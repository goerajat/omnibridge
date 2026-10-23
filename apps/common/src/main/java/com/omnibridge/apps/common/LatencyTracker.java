package com.omnibridge.apps.common;

import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks latencies for performance testing.
 *
 * <p>Thread-safe latency tracking with efficient percentile calculations.
 * Stores nanosecond timestamps and calculates statistics. Round trips are also recorded
 * into HdrHistograms, measured both from the actual send and from the intended send time
 * of a rate schedule ({@link #recordSend(long)}), which corrects for coordinated omission.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * LatencyTracker tracker = new LatencyTracker(1000);
 *
 * // When sending
 * int idx = tracker.recordSend();
 * sendOrder();
 *
 * // When receiving response
 * tracker.recordReceive(idx);
 *
 * // Print results
 * tracker.printStatistics();
 * }</pre>
 */
public class LatencyTracker {

    private static final Logger log = LoggerFactory.getLogger(LatencyTracker.class);

    private final int capacity;
    private final long[] sendTimes;
    private final long[] latencies;
    private final long[] intendedSendTimes;
    private final Histogram actualHistogram = newHistogram();
    private final Histogram intendedHistogram = newHistogram();
    private final AtomicInteger sentCount = new AtomicInteger(0);
    private final AtomicInteger receivedCount = new AtomicInteger(0);
    private long testStartTime;
    private long testEndTime;

    /**
     * Create a new latency tracker with the specified capacity.
     *
     * @param capacity maximum number of samples to track
     */
    public LatencyTracker(int capacity) {
        this.capacity = capacity;
        this.sendTimes = new long[capacity];
        this.latencies = new long[capacity];
        this.intendedSendTimes = new long[capacity];
    }

    private static Histogram newHistogram() {
        return new Histogram(TimeUnit.MINUTES.toNanos(1), 3);
    }

    /**
     * Reset the tracker for a new test run.
     */
    public void reset() {
        sentCount.set(0);
        receivedCount.set(0);
        actualHistogram.reset();
        intendedHistogram.reset();
        testStartTime = System.nanoTime();
        testEndTime = 0;
    }

    /**
     * Record a send event and return the index for correlation.
     *
     * @return the index to use when recording the receive
     */
    public int recordSend() {
        return recordSend(0);
    }

    /**
     * Record a send event that the rate schedule meant to happen at {@code intendedSendNanos}.
     *
     * @param intendedSendNanos the scheduled send time (System.nanoTime()), or 0 for "now"
     * @return the index to use when recording the receive
     */
    public int recordSend(long intendedSendNanos) {
        int idx = sentCount.getAndIncrement();
        if (idx < capacity) {
            long now = System.nanoTime();
            sendTimes[idx] = now;
            intendedSendTimes[idx] = intendedSendNanos != 0 ? intendedSendNanos : now;
        }
        return idx;
    }

    /**
     * Record a receive event for the given index.
     *
     * @param index the index returned by recordSend()
     */
    public void recordReceive(int index) {
        if (index < capacity) {
            long receiveTime = System.nanoTime();
            latencies[index] = receiveTime - sendTimes[index];
            actualHistogram.recordValue(clamp(latencies[index]));
            intendedHistogram.recordValue(clamp(receiveTime - intendedSendTimes[index]));
            receivedCount.incrementAndGet();
        }
    }

    private static long clamp(long nanos) {
        return Math.max(0, Math.min(nanos, TimeUnit.MINUTES.toNanos(1)));
    }

    /**
     * Round trips from the intended send time (coordinated-omission corrected).
     */
    public Histogram getIntendedHistogram() {
        return intendedHistogram;
    }

    /**
     * Round trips from the actual send time.
     */
    public Histogram getActualHistogram() {
        return actualHistogram;
    }

    /**
     * HdrHistogram percentiles as {@code key=value} text (microseconds).
     */
    public static String formatHistogram(Histogram h) {
        if (h.getTotalCount() == 0) {
            return "count=0";
        }
        return String.format(Locale.ROOT,
                "count=%d min=%.2f p50=%.2f p90=%.2f p99=%.2f p99.9=%.2f p99.99=%.2f max=%.2f mean=%.2f",
                h.getTotalCount(), h.getMinValue() / 1000.0,
                h.getValueAtPercentile(50) / 1000.0, h.getValueAtPercentile(90) / 1000.0,
                h.getValueAtPercentile(99) / 1000.0, h.getValueAtPercentile(99.9) / 1000.0,
                h.getValueAtPercentile(99.99) / 1000.0, h.getMaxValue() / 1000.0, h.getMean() / 1000.0);
    }

    /**
     * Mark the test as complete.
     */
    public void complete() {
        testEndTime = System.nanoTime();
    }

    /**
     * Get the number of sent messages.
     */
    public int getSentCount() {
        return sentCount.get();
    }

    /**
     * Get the number of received responses.
     */
    public int getReceivedCount() {
        return receivedCount.get();
    }

    /**
     * Check if all responses have been received.
     */
    public boolean isComplete() {
        return receivedCount.get() >= sentCount.get();
    }

    /**
     * Get the total test duration in nanoseconds.
     */
    public long getTotalTimeNanos() {
        return testEndTime > 0 ? testEndTime - testStartTime : System.nanoTime() - testStartTime;
    }

    /**
     * Calculate and return latency statistics.
     *
     * @return the latency statistics, or null if no data
     */
    public LatencyStats calculateStats() {
        int received = receivedCount.get();
        if (received == 0) {
            return null;
        }

        // Sort latencies for percentile calculation
        long[] validLatencies = new long[received];
        System.arraycopy(latencies, 0, validLatencies, 0, received);
        Arrays.sort(validLatencies);

        long min = validLatencies[0];
        long max = validLatencies[received - 1];
        long sum = 0;
        for (int i = 0; i < received; i++) {
            sum += validLatencies[i];
        }
        long avg = sum / received;

        long p50 = validLatencies[received * 50 / 100];
        long p90 = validLatencies[received * 90 / 100];
        long p95 = validLatencies[received * 95 / 100];
        long p99 = validLatencies[received * 99 / 100];
        long p999 = received > 1000 ? validLatencies[received * 999 / 1000] : p99;

        return new LatencyStats(
                sentCount.get(), received, getTotalTimeNanos(),
                min, max, avg, p50, p90, p95, p99, p999
        );
    }

    /**
     * Print latency statistics to the log.
     */
    public void printStatistics() {
        LatencyStats stats = calculateStats();
        if (stats == null) {
            log.warn("No responses received");
            return;
        }

        log.info("");
        log.info("=".repeat(60));
        log.info("LATENCY TEST RESULTS");
        log.info("=".repeat(60));
        log.info("Orders sent:     {}", stats.sent());
        log.info("Orders received: {}", stats.received());
        log.info("Total time:      {} ms", stats.totalTimeNanos() / 1_000_000);
        log.info("Throughput:      {} orders/sec", stats.throughput());
        log.info("");
        log.info("Latency (microseconds):");
        log.info("  Min:    {}", stats.minMicros());
        log.info("  Max:    {}", stats.maxMicros());
        log.info("  Avg:    {}", stats.avgMicros());
        log.info("  P50:    {}", stats.p50Micros());
        log.info("  P90:    {}", stats.p90Micros());
        log.info("  P95:    {}", stats.p95Micros());
        log.info("  P99:    {}", stats.p99Micros());
        if (stats.received() > 1000) {
            log.info("  P99.9:  {}", stats.p999Micros());
        }
        log.info("=".repeat(60));
        log.info("RTT from intended send (CO-corrected), us: {}", formatHistogram(intendedHistogram));
        log.info("RTT from actual send, us: {}", formatHistogram(actualHistogram));
    }

    /**
     * Print statistics to stdout (for latency mode with minimal logging).
     */
    public void printStatisticsToStdout() {
        LatencyStats stats = calculateStats();
        if (stats == null) {
            System.out.println("No responses received");
            return;
        }

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("LATENCY TEST RESULTS");
        System.out.println("=".repeat(60));
        System.out.println("Orders sent:     " + stats.sent());
        System.out.println("Orders received: " + stats.received());
        System.out.println("Total time:      " + stats.totalTimeNanos() / 1_000_000 + " ms");
        System.out.println("Throughput:      " + stats.throughput() + " orders/sec");
        System.out.println();
        System.out.println("Latency (microseconds):");
        System.out.println("  Min:    " + stats.minMicros());
        System.out.println("  Max:    " + stats.maxMicros());
        System.out.println("  Avg:    " + stats.avgMicros());
        System.out.println("  P50:    " + stats.p50Micros());
        System.out.println("  P90:    " + stats.p90Micros());
        System.out.println("  P95:    " + stats.p95Micros());
        System.out.println("  P99:    " + stats.p99Micros());
        if (stats.received() > 1000) {
            System.out.println("  P99.9:  " + stats.p999Micros());
        }
        System.out.println("=".repeat(60));
        System.out.println("RTT from intended send (CO-corrected), us: " + formatHistogram(intendedHistogram));
        System.out.println("RTT from actual send, us: " + formatHistogram(actualHistogram));
    }

    /**
     * Latency statistics record.
     */
    public record LatencyStats(
            int sent,
            int received,
            long totalTimeNanos,
            long minNanos,
            long maxNanos,
            long avgNanos,
            long p50Nanos,
            long p90Nanos,
            long p95Nanos,
            long p99Nanos,
            long p999Nanos
    ) {
        public long minMicros() { return minNanos / 1000; }
        public long maxMicros() { return maxNanos / 1000; }
        public long avgMicros() { return avgNanos / 1000; }
        public long p50Micros() { return p50Nanos / 1000; }
        public long p90Micros() { return p90Nanos / 1000; }
        public long p95Micros() { return p95Nanos / 1000; }
        public long p99Micros() { return p99Nanos / 1000; }
        public long p999Micros() { return p999Nanos / 1000; }
        public long throughput() {
            return totalTimeNanos > 0 ? (long) received * 1_000_000_000L / totalTimeNanos : 0;
        }
    }
}
