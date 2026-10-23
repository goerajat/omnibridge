package com.omnibridge.apps.fix.initiator;

import org.HdrHistogram.Histogram;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Array-based implementation of LatencyTracker.
 *
 * <p>Send times are kept in arrays indexed by order number, which the ClOrdID carries as a
 * numeric suffix, and round trips are recorded into HdrHistograms, so measurement allocates
 * nothing. {@link #recordSend} runs on the sending thread and {@link #recordResponse} on the
 * event loop; read the results only after the run has finished.</p>
 */
public class ArrayLatencyTracker implements LatencyTracker {

    /** Round trips above this are recorded as this value (one minute). */
    static final long HIGHEST_TRACKABLE_NANOS = TimeUnit.MINUTES.toNanos(1);

    private static final byte SENT = 1;
    private static final byte RESPONDED = 2;

    private final int capacity;
    private final long[] intendedSendTimes;
    private final long[] actualSendTimes;
    private final byte[] states;
    private final long[] latencies;
    private final Histogram intendedHistogram = newHistogram();
    private final Histogram actualHistogram = newHistogram();
    private final Histogram sendSlipHistogram = newHistogram();
    // Highest sent index + 1; its release/acquire publishes the send times to the event loop
    private final AtomicInteger publishedSends = new AtomicInteger(0);
    private final AtomicInteger latencyIndex = new AtomicInteger(0);
    private final AtomicInteger pendingOrders = new AtomicInteger(0);
    private volatile CountDownLatch completionLatch;
    private volatile String prefix = "";

    /**
     * Create a new ArrayLatencyTracker with the specified capacity.
     *
     * @param capacity the maximum number of orders per run
     */
    public ArrayLatencyTracker(int capacity) {
        this.capacity = capacity;
        this.intendedSendTimes = new long[capacity];
        this.actualSendTimes = new long[capacity];
        this.states = new byte[capacity];
        this.latencies = new long[capacity];
        this.completionLatch = new CountDownLatch(0);
    }

    static Histogram newHistogram() {
        return new Histogram(HIGHEST_TRACKABLE_NANOS, 3);
    }

    @Override
    public void recordSend(int index, long intendedSendNanos, long actualSendNanos) {
        if (index < 0 || index >= capacity) {
            return;
        }
        intendedSendTimes[index] = intendedSendNanos;
        actualSendTimes[index] = actualSendNanos;
        states[index] = SENT;
        sendSlipHistogram.recordValue(clamp(actualSendNanos - intendedSendNanos));
        pendingOrders.incrementAndGet();
        if (index >= publishedSends.get()) {
            publishedSends.set(index + 1);
        }
    }

    @Override
    public void recordResponse(CharSequence clOrdId, long receiveTimeNanos) {
        int index = parseIndex(clOrdId);
        if (index < 0 || !hasPrefix(clOrdId) || index >= publishedSends.get() || states[index] != SENT) {
            return;
        }
        states[index] = RESPONDED;

        long latencyNanos = receiveTimeNanos - actualSendTimes[index];
        actualHistogram.recordValue(clamp(latencyNanos));
        intendedHistogram.recordValue(clamp(receiveTimeNanos - intendedSendTimes[index]));

        int idx = latencyIndex.getAndIncrement();
        if (idx < latencies.length) {
            latencies[idx] = latencyNanos;
        }
        pendingOrders.decrementAndGet();
        CountDownLatch latch = this.completionLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * Parse the numeric suffix of a ClOrdID such as {@code TEST123}.
     *
     * @return the index, or -1 if there is no suffix or it does not fit in an int
     */
    static int parseIndex(CharSequence clOrdId) {
        if (clOrdId == null) {
            return -1;
        }
        int end = clOrdId.length();
        int start = end;
        while (start > 0 && isDigit(clOrdId.charAt(start - 1))) {
            start--;
        }
        if (start == end || end - start > 9) {
            return -1;
        }
        int value = 0;
        for (int i = start; i < end; i++) {
            value = value * 10 + (clOrdId.charAt(i) - '0');
        }
        return value;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean hasPrefix(CharSequence clOrdId) {
        String p = prefix;
        if (clOrdId.length() < p.length()) {
            return false;
        }
        for (int i = 0; i < p.length(); i++) {
            if (clOrdId.charAt(i) != p.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static long clamp(long nanos) {
        return Math.max(0, Math.min(nanos, HIGHEST_TRACKABLE_NANOS));
    }

    @Override
    public int getPendingOrderCount() {
        return pendingOrders.get();
    }

    @Override
    public int getRecordedCount() {
        return latencyIndex.get();
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch latch = this.completionLatch;
        if (latch != null) {
            return latch.await(timeout, unit);
        }
        return true;
    }

    @Override
    public long[] getLatencies() {
        return latencies;
    }

    @Override
    public Histogram getIntendedHistogram() {
        return intendedHistogram;
    }

    @Override
    public Histogram getActualHistogram() {
        return actualHistogram;
    }

    @Override
    public Histogram getSendSlipHistogram() {
        return sendSlipHistogram;
    }

    @Override
    public void reset(int expectedCount, String clOrdIdPrefix) {
        this.prefix = clOrdIdPrefix;
        publishedSends.set(0);
        latencyIndex.set(0);
        pendingOrders.set(0);
        Arrays.fill(intendedSendTimes, 0);
        Arrays.fill(actualSendTimes, 0);
        Arrays.fill(states, (byte) 0);
        Arrays.fill(latencies, 0);
        intendedHistogram.reset();
        actualHistogram.reset();
        sendSlipHistogram.reset();
        this.completionLatch = new CountDownLatch(expectedCount);
    }

    /**
     * Get the capacity of this tracker.
     *
     * @return the maximum number of latencies that can be recorded
     */
    public int getCapacity() {
        return capacity;
    }
}
