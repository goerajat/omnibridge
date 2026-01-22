package com.fixengine.samples.initiator;

import java.util.concurrent.TimeUnit;

/**
 * Interface for tracking round-trip latency in FIX message exchanges.
 * Implementations record send and response times for orders identified by ClOrdID.
 */
public interface LatencyTracker {

    /**
     * Record the time an order was sent.
     *
     * @param clOrdId the client order ID
     * @param sendTimeNanos the send timestamp in nanoseconds (from System.nanoTime())
     */
    void recordSendTime(String clOrdId, long sendTimeNanos);

    /**
     * Record the time a response was received for an order.
     * This calculates and stores the round-trip latency.
     *
     * @param clOrdId the client order ID
     * @param receiveTimeNanos the receive timestamp in nanoseconds (from System.nanoTime())
     */
    void recordResponseTime(String clOrdId, long receiveTimeNanos);

    /**
     * Get the current number of orders pending response.
     *
     * @return the count of orders sent but not yet acknowledged
     */
    int getPendingOrderCount();

    /**
     * Get the number of latencies recorded so far.
     *
     * @return the count of completed round-trips
     */
    int getRecordedCount();

    /**
     * Wait for all pending orders to receive responses.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return true if all responses received, false if timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    boolean await(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Get the recorded latencies array.
     *
     * @return array of latencies in nanoseconds
     */
    long[] getLatencies();

    /**
     * Reset the tracker for a new test run.
     *
     * @param expectedCount the expected number of orders for the new run
     */
    void reset(int expectedCount);
}
