package com.omnibridge.fix.engine;

import com.omnibridge.config.ClockProvider;
import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.config.schedule.ScheduleEvent;
import com.omnibridge.config.schedule.ScheduleListener;
import com.omnibridge.config.schedule.SessionScheduler;
import com.omnibridge.config.session.DefaultSessionManagementService;
import com.omnibridge.config.session.SessionManagementService;
import com.omnibridge.fix.engine.config.EngineConfig;
import com.omnibridge.fix.engine.config.EngineSessionConfig;
import com.omnibridge.fix.engine.config.FixEngineConfig;
import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.engine.session.EodEvent;
import com.omnibridge.fix.engine.session.EodEventListener;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.FixSessionAdapter;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.engine.session.SessionStateListener;
import com.omnibridge.network.NetworkEventLoop;
import com.omnibridge.network.NetworkHandler;
import com.omnibridge.network.TcpAcceptor;
import com.omnibridge.network.TcpChannel;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.memory.MemoryMappedLogStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main FIX engine class.
 * Manages FIX sessions, network I/O, and scheduling.
 */
public class FixEngine implements Component {

    private static final Logger log = LoggerFactory.getLogger(FixEngine.class);

    private final EngineConfig config;
    private final FixEngineConfig engineConfig;
    private final ComponentProvider componentProvider;
    private final NetworkEventLoop eventLoop;
    private final LogStore logStore;
    private final ClockProvider clockProvider;
    private final SessionScheduler sessionScheduler;
    private SessionManagementService sessionManagementService;
    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;

    private final Map<String, FixSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, TcpAcceptor> acceptors = new ConcurrentHashMap<>();

    // Multi-session port sharing support:
    // Maps port -> list of sessions sharing that port
    private final Map<Integer, List<FixSession>> portToSessions = new ConcurrentHashMap<>();
    // Maps "theirSenderCompId:theirTargetCompId:port" -> session for acceptor routing
    // (their sender = our target, their target = our sender)
    private final Map<String, FixSession> compIdToSession = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FixEngine-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> scheduleTask;
    private ScheduledFuture<?> eodTask;

    // Track last EOD trigger per session to avoid duplicate triggers on same day
    private final Map<String, LocalDate> lastEodTrigger = new ConcurrentHashMap<>();

    private final List<SessionStateListener> globalStateListeners = new CopyOnWriteArrayList<>();
    private final List<MessageListener> globalMessageListeners = new CopyOnWriteArrayList<>();
    private final List<EodEventListener> eodListeners = new CopyOnWriteArrayList<>();

    // Optional metrics registry
    private MeterRegistry meterRegistry;

    // Schedule listener for SessionScheduler integration
    private final ScheduleListener scheduleListener = this::onScheduleEvent;

    /**
     * Create a FIX engine with the given configuration.
     * @deprecated Use FixEngine(FixEngineConfig, ComponentProvider) instead
     */
    @Deprecated
    public FixEngine(EngineConfig config) throws IOException {
        this.config = config;
        this.engineConfig = null;
        this.componentProvider = null;
        this.clockProvider = ClockProvider.system();
        this.sessionScheduler = null;

        // Create event loop directly (legacy mode)
        this.eventLoop = new NetworkEventLoop();
        if (config.getCpuAffinity() >= 0) {
            eventLoop.setCpuAffinity(config.getCpuAffinity());
        }

        // Create log store directly (legacy mode)
        if (config.getPersistencePath() != null) {
            this.logStore = new MemoryMappedLogStore(
                config.getPersistencePath(),
                config.getMaxLogFileSize()
            );
        } else {
            this.logStore = null;
        }

        log.info("FIX Engine created (legacy mode): {}", config);
    }

    /**
     * Create a FIX engine using the provider pattern.
     * The provider is used to access the NetworkEventLoop, LogStore, ClockProvider, and SessionScheduler.
     *
     * @param engineConfig the FIX engine configuration
     * @param provider the component provider
     */
    public FixEngine(FixEngineConfig engineConfig, ComponentProvider provider) {
        this.engineConfig = engineConfig;
        this.componentProvider = provider;
        this.config = null; // Not using legacy config

        // Get event loop from provider
        this.eventLoop = provider.getComponent(NetworkEventLoop.class);

        // Get clock provider from provider (use system clock if not registered)
        ClockProvider clock = null;
        try {
            clock = provider.getComponent(ClockProvider.class);
        } catch (IllegalArgumentException e) {
            log.debug("No ClockProvider registered with provider, using system clock");
        }
        this.clockProvider = clock != null ? clock : ClockProvider.system();

        // Get log store from provider (may be null if persistence not enabled)
        LogStore store = null;
        try {
            store = provider.getComponent(LogStore.class);
        } catch (IllegalArgumentException e) {
            // No persistence factory registered, that's OK
            log.debug("No LogStore registered with provider");
        }
        this.logStore = store;

        // Get session scheduler from provider (optional)
        SessionScheduler scheduler = null;
        try {
            scheduler = provider.getComponent(SessionScheduler.class);
        } catch (IllegalArgumentException e) {
            log.debug("No SessionScheduler registered with provider, using built-in scheduling");
        }
        this.sessionScheduler = scheduler;

        // Get session management service from provider (optional)
        try {
            this.sessionManagementService = provider.getComponent(SessionManagementService.class);
        } catch (IllegalArgumentException e) {
            log.debug("No SessionManagementService registered with provider");
        }

        log.info("FIX Engine created with provider: network={}, persistence={}, clock={}, scheduler={}, sessionMgmt={}",
                eventLoop != null, logStore != null, clockProvider, sessionScheduler != null, sessionManagementService != null);
    }

