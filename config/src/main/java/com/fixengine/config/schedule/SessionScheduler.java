package com.fixengine.config.schedule;

import com.fixengine.config.ClockProvider;
import com.fixengine.config.Component;
import com.fixengine.config.ComponentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler component that monitors session schedules and emits events.
 *
 * <p>The SessionScheduler:</p>
 * <ul>
 *   <li>Manages named schedule definitions</li>
 *   <li>Associates sessions with schedules</li>
 *   <li>Monitors time and emits events when sessions should start/stop/reset</li>
 *   <li>Uses ClockProvider for testable time</li>
 *   <li>Can be registered with ComponentProvider</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Create scheduler
 * SessionScheduler scheduler = new SessionScheduler(clockProvider);
 *
 * // Define a schedule
 * SessionSchedule usEquity = SessionSchedule.builder()
 *     .name("us-equity")
 *     .timezone("America/New_York")
 *     .addWindow(TimeWindow.builder()
 *         .startTime(9, 30)
 *         .endTime(16, 0)
 *         .weekdays()
 *         .build())
 *     .resetSchedule(ResetSchedule.fixedTime(LocalTime.of(17, 0)))
 *     .build();
 *
 * // Register schedule and associate with session
 * scheduler.registerSchedule(usEquity);
 * scheduler.associateSession("my-session", "us-equity");
 *
 * // Add listener
 * scheduler.addListener(event -> {
 *     if (event.isStartEvent()) {
 *         engine.connect(event.getSessionId());
 *     } else if (event.isEndEvent()) {
 *         engine.disconnect(event.getSessionId());
 *     }
 * });
 *
 * // Start scheduler
 * scheduler.start();
 * }</pre>
 */
public class SessionScheduler implements Component {

    private static final Logger log = LoggerFactory.getLogger(SessionScheduler.class);

    private final ClockProvider clockProvider;
    private final Map<String, SessionSchedule> schedules = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToSchedule = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    private final List<ScheduleListener> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> checkTask;

    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;
    private volatile boolean running = false;

    // Configuration
    private long checkIntervalSeconds = 1;
    private int resetToleranceMinutes = 1;
    private int warningMinutesBeforeEnd = 5;
    private int warningMinutesBeforeReset = 5;

    /**
     * Internal state tracking for each session.
     */
    private static class SessionState {
        boolean wasActive = false;
        boolean resetTriggeredToday = false;
        ZonedDateTime lastResetDate = null;
        ZonedDateTime lastWarningEnd = null;
        ZonedDateTime lastWarningReset = null;
    }

