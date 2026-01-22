package com.fixengine.config.schedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Defines when a session reset (e.g., FIX EOD) should occur.
 *
 * <p>Reset can be configured in several ways:</p>
 * <ul>
 *   <li>Fixed time - reset at a specific time each day (e.g., 17:00 UTC)</li>
 *   <li>Relative to session end - reset a duration before session ends</li>
 *   <li>Day-specific - different reset times for different days</li>
 * </ul>
 *
 * <p>Examples:</p>
 * <pre>{@code
 * // Fixed daily reset at 5 PM
 * ResetSchedule.fixedTime(LocalTime.of(17, 0));
 *
 * // Reset 5 minutes before session end
 * ResetSchedule.relativeToEnd(Duration.ofMinutes(5));
 *
 * // Different reset for Friday (early close)
 * ResetSchedule.builder()
 *     .fixedTime(LocalTime.of(17, 0))
 *     .daysOfWeek(EnumSet.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY))
 *     .build();
 * }</pre>
 */
public class ResetSchedule {

    /**
     * Type of reset schedule.
     */
    public enum Type {
        /** Reset at a fixed time */
        FIXED_TIME,
        /** Reset relative to session end time */
        RELATIVE_TO_END,
        /** No automatic reset */
        NONE
    }

    private final Type type;
    private final LocalTime fixedTime;
    private final Duration relativeToEnd;
    private final Set<DayOfWeek> daysOfWeek;

    private ResetSchedule(Builder builder) {
        this.type = builder.type;
        this.fixedTime = builder.fixedTime;
        this.relativeToEnd = builder.relativeToEnd;
        this.daysOfWeek = builder.daysOfWeek != null
                ? Collections.unmodifiableSet(EnumSet.copyOf(builder.daysOfWeek))
                : Collections.unmodifiableSet(EnumSet.allOf(DayOfWeek.class));

        // Validation
        if (type == Type.FIXED_TIME && fixedTime == null) {
            throw new IllegalArgumentException("Fixed time reset requires a time");
        }
        if (type == Type.RELATIVE_TO_END && relativeToEnd == null) {
            throw new IllegalArgumentException("Relative reset requires a duration");
        }
    }

    /**
     * Create a fixed-time reset schedule.
     *
     * @param time the time to reset each day
     * @return the reset schedule
     */
    public static ResetSchedule fixedTime(LocalTime time) {
        return builder().fixedTime(time).build();
    }

    /**
     * Create a reset schedule relative to session end.
     *
     * @param beforeEnd duration before the session ends to trigger reset
     * @return the reset schedule
     */
    public static ResetSchedule relativeToEnd(Duration beforeEnd) {
        return builder().relativeToEnd(beforeEnd).build();
    }

    /**
     * Create a reset schedule with no automatic reset.
     *
     * @return a no-reset schedule
     */
    public static ResetSchedule none() {
        return builder().noReset().build();
    }

    /**
     * Calculate the next reset time based on this schedule.
     *
     * @param now current time
     * @param sessionEndTime the session's end time (for relative schedules)
     * @param zone the timezone
     * @return the next reset time, or null if no reset is scheduled
     */
    public ZonedDateTime getNextResetTime(ZonedDateTime now, ZonedDateTime sessionEndTime, ZoneId zone) {
        if (type == Type.NONE) {
            return null;
        }

        ZonedDateTime zonedNow = now.withZoneSameInstant(zone);

        if (type == Type.RELATIVE_TO_END) {
            if (sessionEndTime == null) {
                return null;
            }
            return sessionEndTime.minus(relativeToEnd);
        }

        // Fixed time reset
        return getNextFixedResetTime(zonedNow, zone);
    }

    /**
     * Get the next fixed-time reset.
     */
    private ZonedDateTime getNextFixedResetTime(ZonedDateTime now, ZoneId zone) {
        // Check up to 8 days ahead
        for (int i = 0; i < 8; i++) {
            LocalDate date = now.toLocalDate().plusDays(i);
            DayOfWeek dayOfWeek = date.getDayOfWeek();

            if (daysOfWeek.contains(dayOfWeek)) {
                ZonedDateTime resetTime = ZonedDateTime.of(date, fixedTime, zone);

                // If checking today, make sure reset time is in the future
                if (i == 0 && !resetTime.isAfter(now)) {
                    continue;
                }

                return resetTime;
            }
        }

        return null;
    }

    /**
     * Check if a reset should occur at the given time.
     *
     * @param now current time
     * @param sessionEndTime session end time (for relative schedules)
     * @param zone timezone
     * @param toleranceMinutes tolerance window in minutes for time matching
     * @return true if reset should occur now
     */
    public boolean shouldResetNow(ZonedDateTime now, ZonedDateTime sessionEndTime,
                                   ZoneId zone, int toleranceMinutes) {
        ZonedDateTime resetTime = getNextResetTime(now.minusMinutes(toleranceMinutes), sessionEndTime, zone);

        if (resetTime == null) {
            return false;
        }

        // Check if we're within the tolerance window
        ZonedDateTime zonedNow = now.withZoneSameInstant(zone);
        return !zonedNow.isBefore(resetTime) &&
               zonedNow.isBefore(resetTime.plusMinutes(toleranceMinutes));
    }

    // Getters

    public Type getType() {
        return type;
    }

    public LocalTime getFixedTime() {
        return fixedTime;
    }

    public Duration getRelativeToEnd() {
        return relativeToEnd;
    }

    public Set<DayOfWeek> getDaysOfWeek() {
        return daysOfWeek;
    }

    public boolean isEnabled() {
        return type != Type.NONE;
    }

    @Override
    public String toString() {
        return switch (type) {
            case FIXED_TIME -> "ResetSchedule{fixedTime=" + fixedTime + ", days=" + daysOfWeek + "}";
            case RELATIVE_TO_END -> "ResetSchedule{relativeToEnd=" + relativeToEnd + ", days=" + daysOfWeek + "}";
            case NONE -> "ResetSchedule{NONE}";
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResetSchedule that = (ResetSchedule) o;
        return type == that.type &&
                Objects.equals(fixedTime, that.fixedTime) &&
                Objects.equals(relativeToEnd, that.relativeToEnd) &&
                Objects.equals(daysOfWeek, that.daysOfWeek);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, fixedTime, relativeToEnd, daysOfWeek);
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Type type = Type.NONE;
        private LocalTime fixedTime;
        private Duration relativeToEnd;
        private Set<DayOfWeek> daysOfWeek;

        public Builder fixedTime(LocalTime time) {
            this.type = Type.FIXED_TIME;
            this.fixedTime = time;
            return this;
        }

        public Builder fixedTime(int hour, int minute) {
            return fixedTime(LocalTime.of(hour, minute));
        }

        public Builder relativeToEnd(Duration duration) {
            this.type = Type.RELATIVE_TO_END;
            this.relativeToEnd = duration;
            return this;
        }

        public Builder noReset() {
            this.type = Type.NONE;
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

        public ResetSchedule build() {
            return new ResetSchedule(this);
        }
    }
}
