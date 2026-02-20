package com.omnibridge.ouch.engine;

import com.omnibridge.config.ClockProvider;
import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.config.schedule.ScheduleEvent;
import com.omnibridge.config.schedule.ScheduleListener;
import com.omnibridge.config.schedule.SessionScheduler;
import com.omnibridge.config.session.DefaultSessionManagementService;
import com.omnibridge.config.session.SessionManagementService;
import com.omnibridge.network.NetworkEventLoop;
import com.omnibridge.network.NetworkHandler;
import com.omnibridge.network.TcpAcceptor;
import com.omnibridge.network.TcpChannel;
import com.omnibridge.ouch.engine.config.OuchEngineConfig;
import org.agrona.DirectBuffer;
import com.omnibridge.ouch.engine.config.OuchSessionConfig;
import com.omnibridge.ouch.engine.session.OuchSession;
import com.omnibridge.ouch.engine.session.OuchSessionAdapter;
import com.omnibridge.ouch.engine.session.SessionState;
import com.omnibridge.ouch.message.OuchMessage;
import com.omnibridge.persistence.LogStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * OUCH protocol engine for managing OUCH sessions.
 *
 * <p>The OuchEngine:</p>
 * <ul>
 *   <li>Creates and manages OUCH sessions</li>
 *   <li>Handles network event loop for TCP connections</li>
 *   <li>Integrates with persistence for message logging</li>
 *   <li>Supports session scheduling</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * OuchEngine engine = new OuchEngine(config);
 * engine.initialize();
 * engine.start();
 *
 * OuchSession session = engine.createSession(sessionConfig);
 * engine.connect(session.getSessionId(), "exchange.nasdaq.com", 9200);
 *
 * // Send orders
 * session.sendEnterOrder(Side.BUY, "AAPL", 100, 150.00);
 * }</pre>
 */
public class OuchEngine implements Component, ScheduleListener {

    private static final Logger log = LoggerFactory.getLogger(OuchEngine.class);

    private final OuchEngineConfig config;
    private final ComponentProvider componentProvider;
    private final Map<String, OuchSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, TcpAcceptor> acceptors = new ConcurrentHashMap<>();

    // Multi-session port sharing support:
    // Maps port -> list of sessions sharing that port
    private final Map<Integer, List<OuchSession>> portToSessions = new ConcurrentHashMap<>();
    // Maps "username:port" -> session for acceptor routing
    private final Map<String, OuchSession> usernameToSession = new ConcurrentHashMap<>();
    private final List<GlobalStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<GlobalMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    private NetworkEventLoop networkEventLoop;
    private LogStore logStore;
    private SessionScheduler scheduler;
    private ClockProvider clockProvider;
    private SessionManagementService sessionManagementService;

    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;
    private volatile boolean running = false;
    private MeterRegistry meterRegistry;

    /**
     * Create a new OUCH engine with configuration.
     */
    public OuchEngine(OuchEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.componentProvider = null;
    }

    /**
     * Create a new OUCH engine with default configuration.
     */
    public OuchEngine() {
        this(new OuchEngineConfig());
    }

    /**
     * Create an OUCH engine using the provider pattern.
     * The provider is used to access NetworkEventLoop, LogStore, ClockProvider,
     * SessionScheduler, and SessionManagementService.
     *
     * @param config the OUCH engine configuration
     * @param provider the component provider
     */
    public OuchEngine(OuchEngineConfig config, ComponentProvider provider) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.componentProvider = Objects.requireNonNull(provider, "provider is required");

        // Get network event loop from provider
        try {
            this.networkEventLoop = provider.getComponent(NetworkEventLoop.class);
        } catch (IllegalArgumentException e) {
            log.debug("No NetworkEventLoop registered with provider");
        }

        // Get clock provider from provider (use system clock if not registered)
        try {
            this.clockProvider = provider.getComponent(ClockProvider.class);
        } catch (IllegalArgumentException e) {
            log.debug("No ClockProvider registered with provider, using system clock");
            this.clockProvider = ClockProvider.system();
        }

        // Get log store from provider (may be null if persistence not enabled)
        try {
            this.logStore = provider.getComponent(LogStore.class);
        } catch (IllegalArgumentException e) {
            log.debug("No LogStore registered with provider");
        }

