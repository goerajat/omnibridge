package com.fixengine.message;

/**
 * Abstraction for wall clock time sources.
 *
 * <p>This interface abstracts System time calls to allow for:</p>
 * <ul>
 *   <li>Testing with controlled/mock time</li>
 *   <li>Alternative clock implementations (hardware timestamps, synchronized clocks)</li>
 *   <li>High-precision timing sources</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Use the default system clock
 * Clock clock = SystemClock.INSTANCE;
 *
 * // Get current time
 * long millis = clock.currentTimeMillis();
 * long nanos = clock.nanoTime();
 * }</pre>
 *
 * @see SystemClock
 */
public interface Clock {

    /**
     * Returns the current time in milliseconds since the Unix epoch (1970-01-01T00:00:00Z).
     *
     * <p>This is equivalent to {@link System#currentTimeMillis()} for the default implementation.</p>
     *
     * @return the current time in milliseconds
     */
    long currentTimeMillis();

    /**
     * Returns the current value of the running JVM's high-resolution time source, in nanoseconds.
     *
     * <p>This is equivalent to {@link System#nanoTime()} for the default implementation.
     * The value returned represents nanoseconds since some fixed but arbitrary origin time.
     * This method is useful for measuring elapsed time and should not be used for wall-clock time.</p>
     *
     * @return the current value of the high-resolution time source, in nanoseconds
     */
    long nanoTime();

    /**
     * Returns the current time in microseconds since the Unix epoch.
     *
     * <p>Default implementation derives this from {@link #currentTimeMillis()} multiplied by 1000.
     * Specialized implementations may provide higher precision.</p>
     *
     * @return the current time in microseconds
     */
    default long currentTimeMicros() {
        return currentTimeMillis() * 1000L;
    }

    /**
     * Returns the current time in nanoseconds since the Unix epoch.
     *
     * <p>Default implementation derives this from {@link #currentTimeMillis()} multiplied by 1,000,000.
     * Specialized implementations may provide higher precision using hardware timestamps.</p>
     *
     * @return the current time in nanoseconds since epoch
     */
    default long currentTimeNanos() {
        return currentTimeMillis() * 1_000_000L;
    }
}
