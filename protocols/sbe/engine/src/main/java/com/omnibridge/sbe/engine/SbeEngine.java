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
     * Starts an acceptor for the given session.
     *
     * @param session the session to accept connections for
     */
    public void startAcceptor(S session) {
        int port = session.getPort();

        if (acceptors.containsKey(port)) {
            return;
        }

        if (networkEventLoop == null) {
            throw new IllegalStateException("Network event loop not set");
        }

        log.info("Starting {} acceptor on port {}", getEngineName(), port);

        TcpAcceptor acceptor = networkEventLoop.createAcceptor(port, new AcceptorHandler(session));
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
}
