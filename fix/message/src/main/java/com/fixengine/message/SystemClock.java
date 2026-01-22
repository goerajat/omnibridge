package com.fixengine.message;

/**
 * Default Clock implementation that delegates to System time methods.
 *
 * <p>This is a singleton implementation that provides access to the system's
 * wall clock time and high-resolution timer.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Clock clock = SystemClock.INSTANCE;
 * long currentTime = clock.currentTimeMillis();
 * long nanoTime = clock.nanoTime();
 * }</pre>
 *
 * @see Clock
 */
public final class SystemClock implements Clock {

    /**
     * Singleton instance of the system clock.
     */
    public static final SystemClock INSTANCE = new SystemClock();

    /**
     * Private constructor to enforce singleton pattern.
     */
    private SystemClock() {
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