        // Get session scheduler from provider (optional)
        try {
            this.scheduler = provider.getComponent(SessionScheduler.class);
            if (this.scheduler != null) {
                this.scheduler.addListener(this);
            }
        } catch (IllegalArgumentException e) {
            log.debug("No SessionScheduler registered with provider");
        }

        // Get session management service from provider (optional)
        try {
            this.sessionManagementService = provider.getComponent(SessionManagementService.class);
        } catch (IllegalArgumentException e) {
            log.debug("No SessionManagementService registered with provider");
        }

        log.info("OUCH Engine created with provider: network={}, persistence={}, clock={}, scheduler={}, sessionMgmt={}",
                networkEventLoop != null, logStore != null, clockProvider, scheduler != null, sessionManagementService != null);
    }

    // =====================================================
    // Configuration
    // =====================================================

    public void setNetworkEventLoop(NetworkEventLoop eventLoop) {
        this.networkEventLoop = eventLoop;
    }

    public void setLogStore(LogStore logStore) {
        this.logStore = logStore;
    }

    public void setScheduler(SessionScheduler scheduler) {
        this.scheduler = scheduler;
        if (scheduler != null) {
            scheduler.addListener(this);
        }
    }

    public void setClockProvider(ClockProvider clockProvider) {
        this.clockProvider = clockProvider;
    }

    /**
     * Set the session management service.
     * This should be called before creating any sessions.
     *
     * @param service the session management service
     */
    public void setSessionManagementService(SessionManagementService service) {
        this.sessionManagementService = service;
    }

    /**
     * Get the session management service (may be null if not configured).
     */
    public SessionManagementService getSessionManagementService() {
        return sessionManagementService;
    }

    // =====================================================
    // Session Management
    // =====================================================

    /**
     * Create a new OUCH session.
     */
    public OuchSession createSession(OuchSessionConfig sessionConfig) {
        String sessionId = sessionConfig.getSessionId();
        if (sessions.containsKey(sessionId)) {
            throw new IllegalArgumentException("Session already exists: " + sessionId);
        }

        OuchSession session = new OuchSession(
                sessionId,
                sessionConfig.getUsername(),
                sessionConfig.getPassword(),
                sessionConfig.getHost(),
                sessionConfig.getPort(),
                sessionConfig.isInitiator(),
                logStore,
                sessionConfig.getProtocolVersion()
        );

        // Bind metrics if registry is available
        if (meterRegistry != null) {
            session.bindMetrics(meterRegistry);
        }

        // Add internal listeners
        session.addStateListener(this::onSessionStateChange);
        session.addMessageListener(this::onSessionMessage);

        sessions.put(sessionId, session);

        // Register in username lookup for multi-session acceptor routing
        if (!sessionConfig.isInitiator()) {
            registerSessionForAcceptorRouting(session, sessionConfig);
        }

        // Register with session management service if available
        if (sessionManagementService != null) {
            DefaultSessionManagementService defaultService =
                    (sessionManagementService instanceof DefaultSessionManagementService)
                    ? (DefaultSessionManagementService) sessionManagementService : null;
            OuchSessionAdapter adapter = new OuchSessionAdapter(session, defaultService);
            sessionManagementService.registerSession(adapter);
        }

        // Associate with schedule if configured
        if (scheduler != null && sessionConfig.getScheduleName() != null) {
            scheduler.associateSession(sessionId, sessionConfig.getScheduleName());
            log.info("Associated OUCH session '{}' with schedule '{}'", sessionId, sessionConfig.getScheduleName());
        }

        log.info("Created OUCH session: {} (protocol: {})", sessionId, sessionConfig.getProtocolVersion());

        return session;
    }

    /**
     * Get a session by ID.
     */
    public OuchSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Get all sessions.
     */
    public Collection<OuchSession> getSessions() {
        return sessions.values();
    }

    /**
     * Connect a session to a remote host.
     */
    public void connect(String sessionId, String host, int port) {
        OuchSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }

        if (networkEventLoop == null) {
            throw new IllegalStateException("Network event loop not set");
        }

        log.info("Connecting session {} to {}:{}", sessionId, host, port);
        try {
            networkEventLoop.connect(host, port, session);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to connect session " + sessionId, e);
        }
    }

    /**
     * Connect a session using its configured host/port.
     */
    public void connect(String sessionId) {
        OuchSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        connect(sessionId, session.getHost(), session.getPort());
    }

    /**
     * Disconnect a session.
     */
    public void disconnect(String sessionId) {
        OuchSession session = sessions.get(sessionId);
        if (session != null) {
            session.disconnect();
        }
    }

    /**
     * Remove a session.
     */
    public void removeSession(String sessionId) {
        OuchSession session = sessions.remove(sessionId);
        if (session != null) {
            session.stop();
            log.info("Removed OUCH session: {}", sessionId);
        }
    }

    /**
     * Create all sessions defined in the OuchEngineConfig.
     * This is typically called automatically during engine initialization.
     *
     * @return list of created sessions
     */
    public List<OuchSession> createSessionsFromConfig() {
        List<OuchSession> createdSessions = new java.util.ArrayList<>();
        for (OuchSessionConfig sessionConfig : config.getSessions()) {
            try {
                OuchSession session = createSession(sessionConfig);
                createdSessions.add(session);
            } catch (Exception e) {
                log.error("Failed to create session: {}", sessionConfig.getSessionId(), e);
            }
        }
        return createdSessions;
    }

    // =====================================================
    // Internal Callbacks
    // =====================================================

    private void onSessionStateChange(OuchSession session, SessionState oldState, SessionState newState) {
        for (GlobalStateListener listener : stateListeners) {
            try {
                listener.onSessionStateChange(session, oldState, newState);
            } catch (Exception e) {
                log.error("Error in state listener: {}", e.getMessage());
            }
        }
    }

    private void onSessionMessage(OuchSession session, OuchMessage message) {
        for (GlobalMessageListener listener : messageListeners) {
            try {
                listener.onMessage(session, message);
            } catch (Exception e) {
                log.error("Error in message listener: {}", e.getMessage());
            }
        }
    }

    // =====================================================
    // Scheduler Integration
    // =====================================================

    @Override
    public void onScheduleEvent(ScheduleEvent event) {
        String sessionId = event.getSessionId();
        OuchSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }

        switch (event.getType()) {
            case SESSION_START -> {
                log.info("[{}] Scheduled session start", sessionId);
                if (session.getState() == SessionState.DISCONNECTED) {
                    connect(sessionId);
                }
            }
            case SESSION_END -> {
                log.info("[{}] Scheduled session end", sessionId);
                session.disconnect();
            }
            case RESET_DUE -> {
                log.info("[{}] Scheduled reset (EOD)", sessionId);
                // OUCH doesn't have sequence reset like FIX
            }
            default -> log.debug("[{}] Unhandled schedule event: {}", sessionId, event.getType());
        }
    }

    // =====================================================
    // Listener Management
    // =====================================================

    public void addStateListener(GlobalStateListener listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(GlobalStateListener listener) {
        stateListeners.remove(listener);
    }

    public void addMessageListener(GlobalMessageListener listener) {
        messageListeners.add(listener);
    }

    public void removeMessageListener(GlobalMessageListener listener) {
        messageListeners.remove(listener);
    }

    // =====================================================
    // Component Lifecycle
    // =====================================================

    @Override
    public void initialize() {
        if (componentState != ComponentState.UNINITIALIZED) {
            throw new IllegalStateException("Cannot initialize from state: " + componentState);
        }

        if (networkEventLoop == null) {
            try {
                networkEventLoop = new NetworkEventLoop("ouch-io");
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to create network event loop", e);
            }
        }

        // Create sessions from config
        createSessionsFromConfig();

        componentState = ComponentState.INITIALIZED;
        log.info("OUCH Engine initialized with {} sessions", sessions.size());
    }

    @Override
    public void startActive() {
        start();
        componentState = ComponentState.ACTIVE;
    }

    @Override
    public void startStandby() {
        componentState = ComponentState.STANDBY;
        log.info("OUCH Engine in standby mode");
    }

    @Override
    public void becomeActive() {
        start();
        componentState = ComponentState.ACTIVE;
    }

    @Override
    public void becomeStandby() {
        stop();
        componentState = ComponentState.STANDBY;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        // Close all acceptors
        for (TcpAcceptor acceptor : acceptors.values()) {
            try {
                acceptor.close();
            } catch (Exception e) {
                log.error("Error closing acceptor", e);
            }
        }
        acceptors.clear();

        // Stop all sessions
        for (OuchSession session : sessions.values()) {
            session.stop();
        }

        // Stop network event loop
        if (networkEventLoop != null) {
            networkEventLoop.stop();
        }

        componentState = ComponentState.STOPPED;
        log.info("OUCH Engine stopped");
    }

    public void start() {
        if (running) {
            return;
        }

        running = true;

        // Start network event loop
        if (networkEventLoop != null) {
            networkEventLoop.start();
        }

        // Start acceptors for non-initiator sessions
        for (OuchSession session : sessions.values()) {
            if (!session.isInitiator()) {
                startAcceptor(session);
            }
        }

        log.info("OUCH Engine started");
    }

    /**
     * Register a session for acceptor routing in multi-session port sharing.
     * The lookup key is "username:port".
     */
    private void registerSessionForAcceptorRouting(OuchSession session, OuchSessionConfig sessionConfig) {
        int port = sessionConfig.getPort();
        String username = sessionConfig.getUsername();

        if (username != null && !username.isEmpty()) {
            String routingKey = username + ":" + port;
            usernameToSession.put(routingKey, session);
            log.debug("Registered OUCH session {} for acceptor routing: {} on port {}",
                    sessionConfig.getSessionId(), username, port);
        }

        // Track sessions per port
        portToSessions.computeIfAbsent(port, k -> new CopyOnWriteArrayList<>()).add(session);
    }

    /**
     * Find a session by username for acceptor routing.
     *
     * @param username the username from the SoupBinTCP login
     * @param port the port the connection was received on
     * @return the matching session, or null if not found
     */
    public OuchSession findSessionByUsername(String username, int port) {
        String routingKey = username + ":" + port;
        return usernameToSession.get(routingKey);
    }

    /**
     * Get all sessions configured on a specific port.
     *
     * @param port the port number
     * @return list of sessions on that port, or empty list if none
     */
    public List<OuchSession> getSessionsOnPort(int port) {
        return portToSessions.getOrDefault(port, List.of());
    }

    /**
     * Start an acceptor for the given session.
     * Supports multiple sessions on the same port (multi-session acceptor).
     */
    public void startAcceptor(OuchSession session) {
        int port = session.getPort();

        if (acceptors.containsKey(port)) {
            // Acceptor already running on this port - that's OK for multi-session
            log.debug("Acceptor already running on port {}, session {} will share it",
                    port, session.getSessionId());
            return;
        }

        if (networkEventLoop == null) {
            throw new IllegalStateException("Network event loop not set");
        }

        log.info("Starting OUCH acceptor on port {} (multi-session capable)", port);

        TcpAcceptor acceptor = networkEventLoop.createAcceptor(port, new MultiSessionAcceptorHandler(port));
        acceptors.put(port, acceptor);
    }

    @Override
    public String getName() {
        return "ouch-engine";
    }

    @Override
    public ComponentState getState() {
        return componentState;
    }

    /**
     * Get the engine configuration.
     */
    public OuchEngineConfig getConfig() {
        return config;
    }

    /**
     * Get the component provider.
     */
    public ComponentProvider getComponentProvider() {
        return componentProvider;
    }

    /**
     * Get the network event loop.
     */
    public NetworkEventLoop getNetworkEventLoop() {
        return networkEventLoop;
    }

    /**
     * Get the log store.
     */
    public LogStore getLogStore() {
        return logStore;
    }

    /**
     * Get the clock provider.
     */
    public ClockProvider getClockProvider() {
        return clockProvider;
    }

    /**
     * Get the session scheduler.
     */
    public SessionScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Set the meter registry for metrics instrumentation.
     */
    public void setMeterRegistry(MeterRegistry registry) {
        this.meterRegistry = registry;
    }

    /**
     * Get the meter registry.
     */
    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    /**
     * Associate a session with a schedule in the SessionScheduler.
     * This is a convenience method that delegates to the SessionScheduler.
     *
     * @param sessionId the session identifier
     * @param scheduleName the name of the schedule to associate
     * @throws IllegalStateException if no SessionScheduler is configured
     * @throws IllegalArgumentException if the schedule doesn't exist
     */
    public void associateSessionWithSchedule(String sessionId, String scheduleName) {
        if (scheduler == null) {
            throw new IllegalStateException("No SessionScheduler configured");
        }
        scheduler.associateSession(sessionId, scheduleName);
    }

    // =====================================================
    // Listener Interfaces
    // =====================================================

    /**
     * Global listener for session state changes.
     */
    public interface GlobalStateListener {
        void onSessionStateChange(OuchSession session, SessionState oldState, SessionState newState);
    }

    /**
     * Global listener for incoming messages.
     */
    public interface GlobalMessageListener {
        void onMessage(OuchSession session, OuchMessage message);
    }

    // =====================================================
    // Acceptor Handler
    // =====================================================

    /**
     * Handler for accepted connections.
     */
    private class AcceptorHandler implements NetworkHandler {

        private final OuchSession session;

        AcceptorHandler(OuchSession session) {
            this.session = session;
        }

        @Override
        public int getNumBytesToRead(TcpChannel channel) {
            return session.getNumBytesToRead(channel);
        }

        @Override
        public void onConnected(TcpChannel channel) {
            log.info("Accepted OUCH connection on port {}", session.getPort());
            session.setChannel(channel);
            session.onConnected(channel);
        }

        @Override
        public int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length) {
            return session.onDataReceived(channel, buffer, offset, length);
        }

        @Override
        public void onDisconnected(TcpChannel channel, Throwable cause) {
            session.onDisconnected(channel, cause);
        }

        @Override
        public void onConnectFailed(String remoteAddress, Throwable cause) {
            // Not applicable for acceptor
        }

        @Override
        public void onAcceptFailed(Throwable cause) {
            log.error("Accept failed on port {}", session.getPort(), cause);
        }
    }

    /**
     * Multi-session acceptor handler that supports multiple OUCH sessions on a single port.
     * Routes incoming connections to the correct session based on username in the SoupBinTCP login.
     */
    private class MultiSessionAcceptorHandler implements NetworkHandler {

        private final int port;
        private final Map<TcpChannel, PendingConnectionHandler> pendingConnections = new ConcurrentHashMap<>();

        MultiSessionAcceptorHandler(int port) {
            this.port = port;
        }

        @Override
        public int getNumBytesToRead(TcpChannel channel) {
            PendingConnectionHandler pending = pendingConnections.get(channel);
            if (pending != null) {
                return pending.getNumBytesToRead();
            }
            return NetworkHandler.DEFAULT_BYTES_TO_READ;
        }

        @Override
        public void onConnected(TcpChannel channel) {
            log.info("Accepted OUCH connection on port {} (multi-session), awaiting login for routing", port);
            PendingConnectionHandler pending = new PendingConnectionHandler(channel, port, this);
            pendingConnections.put(channel, pending);
        }

        @Override
        public int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length) {
            PendingConnectionHandler pending = pendingConnections.get(channel);
            if (pending != null) {
                return pending.onDataReceived(buffer, offset, length);
            }
            log.warn("Data received for unknown channel on port {}", port);
            return length;
        }

        @Override
        public void onDisconnected(TcpChannel channel, Throwable cause) {
            PendingConnectionHandler pending = pendingConnections.remove(channel);
            if (pending != null) {
                pending.onDisconnected(cause);
            }
        }

        @Override
        public void onConnectFailed(String remoteAddress, Throwable cause) {
            // Not applicable for acceptor
        }

        @Override
        public void onAcceptFailed(Throwable cause) {
            log.error("Accept failed on port {}", port, cause);
        }

        void removePendingConnection(TcpChannel channel) {
            pendingConnections.remove(channel);
        }
    }

    /**
     * Handler for a pending OUCH connection that hasn't been routed to a session yet.
     * Buffers incoming data until the SoupBinTCP login message is received and parsed.
     */
    private class PendingConnectionHandler {

        // SoupBinTCP message types
        private static final byte SOUPBIN_LOGIN_REQUEST = 'L';
        private static final int SOUPBIN_HEADER_SIZE = 3;  // Length (2) + Type (1)
        private static final int SOUPBIN_LOGIN_USERNAME_OFFSET = 0;
        private static final int SOUPBIN_LOGIN_USERNAME_LENGTH = 6;

        private static final int MAX_BUFFER_SIZE = 64 * 1024;
        private static final int HEADER_SIZE = 256;

        private final TcpChannel channel;
        private final int port;
        private final MultiSessionAcceptorHandler parentHandler;
        private final byte[] buffer = new byte[MAX_BUFFER_SIZE];
        private int bufferPosition = 0;
        private OuchSession routedSession = null;
        private boolean routingFailed = false;

        PendingConnectionHandler(TcpChannel channel, int port, MultiSessionAcceptorHandler parentHandler) {
            this.channel = channel;
            this.port = port;
            this.parentHandler = parentHandler;
        }

        int getNumBytesToRead() {
            if (routedSession != null) {
                return routedSession.getNumBytesToRead(channel);
            }
            return HEADER_SIZE;
        }

        int onDataReceived(DirectBuffer directBuffer, int offset, int length) {
            if (routingFailed) {
                return length;
            }

            if (routedSession != null) {
                return routedSession.onDataReceived(channel, directBuffer, offset, length);
            }

            // Buffer incoming data
            int bytesToCopy = Math.min(length, MAX_BUFFER_SIZE - bufferPosition);
            directBuffer.getBytes(offset, buffer, bufferPosition, bytesToCopy);
            bufferPosition += bytesToCopy;

            // Try to extract username from SoupBinTCP login
            String username = tryExtractUsername();
            if (username != null) {
                log.debug("Extracted username from SoupBinTCP login: {} on port {}", username, port);

                // Find the matching session
                routedSession = findSessionByUsername(username, port);

                if (routedSession == null) {
                    // Try to find any session on this port if username routing fails
                    List<OuchSession> sessionsOnPort = getSessionsOnPort(port);
                    if (sessionsOnPort.size() == 1) {
                        routedSession = sessionsOnPort.get(0);
                        log.info("Using single session {} for connection on port {} (username: {})",
                                routedSession.getSessionId(), port, username);
                    } else {
                        log.error("No session configured for username: {} on port {}", username, port);
                        routingFailed = true;
                        channel.close();
                        return length;
                    }
                }

                log.info("Routing OUCH connection to session {} based on username: {}",
                        routedSession.getSessionId(), username);

                // Bind the channel to the session
                routedSession.setChannel(channel);
                routedSession.onConnected(channel);

                // Remove from pending connections
                parentHandler.removePendingConnection(channel);

                // Replay buffered data to the session
                if (bufferPosition > 0) {
                    org.agrona.concurrent.UnsafeBuffer replayBuffer =
                            new org.agrona.concurrent.UnsafeBuffer(buffer, 0, bufferPosition);
                    routedSession.onDataReceived(channel, replayBuffer, 0, bufferPosition);
                }
            }

            return length;
        }

        void onDisconnected(Throwable cause) {
            if (routedSession != null) {
                routedSession.onDisconnected(channel, cause);
            } else {
                log.info("Pending OUCH connection on port {} disconnected before routing: {}",
                        port, cause != null ? cause.getMessage() : "clean disconnect");
            }
        }

        /**
         * Try to extract username from SoupBinTCP Login Request message.
         * SoupBinTCP Login Request format:
         * - Packet Length (2 bytes, big-endian)
         * - Packet Type (1 byte, 'L' for Login Request)
         * - Username (6 bytes, left-justified, space-padded)
         * - Password (10 bytes)
         * - Requested Session (10 bytes)
         * - Requested Sequence Number (20 bytes)
         */
        private String tryExtractUsername() {
            if (bufferPosition < SOUPBIN_HEADER_SIZE + SOUPBIN_LOGIN_USERNAME_LENGTH) {
                return null;  // Not enough data
            }

            // Check if this is a Login Request
            byte messageType = buffer[2];  // After 2-byte length
            if (messageType != SOUPBIN_LOGIN_REQUEST) {
                // Not a login request - might be a different message type
                // For single-session fallback, return empty string
                return "";
            }

            // Extract username (6 bytes starting at offset 3)
            int usernameStart = SOUPBIN_HEADER_SIZE + SOUPBIN_LOGIN_USERNAME_OFFSET;
            int usernameEnd = usernameStart + SOUPBIN_LOGIN_USERNAME_LENGTH;
            return new String(buffer, usernameStart, SOUPBIN_LOGIN_USERNAME_LENGTH).trim();
        }
    }
}
