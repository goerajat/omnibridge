package com.omnibridge.config.schedule;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Complete schedule definition for a session.
 *
 * <p>A SessionSchedule combines:</p>
 * <ul>
 *   <li>One or more time windows - when the session should be active</li>
 *   <li>A reset schedule - when to perform session reset (e.g., FIX EOD)</li>
 *   <li>A timezone - all times are evaluated in this timezone</li>
 * </ul>
 *
 * <p>Example - US Equity Trading Hours:</p>
 * <pre>{@code
 * SessionSchedule schedule = SessionSchedule.builder()
 *     .name("us-equity")
 *     .timezone(ZoneId.of("America/New_York"))
 *     .addWindow(TimeWindow.builder()
 *         .startTime(9, 30)
 *         .endTime(16, 0)
 *         .weekdays()
 *         .build())
 *     .resetSchedule(ResetSchedule.fixedTime(LocalTime.of(17, 0)))
 *     .build();
 * }</pre>
 *
 * <p>Example - 24/5 FX Session:</p>
 * <pre>{@code
 * SessionSchedule schedule = SessionSchedule.builder()
 *     .name("fx-24x5")
 *     .timezone(ZoneId.of("America/New_York"))
 *     .addWindow(TimeWindow.builder()
 *         .startTime(17, 0)  // Sunday 5 PM
 *         .endTime(17, 0)    // Friday 5 PM
 *         .overnight(true)
 *         .daysOfWeek(EnumSet.of(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY))
 *         .build())
 *     .resetSchedule(ResetSchedule.fixedTime(LocalTime.of(17, 0)))
 *     .build();
 * }</pre>
 */
public class SessionSchedule {

    private final String name;
    private final ZoneId timezone;
    private final List<TimeWindow> windows;
    private final ResetSchedule resetSchedule;
    private final boolean enabled;

    private SessionSchedule(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name is required");
        this.timezone = builder.timezone != null ? builder.timezone : ZoneId.of("UTC");
        this.windows = Collections.unmodifiableList(new ArrayList<>(builder.windows));
        this.resetSchedule = builder.resetSchedule != null
                ? builder.resetSchedule
                : ResetSchedule.none();
        this.enabled = builder.enabled;

        if (windows.isEmpty() && enabled) {
            throw new IllegalArgumentException("At least one time window is required for an enabled schedule");
        }
    }