    /**
     * Create a SessionScheduler with the given clock provider.
     *
     * @param clockProvider the clock provider for time
     */
    public SessionScheduler(ClockProvider clockProvider) {
        this.clockProvider = Objects.requireNonNull(clockProvider, "clockProvider is required");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessionScheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Register a schedule definition.
     *
     * @param schedule the schedule to register
     */
    public void registerSchedule(SessionSchedule schedule) {
        schedules.put(schedule.getName(), schedule);
        log.info("Registered schedule: {}", schedule.getName());
    }

    /**
     * Get a schedule by name.
     *
     * @param name the schedule name
     * @return the schedule, or null if not found
     */
    public SessionSchedule getSchedule(String name) {
        return schedules.get(name);
    }

    /**
     * Get all registered schedules.
     *
     * @return map of schedule name to schedule
     */
    public Map<String, SessionSchedule> getSchedules() {
        return Map.copyOf(schedules);
    }

    /**
     * Associate a session with a schedule.
     *
     * @param sessionId the session identifier
     * @param scheduleName the name of the schedule to use
     * @throws IllegalArgumentException if the schedule doesn't exist
     */
    public void associateSession(String sessionId, String scheduleName) {
        if (!schedules.containsKey(scheduleName)) {
            throw new IllegalArgumentException("Unknown schedule: " + scheduleName);
        }
        sessionToSchedule.put(sessionId, scheduleName);
        sessionStates.put(sessionId, new SessionState());
        log.info("Associated session '{}' with schedule '{}'", sessionId, scheduleName);
    }

    /**
     * Remove a session association.
     *
     * @param sessionId the session identifier
     */
    public void removeSession(String sessionId) {
        sessionToSchedule.remove(sessionId);
        sessionStates.remove(sessionId);
    }

    /**
     * Get the schedule for a session.
     *
     * @param sessionId the session identifier
     * @return the schedule, or null if not associated
     */
    public SessionSchedule getScheduleForSession(String sessionId) {
        String scheduleName = sessionToSchedule.get(sessionId);
        return scheduleName != null ? schedules.get(scheduleName) : null;
    }

    /**
     * Check if a session should currently be active.
     *
     * @param sessionId the session identifier
     * @return true if the session should be connected
     */
    public boolean shouldBeActive(String sessionId) {
        SessionSchedule schedule = getScheduleForSession(sessionId);
        if (schedule == null) {
            return true; // No schedule means always active
        }
        return schedule.shouldBeActive(now());
    }

    /**
     * Get schedule status for a session.
     *
     * @param sessionId the session identifier
     * @return the schedule status, or null if no schedule
     */
    public SessionSchedule.ScheduleStatus getSessionStatus(String sessionId) {
        SessionSchedule schedule = getScheduleForSession(sessionId);
        if (schedule == null) {
            return null;
        }
        return schedule.getStatus(now());
    }

    /**
     * Add a schedule listener.
     *
     * @param listener the listener to add
     */
    public void addListener(ScheduleListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a schedule listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(ScheduleListener listener) {
        listeners.remove(listener);
    }

    /**
     * Start the scheduler.
     */
    public void start() {
        if (running) {
            return;
        }

        running = true;
        checkTask = executor.scheduleAtFixedRate(
                this::checkSchedules,
                0, checkIntervalSeconds, TimeUnit.SECONDS
        );

        log.info("SessionScheduler started (checkInterval={}s, resetTolerance={}min)",
                checkIntervalSeconds, resetToleranceMinutes);
    }

    /**
     * Stop the scheduler.
     */
    @Override
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        if (checkTask != null) {
            checkTask.cancel(false);
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        componentState = ComponentState.STOPPED;
        log.info("SessionScheduler stopped");
    }

    /**
     * Manually trigger a schedule check.
     * This is useful for testing where you want to control time and trigger checks explicitly
     * rather than waiting for the background timer.
     */
    public void triggerCheck() {
        checkSchedules();
    }

    /**
     * Check all session schedules and emit events.
     */
    private void checkSchedules() {
        ZonedDateTime currentTime = now();

        for (Map.Entry<String, String> entry : sessionToSchedule.entrySet()) {
            String sessionId = entry.getKey();
            String scheduleName = entry.getValue();

            try {
                checkSession(sessionId, scheduleName, currentTime);
            } catch (Exception e) {
                log.error("Error checking schedule for session {}: {}", sessionId, e.getMessage(), e);
            }
        }
    }

    /**
     * Check a single session's schedule.
     */
    private void checkSession(String sessionId, String scheduleName, ZonedDateTime now) {
        SessionSchedule schedule = schedules.get(scheduleName);
        if (schedule == null || !schedule.isEnabled()) {
            return;
        }

        SessionState state = sessionStates.get(sessionId);
        if (state == null) {
            state = new SessionState();
            sessionStates.put(sessionId, state);
        }

        boolean isActive = schedule.shouldBeActive(now);

        // Check for session start
        if (isActive && !state.wasActive) {
            ZonedDateTime startTime = schedule.getActiveWindow(now) != null
                    ? now : null;
            emitEvent(ScheduleEvent.sessionStart(sessionId, scheduleName, now, startTime));
            state.wasActive = true;
            state.resetTriggeredToday = false; // Reset the daily flag
        }

        // Check for session end
        if (!isActive && state.wasActive) {
            emitEvent(ScheduleEvent.sessionEnd(sessionId, scheduleName, now, now));
            state.wasActive = false;
        }

        // Check for warnings (only if active)
        if (isActive) {
            checkWarnings(sessionId, scheduleName, schedule, state, now);
        }

        // Check for reset
        if (isActive && !state.resetTriggeredToday) {
            if (schedule.shouldResetNow(now, resetToleranceMinutes)) {
                ZonedDateTime resetTime = schedule.getNextResetTime(now);
                emitEvent(ScheduleEvent.resetDue(sessionId, scheduleName, now, resetTime));
                state.resetTriggeredToday = true;
                state.lastResetDate = now;
            }
        }

        // Reset daily flag at midnight (or when date changes)
        if (state.lastResetDate != null &&
            !now.toLocalDate().equals(state.lastResetDate.toLocalDate())) {
            state.resetTriggeredToday = false;
        }
    }

    /**
     * Check and emit warning events.
     */
    private void checkWarnings(String sessionId, String scheduleName,
                               SessionSchedule schedule, SessionState state, ZonedDateTime now) {
        // Warning before session end
        if (warningMinutesBeforeEnd > 0) {
            ZonedDateTime endTime = schedule.getCurrentWindowEndTime(now);
            if (endTime != null) {
                long minutesUntilEnd = java.time.Duration.between(now, endTime).toMinutes();
                if (minutesUntilEnd > 0 && minutesUntilEnd <= warningMinutesBeforeEnd) {
                    // Only emit once per end time
                    if (state.lastWarningEnd == null || !state.lastWarningEnd.equals(endTime)) {
                        emitEvent(ScheduleEvent.warningSessionEnd(
                                sessionId, scheduleName, now, endTime, minutesUntilEnd));
                        state.lastWarningEnd = endTime;
                    }
                }
            }
        }

        // Warning before reset
        if (warningMinutesBeforeReset > 0 && !state.resetTriggeredToday) {
            ZonedDateTime resetTime = schedule.getNextResetTime(now);
            if (resetTime != null) {
                long minutesUntilReset = java.time.Duration.between(now, resetTime).toMinutes();
                if (minutesUntilReset > 0 && minutesUntilReset <= warningMinutesBeforeReset) {
                    // Only emit once per reset time
                    if (state.lastWarningReset == null || !state.lastWarningReset.equals(resetTime)) {
                        emitEvent(ScheduleEvent.warningReset(
                                sessionId, scheduleName, now, resetTime, minutesUntilReset));
                        state.lastWarningReset = resetTime;
                    }
                }
            }
        }
    }

    /**
     * Emit an event to all listeners.
     */
    private void emitEvent(ScheduleEvent event) {
        log.debug("Schedule event: {}", event);
        for (ScheduleListener listener : listeners) {
            try {
                listener.onScheduleEvent(event);
            } catch (Exception e) {
                log.error("Error in schedule listener: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Get the current time from the clock provider.
     */
    private ZonedDateTime now() {
        return ZonedDateTime.now(clockProvider.getClock());
    }

    // Configuration setters

    public void setCheckIntervalSeconds(long seconds) {
        this.checkIntervalSeconds = seconds;
    }

    public void setResetToleranceMinutes(int minutes) {
        this.resetToleranceMinutes = minutes;
    }

    public void setWarningMinutesBeforeEnd(int minutes) {
        this.warningMinutesBeforeEnd = minutes;
    }

    public void setWarningMinutesBeforeReset(int minutes) {
        this.warningMinutesBeforeReset = minutes;
    }

    // ==================== Component Interface ====================

    @Override
    public void initialize() {
        componentState = ComponentState.INITIALIZED;
        log.info("SessionScheduler initialized");
    }

    @Override
    public void startActive() {
        start();
        componentState = ComponentState.ACTIVE;
    }

    @Override
    public void startStandby() {
        componentState = ComponentState.STANDBY;
        log.info("SessionScheduler in standby mode (not started)");
    }

    @Override
    public void becomeActive() {
        start();
        componentState = ComponentState.ACTIVE;
    }

    @Override
    public void becomeStandby() {
        if (running) {
            if (checkTask != null) {
                checkTask.cancel(false);
            }
            running = false;
        }
        componentState = ComponentState.STANDBY;
    }

    @Override
    public String getName() {
        return "session-scheduler";
    }

    @Override
    public ComponentState getState() {
        return componentState;
    }
}
