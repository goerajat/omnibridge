package com.omnibridge.sbe.engine;

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
import com.omnibridge.persistence.LogStore;
import com.omnibridge.sbe.engine.config.SbeEngineConfig;
import com.omnibridge.sbe.engine.config.SbeSessionConfig;
import com.omnibridge.sbe.engine.session.SbeSession;
import com.omnibridge.sbe.engine.session.SbeSessionAdapter;
import com.omnibridge.sbe.engine.session.SbeSessionState;
import com.omnibridge.sbe.message.SbeMessage;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abstract base class for SBE protocol engines.
 * <p>
 * An SBE engine manages multiple sessions of the same protocol type.
 * It handles:
 * <ul>
 *   <li>Session creation and lifecycle management</li>
 *   <li>Network event loop for TCP connections</li>
 *   <li>Persistence integration for message logging</li>
 *   <li>Session scheduling</li>
 *   <li>Component lifecycle (start/stop)</li>
 * </ul>
 * <p>
 * Protocol-specific implementations (iLink 3, Optiq) must:
 * <ul>
 *   <li>Implement {@link #createSession(SbeSessionConfig)} to create protocol-specific sessions</li>
 *   <li>Implement {@link #getEngineName()} for identification</li>
 * </ul>
 *
 * @param <S> the session type
 * @param <C> the session config type
 * @param <E> the engine config type
 */
public abstract class SbeEngine<S extends SbeSession<C>, C extends SbeSessionConfig, E extends SbeEngineConfig<C>>
        implements Component, ScheduleListener {

    private static final Logger log = LoggerFactory.getLogger(SbeEngine.class);

    protected final E config;
    protected final ComponentProvider componentProvider;
    protected final Map<String, S> sessions = new ConcurrentHashMap<>();
    protected final Map<Integer, TcpAcceptor> acceptors = new ConcurrentHashMap<>();

    // Multi-session port sharing support:
    // Maps port -> list of sessions sharing that port
    protected final Map<Integer, List<S>> portToSessions = new ConcurrentHashMap<>();
    // Maps "sessionKey:port" -> session for acceptor routing (key format is protocol-specific)
    protected final Map<String, S> sessionKeyToSession = new ConcurrentHashMap<>();
    protected final List<GlobalStateListener<S>> stateListeners = new CopyOnWriteArrayList<>();
    protected final List<GlobalMessageListener<S>> messageListeners = new CopyOnWriteArrayList<>();

    protected NetworkEventLoop networkEventLoop;
    protected LogStore logStore;
    protected SessionScheduler scheduler;
    protected ClockProvider clockProvider;
    protected SessionManagementService sessionManagementService;

    protected volatile ComponentState componentState = ComponentState.UNINITIALIZED;
    protected volatile boolean running = false;

    /**
     * Creates an SBE engine with configuration.
     *
     * @param config the engine configuration
     */
    protected SbeEngine(E config) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.componentProvider = null;
    }

    /**
     * Creates an SBE engine using the provider pattern.
     * <p>
     * The provider is used to access NetworkEventLoop, LogStore, ClockProvider,
     * SessionScheduler, and SessionManagementService.
     *
     * @param config the engine configuration
     * @param provider the component provider
     */
    protected SbeEngine(E config, ComponentProvider provider) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.componentProvider = Objects.requireNonNull(provider, "provider is required");

        // Get network event loop from provider
        try {
            this.networkEventLoop = provider.getComponent(NetworkEventLoop.class);
        } catch (IllegalArgumentException e) {
            log.debug("No NetworkEventLoop registered with provider");
        }

        // Get clock provider from provider
        try {
            this.clockProvider = provider.getComponent(ClockProvider.class);
        } catch (IllegalArgumentException e) {
            log.debug("No ClockProvider registered with provider, using system clock");
            this.clockProvider = ClockProvider.system();
        }

        // Get log store from provider
        try {
            this.logStore = provider.getComponent(LogStore.class);
        } catch (IllegalArgumentException e) {
            log.debug("No LogStore registered with provider");
        }

        // Get session scheduler from provider
        try {
            this.scheduler = provider.getComponent(SessionScheduler.class);
            if (this.scheduler != null) {
                this.scheduler.addListener(this);
            }
        } catch (IllegalArgumentException e) {
            log.debug("No SessionScheduler registered with provider");
        }

        // Get session management service from provider
        try {
            this.sessionManagementService = provider.getComponent(SessionManagementService.class);
        } catch (IllegalArgumentException e) {
            log.debug("No SessionManagementService registered with provider");
        }

        log.info("{} created with provider: network={}, persistence={}, clock={}, scheduler={}, sessionMgmt={}",
                getEngineName(), networkEventLoop != null, logStore != null, clockProvider,
                scheduler != null, sessionManagementService != null);
    }

    // =====================================================
    // Abstract Methods - Protocol-Specific Implementation
    // =====================================================

    /**
     * Gets the engine name for logging and identification.
     *
     * @return the engine name
     */
    protected abstract String getEngineName();

    /**
     * Creates a protocol-specific session.
     *
     * @param sessionConfig the session configuration
     * @return the created session
     */
    protected abstract S createSession(C sessionConfig);

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

    public void setSessionManagementService(SessionManagementService service) {
        this.sessionManagementService = service;
    }

    public SessionManagementService getSessionManagementService() {
        return sessionManagementService;
    }

    public E getConfig() {
        return config;
    }

    public ComponentProvider getComponentProvider() {
        return componentProvider;
    }

    public NetworkEventLoop getNetworkEventLoop() {
        return networkEventLoop;
    }

    public LogStore getLogStore() {
        return logStore;
    }

    public ClockProvider getClockProvider() {
        return clockProvider;
    }

    public SessionScheduler getScheduler() {
        return scheduler;
    }

    // =====================================================
    // Session Management
    // =====================================================

    /**
     * Adds a session to the engine.
     *
     * @param sessionConfig the session configuration
     * @return the created session
     */
    public S addSession(C sessionConfig) {
        String sessionId = sessionConfig.getSessionId();
        if (sessions.containsKey(sessionId)) {
            throw new IllegalArgumentException("Session already exists: " + sessionId);
        }

        S session = createSession(sessionConfig);

        // Add internal listeners
        session.addStateListener((s, oldState, newState) -> onSessionStateChange(session, oldState, newState));
        session.addMessageListener((s, msg) -> onSessionMessage(session, msg));

        sessions.put(sessionId, session);

        // Register for multi-session acceptor routing
        if (!sessionConfig.isInitiator()) {
            registerSessionForAcceptorRouting(session, sessionConfig);
        }

        // Register with session management service if available
        if (sessionManagementService != null) {
            DefaultSessionManagementService defaultService =
                    (sessionManagementService instanceof DefaultSessionManagementService)
                            ? (DefaultSessionManagementService) sessionManagementService : null;
            SbeSessionAdapter adapter = new SbeSessionAdapter(session, defaultService);
            sessionManagementService.registerSession(adapter);
        }

        // Associate with schedule if configured
        if (scheduler != null && sessionConfig.getScheduleName() != null) {
            scheduler.associateSession(sessionId, sessionConfig.getScheduleName());
            log.info("Associated session '{}' with schedule '{}'", sessionId, sessionConfig.getScheduleName());
        }

        log.info("Created {} session: {}", getEngineName(), sessionId);

        return session;
    }

    /**
     * Gets a session by ID.
     *
     * @param sessionId the session ID
     * @return the session, or null if not found
     */
    public S getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Gets all sessions.
     *
     * @return collection of all sessions
     */
    public Collection<S> getSessions() {
        return sessions.values();
    }

    /**
     * Connects a session to the specified host and port.
     *
     * @param sessionId the session ID
     * @param host the host to connect to
     * @param port the port to connect to
     */
    public void connect(String sessionId, String host, int port) {
        S session = sessions.get(sessionId);
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
     * Connects a session using its configured host and port.
     *
     * @param sessionId the session ID
     */
    public void connect(String sessionId) {
        S session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        connect(sessionId, session.getHost(), session.getPort());
    }

    /**
     * Disconnects a session.
     *
     * @param sessionId the session ID
     */
    public void disconnect(String sessionId) {
        S session = sessions.get(sessionId);
        if (session != null) {
            session.disconnect();
        }
    }

    /**
     * Removes a session from the engine.
     *
     * @param sessionId the session ID
     */
    public void removeSession(String sessionId) {
        S session = sessions.remove(sessionId);
        if (session != null) {
            session.stop();
            log.info("Removed {} session: {}", getEngineName(), sessionId);
        }
    }

    /**
     * Creates all sessions defined in the engine configuration.
     *
     * @return list of created sessions
     */
    public List<S> createSessionsFromConfig() {
        List<S> createdSessions = new java.util.ArrayList<>();
        for (C sessionConfig : config.getSessions()) {
            try {
                S session = addSession(sessionConfig);
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

    protected void onSessionStateChange(S session, SbeSessionState oldState, SbeSessionState newState) {
        for (GlobalStateListener<S> listener : stateListeners) {
            try {
                listener.onSessionStateChange(session, oldState, newState);
            } catch (Exception e) {
                log.error("Error in state listener: {}", e.getMessage());
            }
        }
    }

    protected void onSessionMessage(S session, SbeMessage message) {
        for (GlobalMessageListener<S> listener : messageListeners) {
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
        S session = sessions.get(sessionId);
        if (session == null) {
            return;
        }

        switch (event.getType()) {
            case SESSION_START -> {
                log.info("[{}] Scheduled session start", sessionId);
                if (session.getSessionState() == SbeSessionState.DISCONNECTED) {
                    connect(sessionId);
                }
            }
            case SESSION_END -> {
                log.info("[{}] Scheduled session end", sessionId);
                session.disconnect();
            }
            case RESET_DUE -> {
                log.info("[{}] Scheduled reset", sessionId);
                session.reset();
            }
            default -> log.debug("[{}] Unhandled schedule event: {}", sessionId, event.getType());
        }
    }

    // =====================================================
    // Listener Management
    // =====================================================

    public void addStateListener(GlobalStateListener<S> listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(GlobalStateListener<S> listener) {
        stateListeners.remove(listener);
    }

    public void addMessageListener(GlobalMessageListener<S> listener) {
        messageListeners.add(listener);
    }

    public void removeMessageListener(GlobalMessageListener<S> listener) {
        messageListeners.remove(listener);
    }

    // =====================================================
    // Acceptor Management
    // =====================================================

    /**
     * Register a session for acceptor routing in multi-session port sharing.
     * Subclasses can override {@link #getSessionRoutingKey(SbeSessionConfig)} to customize the key.
     */
    protected void registerSessionForAcceptorRouting(S session, C sessionConfig) {
        int port = sessionConfig.getPort();
        String routingKey = getSessionRoutingKey(sessionConfig);

        if (routingKey != null && !routingKey.isEmpty()) {
            String fullKey = routingKey + ":" + port;
            sessionKeyToSession.put(fullKey, session);
            log.debug("Registered {} session {} for acceptor routing: {} on port {}",
                    getEngineName(), sessionConfig.getSessionId(), routingKey, port);
        }

        // Track sessions per port
        portToSessions.computeIfAbsent(port, k -> new CopyOnWriteArrayList<>()).add(session);
    }

    /**
     * Get the routing key for a session. This key is used to route incoming connections
     * to the correct session in multi-session port sharing mode.
     * <p>
     * Default implementation uses the session ID. Subclasses can override this
     * to use protocol-specific identifiers (e.g., firmId for iLink3, logicalAccessId for Optiq).
     *
     * @param sessionConfig the session configuration
     * @return the routing key, or null if no routing is possible
     */
    protected String getSessionRoutingKey(C sessionConfig) {
        return sessionConfig.getSessionId();
    }

    /**
     * Find a session by routing key for acceptor routing.
     *
     * @param routingKey the routing key extracted from the handshake message
     * @param port the port the connection was received on
     * @return the matching session, or null if not found
     */
    public S findSessionByRoutingKey(String routingKey, int port) {
        String fullKey = routingKey + ":" + port;
        return sessionKeyToSession.get(fullKey);
    }

    /**
     * Get all sessions configured on a specific port.
     *
     * @param port the port number
     * @return list of sessions on that port, or empty list if none
     */
    public List<S> getSessionsOnPort(int port) {
        return portToSessions.getOrDefault(port, List.of());
    }

    /**
     * Extract the routing key from the initial handshake data.
     * Subclasses should override this to parse protocol-specific handshake messages.
     * <p>
     * Default implementation returns null (no routing possible from handshake).
     *
     * @param buffer the buffer containing handshake data
     * @param offset the offset in the buffer
     * @param length the length of data
     * @return the routing key, or null if not yet determinable
     */
    protected String extractRoutingKeyFromHandshake(DirectBuffer buffer, int offset, int length) {
        return null;
    }

    /**
     * Starts an acceptor for the given session.
     * Supports multiple sessions on the same port (multi-session acceptor).
     *
     * @param session the session to accept connections for
     */
    public void startAcceptor(S session) {
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

        log.info("Starting {} acceptor on port {} (multi-session capable)", getEngineName(), port);

        TcpAcceptor acceptor = networkEventLoop.createAcceptor(port, new MultiSessionAcceptorHandler(port));
        acceptors.put(port, acceptor);
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
                networkEventLoop = new NetworkEventLoop(getEngineName().toLowerCase() + "-io");
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to create network event loop", e);
            }
        }

        // Create sessions from config
        createSessionsFromConfig();

        componentState = ComponentState.INITIALIZED;
        log.info("{} initialized with {} sessions", getEngineName(), sessions.size());
    }

    @Override
    public void startActive() {
        start();
        componentState = ComponentState.ACTIVE;
    }

    @Override
    public void startStandby() {
        componentState = ComponentState.STANDBY;
        log.info("{} in standby mode", getEngineName());
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
        for (S session : sessions.values()) {
            session.stop();
        }

        // Stop network event loop
        if (networkEventLoop != null) {
            networkEventLoop.stop();
        }

        componentState = ComponentState.STOPPED;
        log.info("{} stopped", getEngineName());
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
        for (S session : sessions.values()) {
            if (!session.isInitiator()) {
                startAcceptor(session);
            }
        }

        log.info("{} started", getEngineName());
    }

    @Override
    public String getName() {
        return getEngineName().toLowerCase().replace(" ", "-");
    }

    @Override
    public ComponentState getState() {
        return componentState;
    }

    /**
     * Associates a session with a schedule.
     *
     * @param sessionId the session ID
     * @param scheduleName the schedule name
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
     *
     * @param <S> the session type
     */
    public interface GlobalStateListener<S extends SbeSession<?>> {
        void onSessionStateChange(S session, SbeSessionState oldState, SbeSessionState newState);
    }

    /**
     * Global listener for incoming messages.
     *
     * @param <S> the session type
     */
    public interface GlobalMessageListener<S extends SbeSession<?>> {
        void onMessage(S session, SbeMessage message);
    }

    // =====================================================
    // Acceptor Handler
    // =====================================================

    /**
     * Handler for accepted connections.
     */
    private class AcceptorHandler implements NetworkHandler {

        private final S session;

        AcceptorHandler(S session) {
            this.session = session;
        }

        @Override
        public int getNumBytesToRead(TcpChannel channel) {
            return session.getNumBytesToRead(channel);
        }

        @Override
        public void onConnected(TcpChannel channel) {
            log.info("Accepted {} connection on port {}", getEngineName(), session.getPort());
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
     * Multi-session acceptor handler that supports multiple SBE sessions on a single port.
     * Routes incoming connections to the correct session based on handshake message content.
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
            log.info("Accepted {} connection on port {} (multi-session), awaiting handshake for routing",
                    getEngineName(), port);
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
     * Handler for a pending SBE connection that hasn't been routed to a session yet.
     * Buffers incoming data until the handshake message is received and parsed.
     */
    private class PendingConnectionHandler {

        private static final int MAX_BUFFER_SIZE = 64 * 1024;
        private static final int HEADER_SIZE = 256;

        private final TcpChannel channel;
        private final int port;
        private final MultiSessionAcceptorHandler parentHandler;
        private final byte[] buffer = new byte[MAX_BUFFER_SIZE];
        private int bufferPosition = 0;
        private S routedSession = null;
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

            // Try to extract routing key from buffered handshake data
            org.agrona.concurrent.UnsafeBuffer bufferWrapper =
                    new org.agrona.concurrent.UnsafeBuffer(buffer, 0, bufferPosition);
            String routingKey = extractRoutingKeyFromHandshake(bufferWrapper, 0, bufferPosition);

            if (routingKey != null) {
                log.debug("Extracted routing key from {} handshake: {} on port {}",
                        getEngineName(), routingKey, port);

                // Find the matching session
                routedSession = findSessionByRoutingKey(routingKey, port);

                if (routedSession == null) {
                    // Try to find any session on this port if routing key lookup fails
                    List<S> sessionsOnPort = getSessionsOnPort(port);
                    if (sessionsOnPort.size() == 1) {
                        routedSession = sessionsOnPort.get(0);
                        log.info("Using single session {} for connection on port {} (routing key: {})",
                                routedSession.getSessionId(), port, routingKey);
                    } else {
                        log.error("No {} session configured for routing key: {} on port {}",
                                getEngineName(), routingKey, port);
                        routingFailed = true;
                        channel.close();
                        return length;
                    }
                }

                log.info("Routing {} connection to session {} based on routing key: {}",
                        getEngineName(), routedSession.getSessionId(), routingKey);

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
            } else {
                // If we can't determine the routing key, fall back to single session on port
                List<S> sessionsOnPort = getSessionsOnPort(port);
                if (sessionsOnPort.size() == 1) {
                    routedSession = sessionsOnPort.get(0);
                    log.info("Using single session {} for connection on port {} (no routing key)",
                            routedSession.getSessionId(), port);

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
            }

            return length;
        }

        void onDisconnected(Throwable cause) {
            if (routedSession != null) {
                routedSession.onDisconnected(channel, cause);
            } else {
                log.info("Pending {} connection on port {} disconnected before routing: {}",
                        getEngineName(), port, cause != null ? cause.getMessage() : "clean disconnect");
            }
        }
    }
}
