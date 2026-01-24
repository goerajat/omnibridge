package com.omnibridge.apps.fix.initiator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Array-based implementation of LatencyTracker.
 * Stores latencies in a pre-allocated array for zero-allocation during measurement.
 */
public class ArrayLatencyTracker implements LatencyTracker {

    private final int capacity;
    private final ConcurrentHashMap<String, Long> sendTimestamps;
    private final long[] latencies;
    private final AtomicInteger latencyIndex;
    private final AtomicInteger pendingOrders;
    private volatile CountDownLatch completionLatch;

    /**
     * Create a new ArrayLatencyTracker with the specified capacity.
     *
     * @param capacity the maximum number of latencies to record
     */
    public ArrayLatencyTracker(int capacity) {
        this.capacity = capacity;
        this.sendTimestamps = new ConcurrentHashMap<>();
        this.latencies = new long[capacity];
        this.latencyIndex = new AtomicInteger(0);
        this.pendingOrders = new AtomicInteger(0);
        this.completionLatch = new CountDownLatch(0);
    }

    @Override
    public void recordSendTime(String clOrdId, long sendTimeNanos) {
        sendTimestamps.put(clOrdId, sendTimeNanos);
        pendingOrders.incrementAndGet();
    }

    @Override
    public void recordResponseTime(String clOrdId, long receiveTimeNanos) {
        Long sendTime = sendTimestamps.remove(clOrdId);
        if (sendTime != null) {
            long latencyNanos = receiveTimeNanos - sendTime;
            int idx = latencyIndex.getAndIncrement();
            if (idx < latencies.length) {
                latencies[idx] = latencyNanos;
            }
        }
        pendingOrders.decrementAndGet();
        CountDownLatch latch = this.completionLatch;
        if (latch != null) {
            latch.countDown();
        }
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
    public void reset(int expectedCount) {
        sendTimestamps.clear();
        latencyIndex.set(0);
        pendingOrders.set(0);
        this.completionLatch = new CountDownLatch(expectedCount);
        // Clear latencies array
        for (int i = 0; i < latencies.length; i++) {
            latencies[i] = 0;
        }
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
