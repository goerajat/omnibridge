package com.omnibridge.config.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A controllable clock for testing that allows time to be advanced programmatically.
 *
 * <p>This clock starts at a specified instant and can be advanced using
 * {@link #advance(Duration)} or set directly using {@link #setInstant(Instant)}.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Create a controllable clock starting at a specific time
 * ControllableClock clock = ControllableClock.create(
 *     Instant.parse("2024-01-15T09:00:00Z"),
 *     ZoneId.of("America/New_York")
 * );
 *
 * // Use in tests
 * assertEquals(Instant.parse("2024-01-15T09:00:00Z"), clock.instant());
 *
 * // Advance time
 * clock.advance(Duration.ofMinutes(30));
 * assertEquals(Instant.parse("2024-01-15T09:30:00Z"), clock.instant());
 *
 * // Set to a specific time
 * clock.setInstant(Instant.parse("2024-01-15T16:00:00Z"));
 * }</pre>
 */
public class ControllableClock extends Clock {

    private final AtomicReference<Instant> currentInstant;
    private final ZoneId zone;

    private ControllableClock(Instant initialInstant, ZoneId zone) {
        this.currentInstant = new AtomicReference<>(initialInstant);
        this.zone = zone;
    }

    /**
     * Create a new ControllableClock at the specified instant and zone.
     *
     * @param initialInstant the starting instant
     * @param zone the time zone
     * @return a new ControllableClock
     */
    public static ControllableClock create(Instant initialInstant, ZoneId zone) {
        return new ControllableClock(initialInstant, zone);
    }

    /**
     * Create a new ControllableClock at the specified instant in UTC.
     *
     * @param initialInstant the starting instant
     * @return a new ControllableClock in UTC
     */
    public static ControllableClock createUtc(Instant initialInstant) {
        return new ControllableClock(initialInstant, ZoneId.of("UTC"));
    }

    /**
     * Create a new ControllableClock at the current system time.
     *
     * @param zone the time zone
     * @return a new ControllableClock starting at current time
     */
    public static ControllableClock createAtCurrentTime(ZoneId zone) {
        return new ControllableClock(Instant.now(), zone);
    }

    /**
     * Advance the clock by the specified duration.
     *
     * @param duration the amount of time to advance
     * @return this clock for chaining
     */
    public ControllableClock advance(Duration duration) {
        currentInstant.updateAndGet(instant -> instant.plus(duration));
        return this;
    }

    /**
     * Advance the clock by the specified number of seconds.
     *
     * @param seconds the number of seconds to advance
     * @return this clock for chaining
     */
    public ControllableClock advanceSeconds(long seconds) {
        return advance(Duration.ofSeconds(seconds));
    }

    /**
     * Advance the clock by the specified number of minutes.
     *
     * @param minutes the number of minutes to advance
     * @return this clock for chaining
     */
    public ControllableClock advanceMinutes(long minutes) {
        return advance(Duration.ofMinutes(minutes));
    }

    /**
     * Advance the clock by the specified number of hours.
     *
     * @param hours the number of hours to advance
     * @return this clock for chaining
     */
    public ControllableClock advanceHours(long hours) {
        return advance(Duration.ofHours(hours));
    }

    /**
     * Advance the clock by the specified number of days.
     *
     * @param days the number of days to advance
     * @return this clock for chaining
     */
    public ControllableClock advanceDays(long days) {
        return advance(Duration.ofDays(days));
    }

    /**
     * Set the clock to a specific instant.
     *
     * @param instant the instant to set
     * @return this clock for chaining
     */
    public ControllableClock setInstant(Instant instant) {
        currentInstant.set(instant);
        return this;
    }

    /**
     * Reset the clock to the specified instant.
     *
     * @param instant the instant to reset to
     * @return this clock for chaining
     */
    public ControllableClock reset(Instant instant) {
        return setInstant(instant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ControllableClock(currentInstant.get(), zone);
    }

    @Override
    public Instant instant() {
        return currentInstant.get();
    }

    @Override
    public long millis() {
        return currentInstant.get().toEpochMilli();
    }

    @Override
    public String toString() {
        return "ControllableClock{instant=" + currentInstant.get() + ", zone=" + zone + "}";
    }
}