    /**
     * Create a FIX engine with pre-created NetworkEventLoop and LogStore.
     * This constructor is useful when you want direct control over component creation.
     *
     * @param engineConfig the FIX engine configuration
     * @param eventLoop the network event loop
     * @param logStore the persistence store (may be null)
     */
    public FixEngine(FixEngineConfig engineConfig, NetworkEventLoop eventLoop, LogStore logStore) {
        this(engineConfig, eventLoop, logStore, ClockProvider.system(), null);
    }

    /**
     * Create a FIX engine with pre-created components including ClockProvider.
     * This constructor is useful for testing with mock time.
     *
     * @param engineConfig the FIX engine configuration
     * @param eventLoop the network event loop
     * @param logStore the persistence store (may be null)
     * @param clockProvider the clock provider for time sources
     */
    public FixEngine(FixEngineConfig engineConfig, NetworkEventLoop eventLoop, LogStore logStore, ClockProvider clockProvider) {
        this(engineConfig, eventLoop, logStore, clockProvider, null);
    }

    /**
     * Create a FIX engine with pre-created components including SessionScheduler.
     * This constructor is useful for testing with full control over scheduling.
     *
     * @param engineConfig the FIX engine configuration
     * @param eventLoop the network event loop
     * @param logStore the persistence store (may be null)
     * @param clockProvider the clock provider for time sources
     * @param sessionScheduler the session scheduler (may be null for built-in scheduling)
     */
    public FixEngine(FixEngineConfig engineConfig, NetworkEventLoop eventLoop, LogStore logStore,
                     ClockProvider clockProvider, SessionScheduler sessionScheduler) {
        this.engineConfig = engineConfig;
        this.componentProvider = null;
        this.config = null;
        this.eventLoop = eventLoop;
        this.logStore = logStore;
        this.clockProvider = clockProvider != null ? clockProvider : ClockProvider.system();
        this.sessionScheduler = sessionScheduler;

        log.info("FIX Engine created with pre-built components (scheduler={})", sessionScheduler != null);
    }

    /**
     * Start the FIX engine.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Engine already running");
            return;
        }

        log.info("Starting FIX Engine");

        // Start event loop
        eventLoop.start();

        // Start heartbeat monitoring
        heartbeatTask = scheduler.scheduleAtFixedRate(
            this::checkHeartbeats,
            1, 1, TimeUnit.SECONDS
        );

        // Use SessionScheduler if available, otherwise use built-in scheduling
        if (sessionScheduler != null) {
            // Register as listener to handle schedule events
            sessionScheduler.addListener(scheduleListener);
            log.info("Using SessionScheduler for session scheduling");
        } else {
            // Use built-in scheduling
            // Start session scheduling
            scheduleTask = scheduler.scheduleAtFixedRate(
                this::checkSchedules,
                0, 1, TimeUnit.SECONDS
            );

            // Start EOD scheduling (check every minute)
            eodTask = scheduler.scheduleAtFixedRate(
                this::checkEodSchedules,
                0, 1, TimeUnit.MINUTES
            );
            log.info("Using built-in scheduling");
        }

        // Start acceptor listeners (legacy mode only - sessions configured in EngineConfig)
        if (config != null) {
            for (SessionConfig sessionConfig : config.getSessions()) {
                if (sessionConfig.isAcceptor()) {
                    startAcceptor(sessionConfig);
                }
            }
        }

        // Start acceptor listeners for dynamically created sessions
        for (FixSession session : sessions.values()) {
            SessionConfig sessionConfig = session.getConfig();
            if (sessionConfig.isAcceptor() && !acceptors.containsKey(sessionConfig.getPort())) {
                startAcceptor(sessionConfig);
            }
        }

        log.info("FIX Engine started");
    }

    /**
     * Stop the FIX engine.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        log.info("Stopping FIX Engine");

        // Cancel scheduled tasks
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        if (scheduleTask != null) {
            scheduleTask.cancel(false);
        }
        if (eodTask != null) {
            eodTask.cancel(false);
        }

        // Remove listener from SessionScheduler if used
        if (sessionScheduler != null) {
            sessionScheduler.removeListener(scheduleListener);
        }

        // Logout all sessions
        for (FixSession session : sessions.values()) {
            if (session.getState().isLoggedOn()) {
                session.logout("Engine shutting down");
            }
        }

        // Wait briefly for logouts
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Disconnect all sessions
        for (FixSession session : sessions.values()) {
            session.disconnect("Engine stopped");
        }

        // Close acceptors
        for (TcpAcceptor acceptor : acceptors.values()) {
            acceptor.close();
        }
        acceptors.clear();

        // Stop event loop
        eventLoop.stop();

        // Shutdown scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close log store
        if (logStore != null) {
            try {
                logStore.close();
            } catch (Exception e) {
                log.error("Error closing log store", e);
            }
        }

        log.info("FIX Engine stopped");
    }

    // ==================== Session Management ====================

    /**
     * Create a session from SessionConfig.
     */
    public FixSession createSession(SessionConfig sessionConfig) {
        String sessionId = sessionConfig.getSessionId();

        if (sessions.containsKey(sessionId)) {
            throw new IllegalArgumentException("Session already exists: " + sessionId);
        }

        FixSession session = new FixSession(sessionConfig, logStore);

        // Add global listeners
        for (SessionStateListener listener : globalStateListeners) {
            session.addStateListener(listener);
        }
        for (MessageListener listener : globalMessageListeners) {
            session.addMessageListener(listener);
        }

        // Bind metrics if registry is available
        if (meterRegistry != null) {
            session.bindMetrics(meterRegistry);
        }

        sessions.put(sessionId, session);

        // Register in CompID lookup for multi-session acceptor routing
        if (sessionConfig.isAcceptor()) {
            registerSessionForAcceptorRouting(session, sessionConfig);
        }

        // Register with session management service if available
        if (sessionManagementService != null) {
            DefaultSessionManagementService defaultService =
                    (sessionManagementService instanceof DefaultSessionManagementService)
                    ? (DefaultSessionManagementService) sessionManagementService : null;
            FixSessionAdapter adapter = new FixSessionAdapter(session, defaultService);
            sessionManagementService.registerSession(adapter);
        }

        log.info("Session created: {}", sessionId);

        return session;
    }

