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
     * Optimized to cache computed values and minimize object allocation.
     */
    private static class SessionState {
        // Persistent state
        boolean wasActive = false;
        boolean resetTriggeredToday = false;
        java.time.LocalDate lastResetDate = null;  // Use LocalDate (cheaper than ZonedDateTime)
        long lastWarningEndEpoch = 0;    // Use epoch seconds instead of ZonedDateTime
        long lastWarningResetEpoch = 0;  // Use epoch seconds instead of ZonedDateTime

        // Cached values for current check cycle (avoid recomputation)
        TimeWindow cachedActiveWindow;
        long cachedEndTimeEpoch;      // Epoch seconds
        long cachedResetTimeEpoch;    // Epoch seconds

        // Next event tracking for skip optimization
        long nextEventEpochSecond = 0;

        void clearCachedValues() {
            cachedActiveWindow = null;
            cachedEndTimeEpoch = 0;
            cachedResetTimeEpoch = 0;
        }
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
     * Optimized to batch sessions by schedule and minimize redundant computations.
     */
    private void checkSchedules() {
        if (sessionToSchedule.isEmpty()) {
            return;
        }

        // Get current time once for entire check cycle
        final ZonedDateTime currentTime = now();
        final long currentEpochSecond = currentTime.toEpochSecond();
        final java.time.LocalDate today = currentTime.toLocalDate();

        // Group sessions by schedule to share computation
        // Use a simple array-based approach to avoid map allocation on hot path
        for (Map.Entry<String, SessionSchedule> scheduleEntry : schedules.entrySet()) {
            String scheduleName = scheduleEntry.getKey();
            SessionSchedule schedule = scheduleEntry.getValue();

            if (!schedule.isEnabled()) {
                continue;
            }

            // Compute active window ONCE per schedule (not per session)
            TimeWindow activeWindow = schedule.getActiveWindow(currentTime);
            boolean isActive = (activeWindow != null);

            // Pre-compute end time and reset time if active (shared across sessions)
            long endTimeEpoch = 0;
            long resetTimeEpoch = 0;
            if (isActive && activeWindow != null) {
                ZonedDateTime endTime = computeEndTimeDirectly(activeWindow, currentTime, schedule.getTimezone());
                endTimeEpoch = endTime != null ? endTime.toEpochSecond() : 0;

                if (schedule.getResetSchedule().isEnabled()) {
                    // Look back by tolerance window to find reset time (matches shouldResetNow behavior)
                    ZonedDateTime lookbackTime = currentTime.minusMinutes(resetToleranceMinutes);
                    ZonedDateTime resetTime = schedule.getResetSchedule()
                            .getNextResetTime(lookbackTime, endTime, schedule.getTimezone());
                    resetTimeEpoch = resetTime != null ? resetTime.toEpochSecond() : 0;
                }
            }

            // Process all sessions using this schedule with pre-computed values
            for (Map.Entry<String, String> sessionEntry : sessionToSchedule.entrySet()) {
                if (!scheduleName.equals(sessionEntry.getValue())) {
                    continue;
                }

                String sessionId = sessionEntry.getKey();
                try {
                    checkSessionOptimized(sessionId, scheduleName, schedule,
                            currentTime, currentEpochSecond, today,
                            isActive, activeWindow, endTimeEpoch, resetTimeEpoch);
                } catch (Exception e) {
                    log.error("Error checking schedule for session {}: {}", sessionId, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Compute end time directly from active window without redundant getActiveWindow calls.
     */
    private ZonedDateTime computeEndTimeDirectly(TimeWindow window, ZonedDateTime now, java.time.ZoneId zone) {
        if (window == null) {
            return null;
        }
        ZonedDateTime zonedNow = now.withZoneSameInstant(zone);
        java.time.LocalDate endDate = zonedNow.toLocalDate();
        if (window.isOvernight()) {
            endDate = endDate.plusDays(1);
        }
        return ZonedDateTime.of(endDate, window.getEndTime(), zone);
    }

    /**
     * Check a single session's schedule (legacy method for backward compatibility).
     */
    private void checkSession(String sessionId, String scheduleName, ZonedDateTime now) {
        SessionSchedule schedule = schedules.get(scheduleName);
        if (schedule == null || !schedule.isEnabled()) {
            return;
        }

        TimeWindow activeWindow = schedule.getActiveWindow(now);
        boolean isActive = (activeWindow != null);
        long currentEpochSecond = now.toEpochSecond();
        java.time.LocalDate today = now.toLocalDate();

        long endTimeEpoch = 0;
        long resetTimeEpoch = 0;
        if (isActive) {
            ZonedDateTime endTime = computeEndTimeDirectly(activeWindow, now, schedule.getTimezone());
            endTimeEpoch = endTime != null ? endTime.toEpochSecond() : 0;
            if (schedule.getResetSchedule().isEnabled()) {
                // Look back by tolerance window to find reset time (matches shouldResetNow behavior)
                ZonedDateTime lookbackTime = now.minusMinutes(resetToleranceMinutes);
                ZonedDateTime resetTime = schedule.getResetSchedule()
                        .getNextResetTime(lookbackTime, endTime, schedule.getTimezone());
                resetTimeEpoch = resetTime != null ? resetTime.toEpochSecond() : 0;
            }
        }

        checkSessionOptimized(sessionId, scheduleName, schedule,
                now, currentEpochSecond, today,
                isActive, activeWindow, endTimeEpoch, resetTimeEpoch);
    }

    /**
     * Optimized session check using pre-computed values.
     * This avoids redundant schedule lookups and time computations.
     */
    private void checkSessionOptimized(String sessionId, String scheduleName,
                                        SessionSchedule schedule,
                                        ZonedDateTime now, long currentEpochSecond,
                                        java.time.LocalDate today,
                                        boolean isActive, TimeWindow activeWindow,
                                        long endTimeEpoch, long resetTimeEpoch) {
        SessionState state = sessionStates.get(sessionId);
        if (state == null) {
            state = new SessionState();
            sessionStates.put(sessionId, state);
        }

        // Cache values for this check cycle
        state.cachedActiveWindow = activeWindow;
        state.cachedEndTimeEpoch = endTimeEpoch;
        state.cachedResetTimeEpoch = resetTimeEpoch;

        // Check for session start
        if (isActive && !state.wasActive) {
            emitEvent(ScheduleEvent.sessionStart(sessionId, scheduleName, now, now));
            state.wasActive = true;
            state.resetTriggeredToday = false;
            // Update next event time
            state.nextEventEpochSecond = Math.min(
                    endTimeEpoch > 0 ? endTimeEpoch : Long.MAX_VALUE,
                    resetTimeEpoch > 0 ? resetTimeEpoch : Long.MAX_VALUE);
        }

        // Check for session end
        if (!isActive && state.wasActive) {
            emitEvent(ScheduleEvent.sessionEnd(sessionId, scheduleName, now, now));
            state.wasActive = false;
            // Compute next start time for skip optimization
            ZonedDateTime nextStart = schedule.getNextStartTime(now);
            state.nextEventEpochSecond = nextStart != null ? nextStart.toEpochSecond() : Long.MAX_VALUE;
        }

        // Check for warnings and reset (only if active)
        if (isActive) {
            checkWarningsOptimized(sessionId, scheduleName, state, now, currentEpochSecond,
                    endTimeEpoch, resetTimeEpoch);

            // Check for reset using epoch comparison (faster than Duration)
            // Original logic: fire when resetTime <= now < resetTime + tolerance
            if (!state.resetTriggeredToday && resetTimeEpoch > 0) {
                long secondsSinceReset = currentEpochSecond - resetTimeEpoch;
                // Within tolerance window: at or after reset time, but before tolerance expires
                if (secondsSinceReset >= 0 && secondsSinceReset < resetToleranceMinutes * 60L) {
                    ZonedDateTime resetTime = ZonedDateTime.ofInstant(
                            java.time.Instant.ofEpochSecond(resetTimeEpoch),
                            schedule.getTimezone());
                    emitEvent(ScheduleEvent.resetDue(sessionId, scheduleName, now, resetTime));
                    state.resetTriggeredToday = true;
                    state.lastResetDate = today;
                }
            }
        }

        // Reset daily flag at midnight (use LocalDate comparison - no object allocation)
        if (state.lastResetDate != null && !today.equals(state.lastResetDate)) {
            state.resetTriggeredToday = false;
        }
    }

    /**
     * Check and emit warning events using epoch-based comparisons.
     * Uses pre-computed end/reset times to avoid redundant calculations.
     */
    private void checkWarningsOptimized(String sessionId, String scheduleName,
                                         SessionState state, ZonedDateTime now,
                                         long currentEpochSecond,
                                         long endTimeEpoch, long resetTimeEpoch) {
        // Warning before session end (using epoch seconds - no Duration allocation)
        if (warningMinutesBeforeEnd > 0 && endTimeEpoch > 0) {
            long secondsUntilEnd = endTimeEpoch - currentEpochSecond;
            long minutesUntilEnd = secondsUntilEnd / 60;

            if (minutesUntilEnd > 0 && minutesUntilEnd <= warningMinutesBeforeEnd) {
                // Only emit once per end time (compare epoch instead of ZonedDateTime)
                if (state.lastWarningEndEpoch != endTimeEpoch) {
                    ZonedDateTime endTime = ZonedDateTime.ofInstant(
                            java.time.Instant.ofEpochSecond(endTimeEpoch),
                            now.getZone());
                    emitEvent(ScheduleEvent.warningSessionEnd(
                            sessionId, scheduleName, now, endTime, minutesUntilEnd));
                    state.lastWarningEndEpoch = endTimeEpoch;
                }
            }
        }

        // Warning before reset (using epoch seconds)
        if (warningMinutesBeforeReset > 0 && !state.resetTriggeredToday && resetTimeEpoch > 0) {
            long secondsUntilReset = resetTimeEpoch - currentEpochSecond;
            long minutesUntilReset = secondsUntilReset / 60;

            if (minutesUntilReset > 0 && minutesUntilReset <= warningMinutesBeforeReset) {
                // Only emit once per reset time
                if (state.lastWarningResetEpoch != resetTimeEpoch) {
                    ZonedDateTime resetTime = ZonedDateTime.ofInstant(
                            java.time.Instant.ofEpochSecond(resetTimeEpoch),
                            now.getZone());
                    emitEvent(ScheduleEvent.warningReset(
                            sessionId, scheduleName, now, resetTime, minutesUntilReset));
                    state.lastWarningResetEpoch = resetTimeEpoch;
                }
            }
        }
    }

    /**
     * Check and emit warning events (legacy method).
     * @deprecated Use checkWarningsOptimized for better performance
     */
    @SuppressWarnings("unused")
    private void checkWarnings(String sessionId, String scheduleName,
                               SessionSchedule schedule, SessionState state, ZonedDateTime now) {
        long currentEpochSecond = now.toEpochSecond();
        ZonedDateTime endTime = schedule.getCurrentWindowEndTime(now);
        long endTimeEpoch = endTime != null ? endTime.toEpochSecond() : 0;
        ZonedDateTime resetTime = schedule.getNextResetTime(now);
        long resetTimeEpoch = resetTime != null ? resetTime.toEpochSecond() : 0;

        checkWarningsOptimized(sessionId, scheduleName, state, now, currentEpochSecond,
                endTimeEpoch, resetTimeEpoch);
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