    /**
     * Check if the session should currently be active.
     *
     * @param now the current time
     * @return true if the session should be connected
     */
    public boolean shouldBeActive(ZonedDateTime now) {
        if (!enabled) {
            return false;
        }

        for (TimeWindow window : windows) {
            if (window.shouldBeActive(now, timezone)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the currently active time window, if any.
     *
     * @param now the current time
     * @return the active window, or null if no window is active
     */
    public TimeWindow getActiveWindow(ZonedDateTime now) {
        if (!enabled) {
            return null;
        }

        for (TimeWindow window : windows) {
            if (window.shouldBeActive(now, timezone)) {
                return window;
            }
        }
        return null;
    }

    /**
     * Get the next time the session should start.
     *
     * @param from the time to search from
     * @return the next start time, or null if no start is scheduled
     */
    public ZonedDateTime getNextStartTime(ZonedDateTime from) {
        if (!enabled || windows.isEmpty()) {
            return null;
        }

        ZonedDateTime earliest = null;
        for (TimeWindow window : windows) {
            ZonedDateTime nextStart = window.getNextStartTime(from, timezone);
            if (nextStart != null && (earliest == null || nextStart.isBefore(earliest))) {
                earliest = nextStart;
            }
        }
        return earliest;
    }

    /**
     * Get the end time for the current session window.
     *
     * @param now the current time (should be within an active window)
     * @return the end time of the current window, or null if not in a window
     */
    public ZonedDateTime getCurrentWindowEndTime(ZonedDateTime now) {
        TimeWindow activeWindow = getActiveWindow(now);
        if (activeWindow == null) {
            return null;
        }

        // Find the start time that led to this active window
        ZonedDateTime zonedNow = now.withZoneSameInstant(timezone);
        ZonedDateTime today = zonedNow.toLocalDate().atStartOfDay(timezone);

        // For overnight windows, the start might be from yesterday
        ZonedDateTime searchFrom = activeWindow.isOvernight()
                ? today.minusDays(1)
                : today;

        ZonedDateTime startTime = activeWindow.getNextStartTime(searchFrom, timezone);
        if (startTime != null && !startTime.isAfter(zonedNow)) {
            return activeWindow.getEndTimeFor(startTime, timezone);
        }

        return null;
    }

    /**
     * Get the next reset time.
     *
     * @param now the current time
     * @return the next reset time, or null if no reset is scheduled
     */
    public ZonedDateTime getNextResetTime(ZonedDateTime now) {
        if (!resetSchedule.isEnabled()) {
            return null;
        }

        ZonedDateTime sessionEndTime = getCurrentWindowEndTime(now);
        return resetSchedule.getNextResetTime(now, sessionEndTime, timezone);
    }

    /**
     * Check if a reset should occur now.
     *
     * @param now the current time
     * @param toleranceMinutes tolerance window for time matching
     * @return true if reset should occur
     */
    public boolean shouldResetNow(ZonedDateTime now, int toleranceMinutes) {
        ZonedDateTime sessionEndTime = getCurrentWindowEndTime(now);
        return resetSchedule.shouldResetNow(now, sessionEndTime, timezone, toleranceMinutes);
    }

    /**
     * Get schedule status information for monitoring.
     *
     * @param now the current time
     * @return status information
     */
    public ScheduleStatus getStatus(ZonedDateTime now) {
        boolean active = shouldBeActive(now);
        TimeWindow activeWindow = getActiveWindow(now);
        ZonedDateTime nextStart = active ? null : getNextStartTime(now);
        ZonedDateTime windowEnd = active ? getCurrentWindowEndTime(now) : null;
        ZonedDateTime nextReset = getNextResetTime(now);

        return new ScheduleStatus(name, enabled, active, activeWindow, nextStart, windowEnd, nextReset);
    }

    // Getters

    public String getName() {
        return name;
    }

    public ZoneId getTimezone() {
        return timezone;
    }

    public List<TimeWindow> getWindows() {
        return windows;
    }

    public ResetSchedule getResetSchedule() {
        return resetSchedule;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "SessionSchedule{" +
                "name='" + name + '\'' +
                ", timezone=" + timezone +
                ", windows=" + windows.size() +
                ", resetSchedule=" + resetSchedule +
                ", enabled=" + enabled +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SessionSchedule that = (SessionSchedule) o;
        return enabled == that.enabled &&
                Objects.equals(name, that.name) &&
                Objects.equals(timezone, that.timezone) &&
                Objects.equals(windows, that.windows) &&
                Objects.equals(resetSchedule, that.resetSchedule);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, timezone, windows, resetSchedule, enabled);
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private ZoneId timezone;
        private final List<TimeWindow> windows = new ArrayList<>();
        private ResetSchedule resetSchedule;
        private boolean enabled = true;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder timezone(ZoneId timezone) {
            this.timezone = timezone;
            return this;
        }

        public Builder timezone(String timezone) {
            this.timezone = ZoneId.of(timezone);
            return this;
        }

        public Builder addWindow(TimeWindow window) {
            this.windows.add(window);
            return this;
        }

        public Builder windows(List<TimeWindow> windows) {
            this.windows.clear();
            this.windows.addAll(windows);
            return this;
        }

        public Builder resetSchedule(ResetSchedule resetSchedule) {
            this.resetSchedule = resetSchedule;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public SessionSchedule build() {
            return new SessionSchedule(this);
        }
    }

    /**
     * Status information for monitoring.
     */
    public record ScheduleStatus(
            String scheduleName,
            boolean enabled,
            boolean active,
            TimeWindow activeWindow,
            ZonedDateTime nextStartTime,
            ZonedDateTime currentWindowEndTime,
            ZonedDateTime nextResetTime
    ) {
        @Override
        public String toString() {
            if (!enabled) {
                return "ScheduleStatus{" + scheduleName + ": DISABLED}";
            }
            if (active) {
                return "ScheduleStatus{" + scheduleName + ": ACTIVE" +
                        ", windowEnd=" + currentWindowEndTime +
                        ", nextReset=" + nextResetTime + "}";
            }
            return "ScheduleStatus{" + scheduleName + ": INACTIVE" +
                    ", nextStart=" + nextStartTime + "}";
        }
    }
}