    /**
     * Create a session from EngineSessionConfig (HOCON-based configuration).
     * This is the preferred method when using the new configuration system.
     *
     * @param engineSessionConfig the session configuration from HOCON
     * @return the created FixSession
     */
    public FixSession createSession(EngineSessionConfig engineSessionConfig) {
        FixSession session = createSession(convertToSessionConfig(engineSessionConfig));

        // Associate with schedule if configured
        engineSessionConfig.getScheduleName().ifPresent(scheduleName -> {
            if (sessionScheduler != null) {
                sessionScheduler.associateSession(session.getConfig().getSessionId(), scheduleName);
                log.info("Associated FIX session '{}' with schedule '{}'",
                        session.getConfig().getSessionId(), scheduleName);
            } else {
                log.warn("Schedule '{}' specified for session '{}' but no SessionScheduler is configured",
                        scheduleName, session.getConfig().getSessionId());
            }
        });

        return session;
    }

    /**
     * Convert EngineSessionConfig to SessionConfig.
     * This bridges the new HOCON-based configuration with the existing session infrastructure.
     */
    private SessionConfig convertToSessionConfig(EngineSessionConfig config) {
        SessionConfig.Builder builder = SessionConfig.builder()
                .sessionName(config.getSessionName())
                .beginString(config.getBeginString())
                .senderCompId(config.getSenderCompId())
                .targetCompId(config.getTargetCompId())
                .port(config.getPort())
                .heartbeatInterval(config.getHeartbeatInterval())
                .resetOnLogon(config.isResetOnLogon())
                .resetOnLogout(config.isResetOnLogout())
                .resetOnDisconnect(config.isResetOnDisconnect())
                .reconnectInterval(config.getReconnectInterval())
                .maxReconnectAttempts(config.getMaxReconnectAttempts())
                .timeZone(config.getTimeZone())
                .resetOnEod(config.isResetOnEod())
                .logMessages(config.isLogMessages())
                .maxMessageLength(config.getMaxMessageLength())
                .maxTagNumber(config.getMaxTagNumber());

        // Set role
        if (config.getRole() == EngineSessionConfig.Role.ACCEPTOR) {
            builder.acceptor();
        } else {
            builder.initiator();
            if (config.getHost() != null) {
                builder.host(config.getHost());
            }
        }

        // Set optional time fields
        config.getStartTime().ifPresent(builder::startTime);
        config.getEndTime().ifPresent(builder::endTime);
        config.getEodTime().ifPresent(builder::eodTime);
        config.getPersistencePath().ifPresent(builder::persistencePath);

        return builder.build();
    }

    /**
     * Create all sessions defined in the FixEngineConfig.
     * This is typically called automatically during engine initialization.
     *
     * @return list of created sessions
     */
    public List<FixSession> createSessionsFromConfig() {
        if (engineConfig == null) {
            log.warn("No FixEngineConfig available, cannot create sessions from config");
            return List.of();
        }

        List<FixSession> createdSessions = new java.util.ArrayList<>();
        for (EngineSessionConfig sessionConfig : engineConfig.getSessions()) {
            try {
                FixSession session = createSession(sessionConfig);
                createdSessions.add(session);
            } catch (Exception e) {
                log.error("Failed to create session: {}", sessionConfig.getSessionName(), e);
            }
        }
        return createdSessions;
    }

