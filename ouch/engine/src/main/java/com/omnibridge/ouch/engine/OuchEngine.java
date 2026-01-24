package com.omnibridge.ouch.engine;

import com.omnibridge.config.ClockProvider;
import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
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
    private final Map<String, OuchSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, TcpAcceptor> acceptors = new ConcurrentHashMap<>();
    private final List<GlobalStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<GlobalMessageListener> messageListeners = new CopyOnWriteArrayList<>();

    private NetworkEventLoop networkEventLoop;
    private LogStore logStore;
    private SessionScheduler scheduler;
    private ClockProvider clockProvider;
    private SessionManagementService sessionManagementService;

    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;
    private volatile boolean running = false;

    /**
     * Create a new OUCH engine with configuration.
     */
    public OuchEngine(OuchEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config is required");
    }

    /**
     * Create a new OUCH engine with default configuration.
     */
    public OuchEngine() {
        this(new OuchEngineConfig());
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

        // Add internal listeners
        session.addStateListener(this::onSessionStateChange);
        session.addMessageListener(this::onSessionMessage);

        sessions.put(sessionId, session);

        // Register with session management service if available
        if (sessionManagementService != null) {
            DefaultSessionManagementService defaultService =
                    (sessionManagementService instanceof DefaultSessionManagementService)
                    ? (DefaultSessionManagementService) sessionManagementService : null;
            OuchSessionAdapter adapter = new OuchSessionAdapter(session, defaultService);
            sessionManagementService.registerSession(adapter);
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
        if (networkEventLoop == null) {
            try {
                networkEventLoop = new NetworkEventLoop("ouch-io");
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to create network event loop", e);
            }
        }
        componentState = ComponentState.INITIALIZED;
        log.info("OUCH Engine initialized");
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
     * Start an acceptor for the given session.
     */
    public void startAcceptor(OuchSession session) {
        int port = session.getPort();

        if (acceptors.containsKey(port)) {
            // Acceptor already running on this port
            return;
        }

        if (networkEventLoop == null) {
            throw new IllegalStateException("Network event loop not set");
        }

        log.info("Starting OUCH acceptor on port {}", port);

        TcpAcceptor acceptor = networkEventLoop.createAcceptor(port, new AcceptorHandler(session));
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
}
