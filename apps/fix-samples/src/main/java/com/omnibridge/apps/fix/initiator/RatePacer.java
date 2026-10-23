package com.omnibridge.apps.fix.initiator;

/**
 * Fixed-rate send schedule: order {@code i} is due at {@code start + i * interval}.
 *
 * <p>The schedule never drifts: an order sent late does not push back the ones after it,
 * so latency measured from {@link #intendedSendTime} includes the wait a late send caused
 * (no coordinated omission). Waiting spins instead of sleeping, since a sleep on most
 * operating systems overshoots by tens of microseconds to milliseconds.</p>
 */
final class RatePacer {

    private final long startNanos;
    private final long intervalNanos;

    /**
     * @param startNanos when order 0 is due (System.nanoTime())
     * @param ordersPerSecond the rate; 0 or less sends each order as soon as possible
     */
    RatePacer(long startNanos, int ordersPerSecond) {
        this.startNanos = startNanos;
        this.intervalNanos = ordersPerSecond > 0 ? 1_000_000_000L / ordersPerSecond : 0;
    }

    boolean isPaced() {
        return intervalNanos > 0;
    }

    /**
     * When order {@code index} is due. Unpaced, that is now.
     */
    long intendedSendTime(int index) {
        return intervalNanos > 0 ? startNanos + index * intervalNanos : System.nanoTime();
    }

    /**
     * Spin until order {@code index} is due.
     *
     * @return the intended send time of the order
     */
    long awaitSendTime(int index) {
        long due = intendedSendTime(index);
        while (System.nanoTime() - due < 0) {
            Thread.onSpinWait();
        }
        return due;
    }
}