    /**
     * Get a session by ID.
     */
    public FixSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Get all sessions.
     */
    public Map<String, FixSession> getSessions() {
        return new ConcurrentHashMap<>(sessions);
    }

    /**
     * Get all sessions as a list.
     */
    public List<FixSession> getAllSessions() {
        return new java.util.ArrayList<>(sessions.values());
    }

    /**
     * Get a session by its configured name.
     */
    public FixSession getSessionByName(String name) {
        return sessions.values().stream()
            .filter(s -> name.equals(s.getConfig().getSessionName()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get a session by SenderCompID.
     */
    public FixSession getSessionBySenderCompId(String senderCompId) {
        return sessions.values().stream()
            .filter(s -> senderCompId.equals(s.getConfig().getSenderCompId()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get a session by TargetCompID.
     */
    public FixSession getSessionByTargetCompId(String targetCompId) {
        return sessions.values().stream()
            .filter(s -> targetCompId.equals(s.getConfig().getTargetCompId()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get all TCP-connected sessions.
     */
    public List<FixSession> getConnectedSessions() {
        return sessions.values().stream()
            .filter(s -> s.getState().isConnected())
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get all TCP-disconnected sessions.
     */
    public List<FixSession> getDisconnectedSessions() {
        return sessions.values().stream()
            .filter(s -> !s.getState().isConnected())
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get all FIX logged-on sessions.
     */
    public List<FixSession> getLoggedOnSessions() {
        return sessions.values().stream()
            .filter(s -> s.getState().isLoggedOn())
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Connect an initiator session.
     */
    public void connect(String sessionId) {
        FixSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }

        SessionConfig sessionConfig = session.getConfig();
        if (!sessionConfig.isInitiator()) {
            throw new IllegalArgumentException("Session is not an initiator: " + sessionId);
        }

        log.info("Connecting session: {} to {}:{}{}",
                sessionId, sessionConfig.getHost(), sessionConfig.getPort(),
                sessionConfig.isSslEnabled() ? " (SSL)" : "");

        try {
            eventLoop.connect(
                sessionConfig.getHost(),
                sessionConfig.getPort(),
                session,
                sessionConfig.getSslConfig()
            );
        } catch (IOException e) {
            log.error("Failed to connect session {}: {}", sessionId, e.getMessage());
            session.onConnectFailed(sessionConfig.getHost() + ":" + sessionConfig.getPort(), e);
        }
    }

    /**
     * Disconnect a session.
     */
    public void disconnect(String sessionId) {
        FixSession session = sessions.get(sessionId);
        if (session != null) {
            session.disconnect("User requested disconnect");
        }
    }

    /**
     * Register a session for acceptor routing in multi-session port sharing.
     * The lookup key is "theirSenderCompId:theirTargetCompId:port" where
     * their sender = our target and their target = our sender.
     */
    private void registerSessionForAcceptorRouting(FixSession session, SessionConfig sessionConfig) {
        int port = sessionConfig.getPort();
        String ourSender = sessionConfig.getSenderCompId();
        String ourTarget = sessionConfig.getTargetCompId();

        // Key is from the client's perspective: theirSender:theirTarget:port
        // Their sender = our target, Their target = our sender
        String routingKey = ourTarget + ":" + ourSender + ":" + port;
        compIdToSession.put(routingKey, session);

        // Track sessions per port
        portToSessions.computeIfAbsent(port, k -> new CopyOnWriteArrayList<>()).add(session);

        log.debug("Registered session {} for acceptor routing: {} on port {}",
                sessionConfig.getSessionId(), routingKey, port);
    }

    /**
     * Find a session by incoming CompIDs for acceptor routing.
     *
     * @param incomingSenderCompId the SenderCompID from the incoming message (their sender)
     * @param incomingTargetCompId the TargetCompID from the incoming message (their target)
     * @param port the port the connection was received on
     * @return the matching session, or null if not found
     */
    public FixSession findSessionByCompIds(String incomingSenderCompId, String incomingTargetCompId, int port) {
        String routingKey = incomingSenderCompId + ":" + incomingTargetCompId + ":" + port;
        return compIdToSession.get(routingKey);
    }

    /**
     * Get all sessions configured on a specific port.
     *
     * @param port the port number
     * @return list of sessions on that port, or empty list if none
     */
    public List<FixSession> getSessionsOnPort(int port) {
        return portToSessions.getOrDefault(port, List.of());
    }

    /**
     * Start an acceptor for the given session configuration.
     * Supports multiple sessions on the same port (multi-session acceptor).
     */
    private void startAcceptor(SessionConfig sessionConfig) {
        int port = sessionConfig.getPort();

        if (acceptors.containsKey(port)) {
            // Acceptor already running on this port - that's OK for multi-session
            log.debug("Acceptor already running on port {}, session {} will share it",
                    port, sessionConfig.getSessionId());
            return;
        }

        log.info("Starting acceptor on port {} (multi-session capable)", port);

        TcpAcceptor acceptor = eventLoop.createAcceptor(port, new MultiSessionAcceptorHandler(port));
        acceptors.put(port, acceptor);
    }

    // ==================== Listeners ====================

    /**
     * Add a global session state listener (applies to all sessions).
     */
    public void addStateListener(SessionStateListener listener) {
        globalStateListeners.add(listener);
        // Add to existing sessions
        for (FixSession session : sessions.values()) {
            session.addStateListener(listener);
        }
    }

    /**
     * Remove a global session state listener.
     */
    public void removeStateListener(SessionStateListener listener) {
        globalStateListeners.remove(listener);
        for (FixSession session : sessions.values()) {
            session.removeStateListener(listener);
        }
    }

    /**
     * Add a global message listener (applies to all sessions).
     */
    public void addMessageListener(MessageListener listener) {
        globalMessageListeners.add(listener);
        for (FixSession session : sessions.values()) {
            session.addMessageListener(listener);
        }
    }

    /**
     * Remove a global message listener.
     */
    public void removeMessageListener(MessageListener listener) {
        globalMessageListeners.remove(listener);
        for (FixSession session : sessions.values()) {
            session.removeMessageListener(listener);
        }
    }

    // ==================== Scheduling ====================

    /**
     * Handle schedule events from SessionScheduler.
     * This method is called when using the SessionScheduler component.
     */
    private void onScheduleEvent(ScheduleEvent event) {
        String sessionId = event.getSessionId();
        FixSession session = sessions.get(sessionId);

        switch (event.getType()) {
            case SESSION_START -> {
                log.info("[{}] Schedule event: SESSION_START", sessionId);
                if (session == null) {
                    // Session not yet created - this shouldn't happen normally
                    // as sessions should be created during engine initialization
                    log.warn("[{}] Session not found for SESSION_START event", sessionId);
                    return;
                }
                SessionConfig sessionConfig = session.getConfig();
                if (sessionConfig.isInitiator() && session.getState() == SessionState.DISCONNECTED) {
                    connect(sessionId);
                }
            }

            case SESSION_END -> {
                log.info("[{}] Schedule event: SESSION_END", sessionId);
                if (session != null && session.getState().isLoggedOn()) {
                    session.logout("Session schedule ended");
                }
            }

            case RESET_DUE -> {
                log.info("[{}] Schedule event: RESET_DUE (EOD)", sessionId);
                if (session != null) {
                    triggerEod(session, EodEvent.Type.SCHEDULED);
                }
            }

            case WARNING_SESSION_END -> {
                log.debug("[{}] Schedule event: WARNING_SESSION_END - {}", sessionId, event.getMessage());
                // Could notify listeners or log for monitoring
            }

            case WARNING_RESET -> {
                log.debug("[{}] Schedule event: WARNING_RESET - {}", sessionId, event.getMessage());
                // Could notify listeners or log for monitoring
            }
        }
    }

    private void checkHeartbeats() {
        for (FixSession session : sessions.values()) {
            // Only check heartbeat for sessions that are logged on
            if (!session.getState().isLoggedOn()) {
                continue;
            }
            try {
                session.checkHeartbeat();
            } catch (Exception e) {
                log.error("Error checking heartbeat for session {}", session.getConfig().getSessionId(), e);
            }
        }
    }

    private void checkSchedules() {
        // Check schedules for legacy config sessions
        if (config != null) {
            for (SessionConfig sessionConfig : config.getSessions()) {
                try {
                    checkSessionSchedule(sessionConfig);
                } catch (Exception e) {
                    log.error("Error checking schedule for session {}", sessionConfig.getSessionId(), e);
                }
            }
        }

        // Check schedules for dynamically created sessions
        for (FixSession session : sessions.values()) {
            try {
                checkSessionSchedule(session.getConfig());
            } catch (Exception e) {
                log.error("Error checking schedule for session {}", session.getConfig().getSessionId(), e);
            }
        }
    }

    private void checkSessionSchedule(SessionConfig sessionConfig) {
        LocalTime startTime = sessionConfig.getStartTime();
        LocalTime endTime = sessionConfig.getEndTime();

        if (startTime == null || endTime == null) {
            // No schedule configured
            return;
        }

        String sessionId = sessionConfig.getSessionId();
        FixSession session = sessions.get(sessionId);

        ZoneId zoneId = ZoneId.of(sessionConfig.getTimeZone());
        LocalTime now = ZonedDateTime.now(clockProvider.getClock().withZone(zoneId)).toLocalTime();

        boolean inSchedule = isInSchedule(now, startTime, endTime);

        if (inSchedule) {
            // Should be connected
            if (session == null) {
                session = createSession(sessionConfig);
            }

            if (sessionConfig.isInitiator() &&
                session.getState() == SessionState.DISCONNECTED) {
                connect(sessionId);
            }
        } else {
            // Should be disconnected
            if (session != null && session.getState().isLoggedOn()) {
                session.logout("Session schedule ended");
            }
        }
    }

    private boolean isInSchedule(LocalTime now, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            // Normal case: start < end (e.g., 08:00 to 17:00)
            return !now.isBefore(start) && now.isBefore(end);
        } else {
            // Overnight case: start > end (e.g., 20:00 to 06:00)
            return !now.isBefore(start) || now.isBefore(end);
        }
    }

    // ==================== EOD (End of Day) ====================

    /**
     * Check EOD schedules for all sessions.
     * Called every minute by the scheduler.
     */
    private void checkEodSchedules() {
        for (FixSession session : sessions.values()) {
            try {
                checkSessionEod(session);
            } catch (Exception e) {
                log.error("Error checking EOD for session {}", session.getConfig().getSessionId(), e);
            }
        }
    }

    private void checkSessionEod(FixSession session) {
        SessionConfig sessionConfig = session.getConfig();
        LocalTime eodTime = sessionConfig.getEodTime();

        if (eodTime == null || !sessionConfig.isResetOnEod()) {
            // EOD not configured for this session
            return;
        }

        String sessionId = sessionConfig.getSessionId();
        ZoneId zoneId = ZoneId.of(sessionConfig.getTimeZone());
        ZonedDateTime nowZoned = ZonedDateTime.now(clockProvider.getClock().withZone(zoneId));
        LocalTime now = nowZoned.toLocalTime();
        LocalDate today = nowZoned.toLocalDate();

        // Check if we're within the EOD trigger window (within 1 minute of configured time)
        boolean isEodTime = !now.isBefore(eodTime) && now.isBefore(eodTime.plusMinutes(1));

        if (isEodTime) {
            // Check if we already triggered EOD today
            LocalDate lastTrigger = lastEodTrigger.get(sessionId);
            if (lastTrigger != null && lastTrigger.equals(today)) {
                // Already triggered today
                return;
            }

            // Trigger EOD
            triggerEod(session, EodEvent.Type.SCHEDULED);
            lastEodTrigger.put(sessionId, today);
        }
    }

    /**
     * Trigger an EOD event for a session.
     * This resets sequence numbers and logs the event to persistence.
     *
     * @param session the session to trigger EOD for
     * @param type the type of EOD event (SCHEDULED or MANUAL)
     */
    public void triggerEod(FixSession session, EodEvent.Type type) {
        String sessionId = session.getConfig().getSessionId();
        int previousOutSeq = session.getOutgoingSeqNum();
        int previousInSeq = session.getExpectedIncomingSeqNum();

        log.info("[{}] Triggering EOD event: type={}, previousOutSeq={}, previousInSeq={}",
                sessionId, type, previousOutSeq, previousInSeq);

        // Create EOD event
        EodEvent event = new EodEvent(sessionId, Instant.now(), type, previousOutSeq, previousInSeq);

        // Log EOD event to persistence (routed through event loop to ensure
        // single-writer semantics for Chronicle Queue)
        if (logStore != null) {
            LogEntry eodEntry = LogEntry.builder()
                .timestamp(Instant.now().toEpochMilli())
                .sequenceNumber(0)  // seqNum=0 as marker
                .direction(LogEntry.Direction.OUTBOUND)
                .streamName(sessionId)
                .metadata(event.toMetadataJson())
                .rawMessage(new byte[0])  // no raw message
                .build();
            eventLoop.execute(() -> logStore.write(eodEntry));
        }

        // Reset sequence numbers
        session.resetSequenceNumbers();

        // Notify listeners
        for (EodEventListener listener : eodListeners) {
            try {
                listener.onEodEvent(session, event);
            } catch (Exception e) {
                log.error("[{}] Error notifying EOD listener", sessionId, e);
            }
        }
    }

    /**
     * Manually trigger an EOD event for a session.
     *
     * @param sessionId the session ID
     */
    public void triggerEod(String sessionId) {
        FixSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        triggerEod(session, EodEvent.Type.MANUAL);
    }

    /**
     * Add an EOD event listener.
     */
    public void addEodListener(EodEventListener listener) {
        eodListeners.add(listener);
    }

    /**
     * Remove an EOD event listener.
     */
    public void removeEodListener(EodEventListener listener) {
        eodListeners.remove(listener);
    }

    // ==================== Inner Classes ====================

    /**
     * Handler for accepted connections.
     */
    private class AcceptorHandler implements NetworkHandler {

        private final SessionConfig templateConfig;
        private FixSession session;

        AcceptorHandler(SessionConfig templateConfig) {
            this.templateConfig = templateConfig;
        }

        @Override
        public int getNumBytesToRead(TcpChannel channel) {
            if (session != null) {
                return session.getNumBytesToRead(channel);
            }
            return NetworkHandler.DEFAULT_BYTES_TO_READ;
        }

        @Override
        public void onConnected(TcpChannel channel) {
            log.info("Accepted connection on port {}", templateConfig.getPort());

            // Create or reuse session
            String sessionId = templateConfig.getSessionId();
            session = sessions.get(sessionId);

            if (session == null) {
                session = createSession(templateConfig);
            }

            session.setChannel(channel);
            session.onConnected(channel);
        }

        @Override
        public int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length) {
            if (session != null) {
                return session.onDataReceived(channel, buffer, offset, length);
            }
            return length;
        }

        @Override
        public void onDisconnected(TcpChannel channel, Throwable cause) {
            if (session != null) {
                session.onDisconnected(channel, cause);
            }
        }

        @Override
        public void onConnectFailed(String remoteAddress, Throwable cause) {
            // Not applicable for acceptor
        }

        @Override
        public void onAcceptFailed(Throwable cause) {
            log.error("Accept failed on port {}", templateConfig.getPort(), cause);
        }
    }

    /**
     * Multi-session acceptor handler that supports multiple FIX sessions on a single port.
     * Routes incoming connections to the correct session based on CompIDs in the Logon message.
     *
     * <p>This handler creates a {@link PendingConnectionHandler} for each new connection,
     * which buffers incoming data until the Logon message is received and parsed.
     * Once CompIDs are extracted, the connection is routed to the appropriate session.</p>
     */
    private class MultiSessionAcceptorHandler implements NetworkHandler {

        private final int port;

        // Map of channel to pending handler (for connections not yet routed)
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
            log.info("Accepted connection on port {} (multi-session), awaiting Logon for routing", port);

            // Create a pending connection handler to buffer data and route
            PendingConnectionHandler pending = new PendingConnectionHandler(channel, port, this);
            pendingConnections.put(channel, pending);
        }

        @Override
        public int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length) {
            PendingConnectionHandler pending = pendingConnections.get(channel);
            if (pending != null) {
                return pending.onDataReceived(buffer, offset, length);
            }
            // This shouldn't happen if the connection was properly routed
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

        /**
         * Remove a pending connection after it has been routed to a session.
         */
        void removePendingConnection(TcpChannel channel) {
            pendingConnections.remove(channel);
        }
    }

    /**
     * Handler for a pending connection that hasn't been routed to a session yet.
     * Buffers incoming data until the Logon message is received and parsed.
     */
    private class PendingConnectionHandler {

        private static final byte SOH = 0x01;
        private static final int MAX_BUFFER_SIZE = 64 * 1024;
        private static final int HEADER_SIZE = 256; // Enough for Logon header

        private final TcpChannel channel;
        private final int port;
        private final MultiSessionAcceptorHandler parentHandler;
        private final byte[] buffer = new byte[MAX_BUFFER_SIZE];
        private int bufferPosition = 0;
        private FixSession routedSession = null;
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
            // Request enough bytes to parse the Logon header
            return HEADER_SIZE;
        }

        int onDataReceived(DirectBuffer directBuffer, int offset, int length) {
            if (routingFailed) {
                return length; // Discard data after routing failure
            }

            if (routedSession != null) {
                // Already routed - delegate to session
                return routedSession.onDataReceived(channel, directBuffer, offset, length);
            }

            // Buffer incoming data
            int bytesToCopy = Math.min(length, MAX_BUFFER_SIZE - bufferPosition);
            directBuffer.getBytes(offset, buffer, bufferPosition, bytesToCopy);
            bufferPosition += bytesToCopy;

            // Try to extract CompIDs from the buffered data
            String[] compIds = tryExtractCompIds();
            if (compIds != null) {
                String senderCompId = compIds[0];
                String targetCompId = compIds[1];

                log.debug("Extracted CompIDs from Logon: sender={}, target={} on port {}",
                        senderCompId, targetCompId, port);

                // Find the matching session
                routedSession = findSessionByCompIds(senderCompId, targetCompId, port);

                if (routedSession == null) {
                    log.error("No session configured for CompIDs: sender={}, target={} on port {}",
                            senderCompId, targetCompId, port);
                    routingFailed = true;
                    sendLogoutAndDisconnect(senderCompId, targetCompId,
                            "Unknown CompID combination: " + senderCompId + "/" + targetCompId);
                    return length;
                }

                log.info("Routing connection to session {} based on CompIDs: sender={}, target={}",
                        routedSession.getConfig().getSessionId(), senderCompId, targetCompId);

                // Bind the channel to the session
                routedSession.setChannel(channel);
                routedSession.onConnected(channel);

                // Keep the PendingConnectionHandler in the pendingConnections map
                // so that subsequent data/disconnect events from the NetworkEventLoop
                // (which still routes through MultiSessionAcceptorHandler) can be
                // delegated to the routed session via this handler's routedSession field.

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
                log.info("Pending connection on port {} disconnected before routing: {}",
                        port, cause != null ? cause.getMessage() : "clean disconnect");
            }
        }

        /**
         * Try to extract SenderCompID and TargetCompID from the buffered Logon message.
         * Returns [senderCompId, targetCompId] if successful, null if more data is needed.
         */
        private String[] tryExtractCompIds() {
            // Look for tag 49 (SenderCompID) and tag 56 (TargetCompID) in the buffer
            String senderCompId = extractTag(49);
            String targetCompId = extractTag(56);

            if (senderCompId != null && targetCompId != null) {
                return new String[]{senderCompId, targetCompId};
            }

            return null;
        }

        /**
         * Extract a tag value from the buffered data.
         */
        private String extractTag(int tag) {
            // Build the tag prefix pattern: "tag="
            String tagPrefix = tag + "=";
            byte[] prefix = tagPrefix.getBytes();

            // Search for the tag in the buffer
            for (int i = 0; i < bufferPosition - prefix.length; i++) {
                boolean found = true;
                // Check if preceded by SOH or start of buffer
                if (i > 0 && buffer[i - 1] != SOH) {
                    continue;
                }
                for (int j = 0; j < prefix.length; j++) {
                    if (buffer[i + j] != prefix[j]) {
                        found = false;
                        break;
                    }
                }
                if (found) {
                    // Found the tag, extract value until SOH
                    int valueStart = i + prefix.length;
                    int valueEnd = valueStart;
                    while (valueEnd < bufferPosition && buffer[valueEnd] != SOH) {
                        valueEnd++;
                    }
                    if (valueEnd < bufferPosition) {
                        return new String(buffer, valueStart, valueEnd - valueStart);
                    }
                }
            }
            return null;
        }

        /**
         * Send a Logout message and disconnect for unknown CompID combinations.
         */
        private void sendLogoutAndDisconnect(String theirSender, String theirTarget, String reason) {
            try {
                // Build a simple Logout message
                StringBuilder logout = new StringBuilder();

                // BeginString
                logout.append("8=FIX.4.2").append((char) SOH);

                // Body (will update length later)
                StringBuilder body = new StringBuilder();
                body.append("35=5").append((char) SOH);  // MsgType=Logout
                body.append("49=").append(theirTarget).append((char) SOH);  // SenderCompID (our sender = their target)
                body.append("56=").append(theirSender).append((char) SOH);  // TargetCompID (our target = their sender)
                body.append("34=1").append((char) SOH);  // SeqNum
                body.append("52=").append(java.time.Instant.now().toString().replace("T", "-").replace("Z", ""))
                        .append((char) SOH);  // SendingTime
                body.append("58=").append(reason).append((char) SOH);  // Text

                // BodyLength
                logout.append("9=").append(body.length()).append((char) SOH);

                // Append body
                logout.append(body);

                // Calculate checksum
                String msgWithoutChecksum = logout.toString();
                int checksum = 0;
                for (int i = 0; i < msgWithoutChecksum.length(); i++) {
                    checksum += msgWithoutChecksum.charAt(i);
                }
                checksum = checksum % 256;

                // Append checksum
                logout.append("10=").append(String.format("%03d", checksum)).append((char) SOH);

                // Send the message
                byte[] logoutBytes = logout.toString().getBytes();
                channel.writeRaw(logoutBytes, 0, logoutBytes.length);

                log.info("Sent Logout for unknown CompIDs: {}", reason);
            } catch (Exception e) {
                log.error("Error sending Logout for unknown CompIDs", e);
            } finally {
                // Disconnect after a short delay
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                channel.close();
            }
        }
    }

    /**
     * Get the legacy engine configuration.
     * @deprecated Use getEngineConfig() instead
     */
    @Deprecated
    public EngineConfig getConfig() {
        return config;
    }

    /**
     * Get the FIX engine configuration.
     */
    public FixEngineConfig getEngineConfig() {
        return engineConfig;
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
    public NetworkEventLoop getEventLoop() {
        return eventLoop;
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
     * Get the session scheduler (may be null if using built-in scheduling).
     */
    public SessionScheduler getSessionScheduler() {
        return sessionScheduler;
    }

    /**
     * Get the session management service (may be null if not configured).
     */
    public SessionManagementService getSessionManagementService() {
        return sessionManagementService;
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
     * Set the meter registry for metrics instrumentation.
     * Should be called before creating sessions.
     *
     * @param registry the Micrometer meter registry
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
        if (sessionScheduler == null) {
            throw new IllegalStateException("No SessionScheduler configured");
        }
        sessionScheduler.associateSession(sessionId, scheduleName);
    }

    /**
     * Check if the engine is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    // ==================== Component Interface ====================

    @Override
    public void initialize() throws Exception {
        if (componentState != ComponentState.UNINITIALIZED) {
            throw new IllegalStateException("Cannot initialize from state: " + componentState);
        }
        // Create sessions from config if available
        if (engineConfig != null) {
            createSessionsFromConfig();
        }
        componentState = ComponentState.INITIALIZED;
        log.info("FixEngine component initialized");
    }

    @Override
    public void startActive() throws Exception {
        if (componentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start active from state: " + componentState);
        }
        start();
        componentState = ComponentState.ACTIVE;
    }

    @Override
    public void startStandby() throws Exception {
        if (componentState != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start standby from state: " + componentState);
        }
        // In standby mode, sessions are ready but not processing
        componentState = ComponentState.STANDBY;
        log.info("FixEngine started in STANDBY mode");
    }

    @Override
    public void becomeActive() throws Exception {
        if (componentState != ComponentState.STANDBY) {
            throw new IllegalStateException("Cannot become active from state: " + componentState);
        }
        start();
        componentState = ComponentState.ACTIVE;
        log.info("FixEngine transitioned to ACTIVE mode");
    }

    @Override
    public void becomeStandby() throws Exception {
        if (componentState != ComponentState.ACTIVE) {
            throw new IllegalStateException("Cannot become standby from state: " + componentState);
        }
        stop();
        componentState = ComponentState.STANDBY;
        log.info("FixEngine transitioned to STANDBY mode");
    }

    @Override
    public String getName() {
        return "fix-engine";
    }

    @Override
    public ComponentState getState() {
        return componentState;
    }
}
