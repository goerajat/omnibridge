package com.omnibridge.config.testing;

import com.omnibridge.config.ClockProvider;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A controllable clock provider for testing that allows time to be advanced programmatically.
 *
 * <p>This class extends {@link ClockProvider} and uses a {@link ControllableClock} internally,
 * allowing time to be advanced for testing schedule-based functionality.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Create provider starting at 9:00 AM NY time on a Monday
 * ControllableClockProvider clockProvider = ControllableClockProvider.create(
 *     Instant.parse("2024-01-15T14:00:00Z"),  // 9 AM in NY (UTC-5)
 *     ZoneId.of("America/New_York")
 * );
 *
 * // Create scheduler with controllable time
 * SessionScheduler scheduler = new SessionScheduler(clockProvider);
 *
 * // Advance time to trigger schedule events
 * clockProvider.advanceMinutes(30);  // Now 9:30 AM - market opens!
 * }</pre>
 */
public class ControllableClockProvider extends ClockProvider {

    private final ControllableClock controllableClock;

    private ControllableClockProvider(ControllableClock clock) {
        super(clock, System::nanoTime);
        this.controllableClock = clock;
    }

    /**
     * Create a new ControllableClockProvider at the specified instant and zone.
     *
     * @param initialInstant the starting instant
     * @param zone the time zone
     * @return a new ControllableClockProvider
     */
    public static ControllableClockProvider create(Instant initialInstant, ZoneId zone) {
        return new ControllableClockProvider(ControllableClock.create(initialInstant, zone));
    }

    /**
     * Create a new ControllableClockProvider at the specified instant in UTC.
     *
     * @param initialInstant the starting instant
     * @return a new ControllableClockProvider in UTC
     */
    public static ControllableClockProvider createUtc(Instant initialInstant) {
        return create(initialInstant, ZoneId.of("UTC"));
    }

    /**
     * Create a new ControllableClockProvider from an existing ControllableClock.
     *
     * @param clock the controllable clock to wrap
     * @return a new ControllableClockProvider
     */
    public static ControllableClockProvider from(ControllableClock clock) {
        return new ControllableClockProvider(clock);
    }

    /**
     * Advance the clock by the specified duration.
     *
     * @param duration the amount of time to advance
     * @return this provider for chaining
     */
    public ControllableClockProvider advance(Duration duration) {
        controllableClock.advance(duration);
        return this;
    }

    /**
     * Advance the clock by the specified number of seconds.
     *
     * @param seconds the number of seconds to advance
     * @return this provider for chaining
     */
    public ControllableClockProvider advanceSeconds(long seconds) {
        controllableClock.advanceSeconds(seconds);
        return this;
    }

    /**
     * Advance the clock by the specified number of minutes.
     *
     * @param minutes the number of minutes to advance
     * @return this provider for chaining
     */
    public ControllableClockProvider advanceMinutes(long minutes) {
        controllableClock.advanceMinutes(minutes);
        return this;
    }

    /**
     * Advance the clock by the specified number of hours.
     *
     * @param hours the number of hours to advance
     * @return this provider for chaining
     */
    public ControllableClockProvider advanceHours(long hours) {
        controllableClock.advanceHours(hours);
        return this;
    }

    /**
     * Advance the clock by the specified number of days.
     *
     * @param days the number of days to advance
     * @return this provider for chaining
     */
    public ControllableClockProvider advanceDays(long days) {
        controllableClock.advanceDays(days);
        return this;
    }

    /**
     * Set the clock to a specific instant.
     *
     * @param instant the instant to set
     * @return this provider for chaining
     */
    public ControllableClockProvider setInstant(Instant instant) {
        controllableClock.setInstant(instant);
        return this;
    }

    /**
     * Get the underlying ControllableClock.
     *
     * @return the controllable clock
     */
    public ControllableClock getControllableClock() {
        return controllableClock;
    }

    @Override
    public String getName() {
        return "controllable-clock-provider";
    }

    @Override
    public String toString() {
        return "ControllableClockProvider{clock=" + controllableClock + "}";
    }
}
