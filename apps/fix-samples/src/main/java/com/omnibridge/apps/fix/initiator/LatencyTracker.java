package com.omnibridge.apps.fix.initiator;

import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;

/**
 * Interface for tracking round-trip latency in FIX message exchanges.
 * Orders are identified by their index in the run; the ClOrdID carries the index as a
 * numeric suffix (e.g. TEST123), so responses are matched without a map or a String.
 */
public interface LatencyTracker {

    /**
     * Record that order {@code index} was sent.
     *
     * @param index the order index (the numeric suffix of its ClOrdID)
     * @param intendedSendNanos when the rate schedule meant the order to go (System.nanoTime())
     * @param actualSendNanos when it was actually handed to the session (System.nanoTime())
     */
    void recordSend(int index, long intendedSendNanos, long actualSendNanos);

    /**
     * Record the response to an order. Unknown or repeated ClOrdIDs are ignored.
     *
     * @param clOrdId the ClOrdID of the response
     * @param receiveTimeNanos the receive timestamp (System.nanoTime())
     */
    void recordResponse(CharSequence clOrdId, long receiveTimeNanos);

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
     * Get the recorded latencies (from the actual send time) in arrival order.
     *
     * @return array of latencies in nanoseconds
     */
    long[] getLatencies();

    /**
     * Round trips measured from the intended send time (corrected for coordinated omission).
     */
    Histogram getIntendedHistogram();

    /**
     * Round trips measured from the actual send time.
     */
    Histogram getActualHistogram();

    /**
     * How late each order went out relative to its intended send time.
     */
    Histogram getSendSlipHistogram();

    /**
     * Reset the tracker for a new test run.
     *
     * @param expectedCount the expected number of orders for the new run
     * @param clOrdIdPrefix the ClOrdID prefix of this run's orders; late responses from an
     *                      earlier run (another prefix) are ignored
     */
    void reset(int expectedCount, String clOrdIdPrefix);
}
