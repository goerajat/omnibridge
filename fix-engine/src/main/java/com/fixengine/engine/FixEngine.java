package com.fixengine.engine;

import com.fixengine.engine.config.EngineConfig;
import com.fixengine.engine.config.SessionConfig;
import com.fixengine.engine.session.EodEvent;
import com.fixengine.engine.session.EodEventListener;
import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.MessageListener;
import com.fixengine.engine.session.SessionState;
import com.fixengine.engine.session.SessionStateListener;
import com.fixengine.network.NetworkEventLoop;
import com.fixengine.network.NetworkHandler;
import com.fixengine.network.TcpAcceptor;
import com.fixengine.network.TcpChannel;
import com.fixengine.persistence.FixLogStore;
import com.fixengine.persistence.memory.MemoryMappedFixLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
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
public class FixEngine {

    private static final Logger log = LoggerFactory.getLogger(FixEngine.class);

    private final EngineConfig config;
    private final NetworkEventLoop eventLoop;
    private final FixLogStore logStore;

    private final Map<String, FixSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, TcpAcceptor> acceptors = new ConcurrentHashMap<>();

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

    /**
     * Create a FIX engine with the given configuration.
     */
    public FixEngine(EngineConfig config) throws IOException {
        this.config = config;

        // Create event loop
        this.eventLoop = new NetworkEventLoop();
        if (config.getCpuAffinity() >= 0) {
            eventLoop.setCpuAffinity(config.getCpuAffinity());
        }

        // Create log store
        if (config.getPersistencePath() != null) {
            this.logStore = new MemoryMappedFixLogStore(
                config.getPersistencePath(),
                config.getMaxLogFileSize()
            );
        } else {
            this.logStore = null;
        }

        log.info("FIX Engine created: {}", config);
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

        // Start acceptor listeners
        for (SessionConfig sessionConfig : config.getSessions()) {
            if (sessionConfig.isAcceptor()) {
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
     * Create a session from configuration.
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

        sessions.put(sessionId, session);
        log.info("Session created: {}", sessionId);

        return session;
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

        log.info("Connecting session: {} to {}:{}", sessionId, sessionConfig.getHost(), sessionConfig.getPort());

        eventLoop.connect(
            new InetSocketAddress(sessionConfig.getHost(), sessionConfig.getPort()),
            session
        );
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
     * Start an acceptor for the given session configuration.
     */
    private void startAcceptor(SessionConfig sessionConfig) {
        int port = sessionConfig.getPort();

        if (acceptors.containsKey(port)) {
            // Acceptor already running on this port
            return;
        }

        log.info("Starting acceptor on port {}", port);

        TcpAcceptor acceptor = eventLoop.createAcceptor(port, new AcceptorHandler(sessionConfig));
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

    private void checkHeartbeats() {
        for (FixSession session : sessions.values()) {
            try {
                session.checkHeartbeat();
            } catch (Exception e) {
                log.error("Error checking heartbeat for session {}", session.getConfig().getSessionId(), e);
            }
        }
    }

    private void checkSchedules() {
        for (SessionConfig sessionConfig : config.getSessions()) {
            try {
                checkSessionSchedule(sessionConfig);
            } catch (Exception e) {
                log.error("Error checking schedule for session {}", sessionConfig.getSessionId(), e);
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
        LocalTime now = ZonedDateTime.now(zoneId).toLocalTime();

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
        ZonedDateTime nowZoned = ZonedDateTime.now(zoneId);
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

        // Log EOD event to persistence
        if (logStore != null) {
            com.fixengine.persistence.FixLogEntry eodEntry = new com.fixengine.persistence.FixLogEntry(
                Instant.now().toEpochMilli(),
                0,  // seqNum=0 as marker
                com.fixengine.persistence.FixLogEntry.Direction.OUTBOUND,
                sessionId,
                "EOD",  // special msgType for EOD events
                0,
                new byte[0],  // no raw message
                event.toMetadataJson()
            );
            logStore.write(eodEntry);
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
        public int onDataReceived(TcpChannel channel, ByteBuffer data) {
            if (session != null) {
                return session.onDataReceived(channel, data);
            }
            return data.remaining();
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
     * Get the engine configuration.
     */
    public EngineConfig getConfig() {
        return config;
    }

    /**
     * Check if the engine is running.
     */
    public boolean isRunning() {
        return running.get();
    }
}
