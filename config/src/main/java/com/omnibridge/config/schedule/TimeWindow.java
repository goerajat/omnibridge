package com.omnibridge.config.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a time window during which a session should be active.
 *
 * <p>A TimeWindow defines:</p>
 * <ul>
 *   <li>Start time - when the session should connect</li>
 *   <li>End time - when the session should disconnect</li>
 *   <li>Days of week - which days the window applies (optional, defaults to all days)</li>
 *   <li>Overnight flag - whether the window spans midnight</li>
 * </ul>
 *
 * <p>Examples:</p>
 * <pre>{@code
 * // Regular trading hours (Mon-Fri, 9:30 AM - 4:00 PM)
 * TimeWindow.builder()
 *     .startTime(LocalTime.of(9, 30))
 *     .endTime(LocalTime.of(16, 0))
 *     .daysOfWeek(EnumSet.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY))
 *     .build();
 *
 * // Overnight FX session (Sun 5PM - Fri 5PM)
 * TimeWindow.builder()
 *     .startTime(LocalTime.of(17, 0))
 *     .endTime(LocalTime.of(17, 0))
 *     .overnight(true)
 *     .daysOfWeek(EnumSet.of(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY))
 *     .build();
 * }</pre>
 */
public class TimeWindow {

    private final LocalTime startTime;
    private final LocalTime endTime;
    private final Set<DayOfWeek> daysOfWeek;
    private final boolean overnight;

    private TimeWindow(Builder builder) {
        this.startTime = Objects.requireNonNull(builder.startTime, "startTime is required");
        this.endTime = Objects.requireNonNull(builder.endTime, "endTime is required");
        this.daysOfWeek = builder.daysOfWeek != null
                ? Collections.unmodifiableSet(EnumSet.copyOf(builder.daysOfWeek))
                : Collections.unmodifiableSet(EnumSet.allOf(DayOfWeek.class));
        this.overnight = builder.overnight;

        // Auto-detect overnight if start > end and not explicitly set
        if (!builder.overnightExplicitlySet && startTime.isAfter(endTime)) {
            throw new IllegalArgumentException(
                    "Start time " + startTime + " is after end time " + endTime +
                    ". Set overnight=true for sessions spanning midnight.");
        }
    }

    /**
     * Check if the given time falls within this window.
     *
     * @param dateTime the date/time to check (should be in the window's timezone)
     * @return true if the time is within the window
     */
    public boolean isWithinWindow(ZonedDateTime dateTime) {
        LocalTime time = dateTime.toLocalTime();
        DayOfWeek dayOfWeek = dateTime.getDayOfWeek();

        if (overnight) {
            return isWithinOvernightWindow(dateTime);
        }

        // Regular window: check if day matches and time is within range
        if (!daysOfWeek.contains(dayOfWeek)) {
            return false;
        }

        // Start <= time < end
        return !time.isBefore(startTime) && time.isBefore(endTime);
    }

    /**
     * Check if the given time falls within an overnight window.
     *
     * <p>For overnight windows, the session starts on one day and ends on the next.
     * The daysOfWeek set indicates the START days of the window.</p>
     */
    private boolean isWithinOvernightWindow(ZonedDateTime dateTime) {
        LocalTime time = dateTime.toLocalTime();
        DayOfWeek dayOfWeek = dateTime.getDayOfWeek();

        // Check if we're in the "evening" portion (after start time on a start day)
        if (daysOfWeek.contains(dayOfWeek) && !time.isBefore(startTime)) {
            return true;
        }

        // Check if we're in the "morning" portion (before end time, day after a start day)
        DayOfWeek previousDay = dayOfWeek.minus(1);
        if (daysOfWeek.contains(previousDay) && time.isBefore(endTime)) {
            return true;
        }

        return false;
    }

    /**
     * Get the next start time on or after the given date/time.
     *
     * @param from the date/time to search from
     * @param zone the timezone for the schedule
     * @return the next start time, or null if no valid start time exists
     */
    public ZonedDateTime getNextStartTime(ZonedDateTime from, ZoneId zone) {
        ZonedDateTime candidate = from.withZoneSameInstant(zone);

        // Check up to 8 days ahead (to handle weekly schedules)
        for (int i = 0; i < 8; i++) {
            LocalDate date = candidate.toLocalDate().plusDays(i);
            DayOfWeek dayOfWeek = date.getDayOfWeek();

            if (daysOfWeek.contains(dayOfWeek)) {
                ZonedDateTime startDateTime = ZonedDateTime.of(date, startTime, zone);

                // If checking today, make sure start time is in the future
                if (i == 0 && !startDateTime.isAfter(from)) {
                    continue;
                }

                return startDateTime;
            }
        }

        return null;
    }

    /**
     * Get the next end time after the given start time.
     *
     * @param startDateTime the start time of the session
     * @param zone the timezone for the schedule
     * @return the end time for this session window
     */
    public ZonedDateTime getEndTimeFor(ZonedDateTime startDateTime, ZoneId zone) {
        LocalDate endDate = startDateTime.toLocalDate();

        if (overnight) {
            // End time is on the next day
            endDate = endDate.plusDays(1);
        }

        return ZonedDateTime.of(endDate, endTime, zone);
    }

    /**
     * Check if this window is currently in the "should be active" state.
     *
     * @param now the current time
     * @param zone the timezone for evaluation
     * @return true if a session with this window should be connected
     */
    public boolean shouldBeActive(ZonedDateTime now, ZoneId zone) {
        ZonedDateTime zonedNow = now.withZoneSameInstant(zone);
        return isWithinWindow(zonedNow);
    }

    // Getters

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public Set<DayOfWeek> getDaysOfWeek() {
        return daysOfWeek;
    }

    public boolean isOvernight() {
        return overnight;
    }

    @Override
    public String toString() {
        return "TimeWindow{" +
                "startTime=" + startTime +
                ", endTime=" + endTime +
                ", daysOfWeek=" + daysOfWeek +
                ", overnight=" + overnight +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeWindow that = (TimeWindow) o;
        return overnight == that.overnight &&
                Objects.equals(startTime, that.startTime) &&
                Objects.equals(endTime, that.endTime) &&
                Objects.equals(daysOfWeek, that.daysOfWeek);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startTime, endTime, daysOfWeek, overnight);
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private LocalTime startTime;
        private LocalTime endTime;
        private Set<DayOfWeek> daysOfWeek;
        private boolean overnight = false;
        private boolean overnightExplicitlySet = false;

        public Builder startTime(LocalTime startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder startTime(int hour, int minute) {
            this.startTime = LocalTime.of(hour, minute);
            return this;
        }

        public Builder endTime(LocalTime endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder endTime(int hour, int minute) {
            this.endTime = LocalTime.of(hour, minute);
            return this;
        }

        public Builder daysOfWeek(Set<DayOfWeek> daysOfWeek) {
            this.daysOfWeek = daysOfWeek;
            return this;
        }

        public Builder weekdays() {
            this.daysOfWeek = EnumSet.of(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
            );
            return this;
        }

        public Builder allDays() {
            this.daysOfWeek = EnumSet.allOf(DayOfWeek.class);
            return this;
        }

        public Builder overnight(boolean overnight) {
            this.overnight = overnight;
            this.overnightExplicitlySet = true;
            return this;
        }

        public TimeWindow build() {
            return new TimeWindow(this);
        }
    }
}
