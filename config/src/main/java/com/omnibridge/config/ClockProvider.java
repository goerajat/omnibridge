package com.omnibridge.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Provider for time sources used throughout the FIX engine.
 *
 * <p>This component wraps a {@link java.time.Clock} for wall-clock time and provides
 * additional methods for high-resolution timing. Using this abstraction allows for:</p>
 * <ul>
 *   <li>Testing with controlled/mock time using {@link Clock#fixed(Instant, ZoneId)}</li>
 *   <li>Alternative clock implementations (hardware timestamps, synchronized clocks)</li>
 *   <li>High-precision timing sources</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Production - use system clock
 * ClockProvider clockProvider = ClockProvider.system();
 *
 * // Testing - use fixed clock
 * Instant fixedTime = Instant.parse("2024-01-15T10:30:00Z");
 * ClockProvider testClock = ClockProvider.fixed(fixedTime, ZoneId.of("UTC"));
 *
 * // Get current time
 * long millis = clockProvider.currentTimeMillis();
 * Instant instant = clockProvider.instant();
 * }</pre>
 *
 * @see java.time.Clock
 */
@Singleton
public class ClockProvider implements Component {

    private final Clock clock;
    private final NanoTimeSource nanoTimeSource;

    /**
     * Functional interface for providing high-resolution elapsed time.
     */
    @FunctionalInterface
    public interface NanoTimeSource {
        /**
         * Returns the current value of the running JVM's high-resolution time source.
         * @return nanoseconds since some fixed but arbitrary origin time
         */
        long nanoTime();
    }

    /**
     * Create a ClockProvider with the given clock and nano time source.
     *
     * @param clock the java.time.Clock for wall-clock time
     * @param nanoTimeSource the source for high-resolution elapsed time
     */
    public ClockProvider(Clock clock, NanoTimeSource nanoTimeSource) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock cannot be null");
        }
        if (nanoTimeSource == null) {
            throw new IllegalArgumentException("NanoTimeSource cannot be null");
        }
        this.clock = clock;
        this.nanoTimeSource = nanoTimeSource;
    }

    /**
     * Create a ClockProvider with the given clock using System.nanoTime() for elapsed time.
     *
     * @param clock the java.time.Clock for wall-clock time
     */
    public ClockProvider(Clock clock) {
        this(clock, System::nanoTime);
    }

    /**
     * Create a system ClockProvider that uses the system clock and System.nanoTime().
     *
     * @return a ClockProvider using system time sources
     */
    public static ClockProvider system() {
        return new ClockProvider(Clock.systemUTC(), System::nanoTime);
    }

    /**
     * Create a fixed ClockProvider for testing that always returns the same instant.
     *
     * <p>Note: The nanoTime() method will still use System.nanoTime() unless a custom
     * NanoTimeSource is provided. For fully deterministic tests, use
     * {@link #fixed(Instant, ZoneId, NanoTimeSource)}.</p>
     *
     * @param fixedInstant the instant to return
     * @param zone the time zone
     * @return a ClockProvider fixed at the given instant
     */
    public static ClockProvider fixed(Instant fixedInstant, ZoneId zone) {
        return new ClockProvider(Clock.fixed(fixedInstant, zone), System::nanoTime);
    }

    /**
     * Create a fixed ClockProvider for testing with a custom nano time source.
     *
     * @param fixedInstant the instant to return
     * @param zone the time zone
     * @param nanoTimeSource the source for nanoTime() values
     * @return a ClockProvider fixed at the given instant with custom nano time
     */
    public static ClockProvider fixed(Instant fixedInstant, ZoneId zone, NanoTimeSource nanoTimeSource) {
        return new ClockProvider(Clock.fixed(fixedInstant, zone), nanoTimeSource);
    }

    /**
     * Get the underlying java.time.Clock.
     *
     * @return the clock instance
     */
    public Clock getClock() {
        return clock;
    }

    /**
     * Get the current instant from this clock.
     *
     * @return the current instant
     */
    public Instant instant() {
        return clock.instant();
    }

    /**
     * Get the current time in milliseconds since the Unix epoch.
     *
     * @return current time in milliseconds
     */
    public long currentTimeMillis() {
        return clock.millis();
    }

    /**
     * Get the current value of the high-resolution time source, in nanoseconds.
     *
     * <p>This value represents nanoseconds since some fixed but arbitrary origin time.
     * It is useful for measuring elapsed time but should not be used for wall-clock time.</p>
     *
     * @return the current high-resolution time in nanoseconds
     */
    public long nanoTime() {
        return nanoTimeSource.nanoTime();
    }

    /**
     * Get the current time in microseconds since the Unix epoch.
     *
     * <p>Derived from {@link #currentTimeMillis()} multiplied by 1000.</p>
     *
     * @return the current time in microseconds
     */
    public long currentTimeMicros() {
        return currentTimeMillis() * 1000L;
    }

    /**
     * Get the current time in nanoseconds since the Unix epoch.
     *
     * <p>Uses the Instant for better precision when available.</p>
     *
     * @return the current time in nanoseconds since epoch
     */
    public long currentTimeNanos() {
        Instant instant = instant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    /**
     * Get the time zone of this clock.
     *
     * @return the time zone
     */
    public ZoneId getZone() {
        return clock.getZone();
    }

    /**
     * Create a ClockProvider with a different time zone.
     *
     * @param zone the new time zone
     * @return a new ClockProvider with the specified zone
     */
    public ClockProvider withZone(ZoneId zone) {
        return new ClockProvider(clock.withZone(zone), nanoTimeSource);
    }

    // ==================== Component Interface ====================

    @Override
    public void initialize() {
        // No initialization needed
    }

    @Override
    public void startActive() {
        // No-op for clock provider
    }

    @Override
    public void startStandby() {
        // No-op for clock provider
    }

    @Override
    public void becomeActive() {
        // No-op for clock provider
    }

    @Override
    public void becomeStandby() {
        // No-op for clock provider
    }

    @Override
    public void stop() {
        // No cleanup needed for clock provider
    }

    @Override
    public String getName() {
        return "clock-provider";
    }

    @Override
    public ComponentState getState() {
        return ComponentState.ACTIVE;
    }

    @Override
    public String toString() {
        return "ClockProvider{clock=" + clock + "}";
    }
}
