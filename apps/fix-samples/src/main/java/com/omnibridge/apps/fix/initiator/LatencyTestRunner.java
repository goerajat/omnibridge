package com.omnibridge.apps.fix.initiator;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.network.NetworkEventLoop;
import com.omnibridge.fix.engine.timing.StageTimingRecorder;
import com.omnibridge.fix.engine.timing.StageTimingReport;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import org.HdrHistogram.Histogram;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs latency performance tests on a FIX session.
 * Sends orders at a specified rate and measures round-trip latency.
 *
 * <p>The summary table keeps its original lines (scripts parse them); HdrHistogram
 * percentiles from the intended send time (coordinated-omission corrected) and from the
 * actual send time follow as {@code key=value} lines, then the stage timing report when
 * the session has stage timing enabled.</p>
 */
public class LatencyTestRunner {

    private static final String WARMUP_PREFIX = "WARMUP";
    private static final String TEST_PREFIX = "TEST";
    // The exchange simulator acks NOFILL* orders and never fills them (its fill-engine rules),
    // so only the ack this test measures comes back
    private static final String SYMBOL = "NOFILL";
    // Lead time before the first paced order, so it is not already late
    private static final long PACER_LEAD_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final FixSession session;
    // Sends from the loop's own thread when set (the default); null sends from this thread
    private final NetworkEventLoop eventLoop;
    private final InitiatorMessageListener messageListener;
    private final int warmupOrders;
    private final int testOrders;
    private final int ordersPerSecond;
    private final int maxPendingOrders;
    private final int backpressureTimeoutSeconds;

    private final LatencyTracker tracker;
    private final StringBuilder clOrdId = new StringBuilder(32);
    private long warmupReadCapHits;
    private volatile boolean running = true;

    /**
     * @param eventLoop the session's event loop, to send the orders from its thread (see
     *                  {@link #run()}); null sends them from the thread that calls {@code run}
     */
    public LatencyTestRunner(FixSession session, NetworkEventLoop eventLoop, InitiatorMessageListener messageListener,
                             int warmupOrders, int testOrders, int ordersPerSecond,
                             int maxPendingOrders, int backpressureTimeoutSeconds) {
        this.session = session;
        this.eventLoop = eventLoop;
        this.messageListener = messageListener;
        this.warmupOrders = warmupOrders;
        this.testOrders = testOrders;
        this.ordersPerSecond = ordersPerSecond;
        this.maxPendingOrders = maxPendingOrders;
        this.backpressureTimeoutSeconds = backpressureTimeoutSeconds;

        // Create tracker with capacity for the larger of warmup or test orders
        int capacity = Math.max(warmupOrders, testOrders);
        this.tracker = new ArrayLatencyTracker(capacity);
    }

    /**
     * Run the latency test (warmup + actual test).
     *
     * <p>With an event loop, both phases send from the loop's own thread through its loop
     * task: each iteration sends the next order once it is due (timed phase) or as soon as
     * flow control allows (warmup). The loop busy-spins, so an order is sent within an
     * iteration of its intended time and there is no thread hand-off or wake-up between the
     * pacer and the socket write. Without a loop the orders are sent from this thread, which
     * spins on the schedule and commits to the ring for the loop to write.</p>
     *
     * @return true if test completed successfully
     */
    public boolean run() throws InterruptedException {
        System.out.println("\n========== LATENCY TRACKING MODE ==========\n");
        System.out.println("Sender: " + (eventLoop != null ? "event loop thread" : "main thread"));

        // Phase 1: Warmup
        System.out.println("Phase 1: Warmup (" + warmupOrders + " orders for JIT compilation)...");
        boolean warmupSuccess = runWarmup();

        if (!warmupSuccess) {
            System.out.println("\n========== TEST ABORTED (Warmup Failed) ==========\n");
            return false;
        }

        System.out.println("Warmup complete.\n");

        // Small pause between phases
        Thread.sleep(10000);

        // Measure stages over the timed phase only, on both ends of the session
        if (session.requestStageTimingReset()) {
            System.out.println("Stage timing enabled: reset sent to both sides.");
        }

        // Phase 2: Latency test
        System.out.println("Phase 2: Latency test (" + testOrders + " orders at " +
                          (ordersPerSecond > 0 ? ordersPerSecond + " orders/sec" : "max rate") + ")...");
        boolean testSuccess = runLatencyTest();

        if (testSuccess) {
            System.out.println("\n========== TEST COMPLETE ==========\n");
        } else {
            System.out.println("\n========== TEST ABORTED (Error During Test) ==========");
            System.out.println("Partial results shown above.\n");
        }

        return testSuccess;
    }

    /** What a phase's sender did; written by the sending thread, read after it is done. */
    private static final class SendResult {
        int sent;
        long backpressureNanos;
        String error;   // null when every order went out
    }

    /**
     * One phase's orders. Warmup (no pacer) sends whenever flow control allows; the timed phase
     * sends order {@code i} once it is due. {@link #tick()} sends at most one order and never
     * waits, so it can run as the loop task; {@link #sendAll()} spins on it from a thread.
     */
    private final class PhaseSender implements Runnable {
        private final String prefix;
        private final int count;
        private final RatePacer pacer;   // null: as fast as flow control allows
        private final long backpressureTimeoutNanos = TimeUnit.SECONDS.toNanos(backpressureTimeoutSeconds);
        private final CountDownLatch done = new CountDownLatch(1);
        private final SendResult result = new SendResult();
        private int next;
        private long backpressureStart;

        PhaseSender(String prefix, int count, RatePacer pacer) {
            this.prefix = prefix;
            this.count = count;
            this.pacer = pacer;
        }

        boolean isDone() {
            return done.getCount() == 0;
        }

        /** Loop task: one tick per loop iteration. */
        @Override
        public void run() {
            tick();
        }

        /** Send the next order if it is due and allowed; finish the phase when none are left. */
        void tick() {
            if (isDone()) {
                return;
            }
            if (next >= count || !running) {
                finish();
                return;
            }
            if (tracker.getPendingOrderCount() >= maxPendingOrders) {
                long now = System.nanoTime();
                if (backpressureStart == 0) {
                    backpressureStart = now;
                } else if (now - backpressureStart > backpressureTimeoutNanos) {
                    result.error = "Backpressure timeout exceeded (" + backpressureTimeoutSeconds +
                            "s) - pending orders: " + tracker.getPendingOrderCount();
                    finish();
                }
                return;
            }
            if (backpressureStart != 0) {
                result.backpressureNanos += System.nanoTime() - backpressureStart;
                backpressureStart = 0;
            }
            long intended = pacer != null ? pacer.intendedSendTime(next) : 0;
            long now = System.nanoTime();
            if (pacer != null && now - intended < 0) {
                return;   // not due yet
            }
            try {
                tracker.recordSend(next, pacer != null ? intended : now, now);
                sendOrder(prefix, next);
                next++;
                result.sent++;
            } catch (Exception e) {
                result.error = "Failed to send order " + next + ": " + e.getMessage();
                finish();
                return;
            }
            if (next >= count) {
                finish();
            }
        }

        private void finish() {
            if (backpressureStart != 0) {
                result.backpressureNanos += System.nanoTime() - backpressureStart;
                backpressureStart = 0;
            }
            done.countDown();
        }

        /** Spin on {@link #tick()} from the calling thread until the phase is done. */
        SendResult sendAll() {
            while (!isDone()) {
                tick();
                Thread.onSpinWait();
            }
            return result;
        }

        /** Run as the loop task until the phase is done or the bound passes. */
        SendResult sendAllOnLoop(long bound, TimeUnit unit) throws InterruptedException {
            eventLoop.setLoopTask(this);
            try {
                if (!done.await(bound, unit)) {
                    result.error = "Sender did not finish within " + unit.toSeconds(bound) + "s (sent " + result.sent + ")";
                    done.countDown();
                }
            } finally {
                eventLoop.setLoopTask(null);
            }
            return result;
        }
    }

    private SendResult send(String prefix, int count, RatePacer pacer) throws InterruptedException {
        PhaseSender sender = new PhaseSender(prefix, count, pacer);
        if (eventLoop == null) {
            return sender.sendAll();
        }
        // The schedule's length plus the backpressure allowance, with room for a slow start
        long boundSeconds = (pacer != null && ordersPerSecond > 0 ? (long) count / ordersPerSecond : 0)
                + backpressureTimeoutSeconds + 60;
        return sender.sendAllOnLoop(boundSeconds, TimeUnit.SECONDS);
    }

    private boolean runWarmup() throws InterruptedException {
        // Reset tracker for warmup phase
        tracker.reset(warmupOrders, WARMUP_PREFIX);
        messageListener.setLatencyTracker(tracker);

        long startTime = System.currentTimeMillis();
        SendResult sent = send(WARMUP_PREFIX, warmupOrders, null);
        long backpressureMs = sent.backpressureNanos / 1_000_000;

        if (sent.error != null) {
            System.err.println("\nERROR: " + sent.error);
            System.err.println("Warmup phase aborted after " + sent.sent + " orders.");
            printWarmupResults(sent.sent, System.currentTimeMillis() - startTime, backpressureMs);
            messageListener.setLatencyTracker(null);
            return false;
        }

        boolean completed = tracker.await(60, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;

        if (!completed) {
            System.out.println("WARNING: Not all warmup acks received within timeout");
        }

        printWarmupResults(sent.sent, elapsed, backpressureMs);
        printWarmupBurst();
        messageListener.setLatencyTracker(null);
        warmupReadCapHits = session.getReadCapHits();
        return true;
    }

    /**
     * The warmup is an unpaced burst: acks queue behind each other, and one read can bring
     * as many as the read cap allows. Shows how long the first ack and the rest took.
     */
    private void printWarmupBurst() {
        int received = tracker.getRecordedCount();
        if (received == 0) {
            return;
        }
        Histogram rtt = tracker.getActualHistogram();
        System.out.printf("  Warmup burst: acks=%d first-ack=%.2f p50=%.2f p99=%.2f max=%.2f us, read-cap hits initiator=%d%n",
                received, tracker.getLatencies()[0] / 1000.0,
                rtt.getValueAtPercentile(50) / 1000.0, rtt.getValueAtPercentile(99) / 1000.0,
                rtt.getMaxValue() / 1000.0, session.getReadCapHits());
    }

    private boolean runLatencyTest() throws InterruptedException {
        // Reset tracker for test phase
        tracker.reset(testOrders, TEST_PREFIX);
        messageListener.setLatencyTracker(tracker);

        long startTime = System.nanoTime();
        RatePacer pacer = new RatePacer(startTime + PACER_LEAD_NANOS, ordersPerSecond);
        SendResult sent = send(TEST_PREFIX, testOrders, pacer);
        int ordersSent = sent.sent;
        boolean errorOccurred = sent.error != null;
        if (errorOccurred) {
            System.err.println("\nERROR: " + sent.error);
            System.err.println("Latency test aborted after " + ordersSent + " orders.");
        }

        long totalTimeNanos = System.nanoTime() - startTime;

        // Wait for responses
        int waitTimeSeconds = errorOccurred ? 10 : 60;
        if (ordersSent > 0) {
            long waitStart = System.currentTimeMillis();
            while (tracker.getRecordedCount() < ordersSent &&
                   (System.currentTimeMillis() - waitStart) < waitTimeSeconds * 1000) {
                Thread.sleep(100);
            }
        }

        messageListener.setLatencyTracker(null);

        int receivedCount = tracker.getRecordedCount();
        if (receivedCount < ordersSent && !errorOccurred) {
            System.out.println("WARNING: Not all acks received within timeout");
        }

        if (ordersSent > 0) {
            printLatencyTestResults(ordersSent, receivedCount, totalTimeNanos, sent.backpressureNanos);
            printLatencyStats(tracker.getLatencies(), receivedCount);
            printHistograms(pacer.isPaced());
            // Reads that filled the read cap during the timed phase (scripts parse this line)
            System.out.println("  Read cap hits: initiator=" + (session.getReadCapHits() - warmupReadCapHits));
            printStageTiming();
        } else {
            System.out.println("  No orders were sent successfully.");
        }

        return !errorOccurred;
    }

    int counter = 1;
    private void sendOrder(String prefix, int index) throws Exception {
        clOrdId.setLength(0);
        clOrdId.append(prefix).append(index);

        RingBufferOutgoingMessage order = session.tryClaimMessage(FixTags.MsgTypes.NewOrderSingle);
        if (order == null) {
            throw new IllegalStateException("Ring buffer full - cannot send order " + clOrdId);
        }

        order.setField(FixTags.ClOrdID, clOrdId);
        order.setField(FixTags.Symbol, SYMBOL);
        order.setField(FixTags.Side, FixTags.SIDE_BUY);
        order.setField(FixTags.OrderQty, 100 + (counter++));
        order.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
        order.setField(FixTags.Price, 100.00, 2);
        order.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
        session.commitMessage(order);
    }

    private void printWarmupResults(int ordersSent, long elapsedMs, long backpressureMs) {
        double effectiveTime = elapsedMs - backpressureMs;
        double overallRate = ordersSent * 1000.0 / elapsedMs;
        double effectiveRate = effectiveTime > 0 ? ordersSent * 1000.0 / effectiveTime : 0;

        System.out.printf("  Warmup: %d orders in %d ms%n", ordersSent, elapsedMs);
        System.out.printf("  Overall rate: %.0f orders/sec%n", overallRate);
        if (backpressureMs > 0) {
            System.out.printf("  Backpressure time: %d ms (%.1f%%)%n", backpressureMs, backpressureMs * 100.0 / elapsedMs);
            System.out.printf("  Effective rate (excluding backpressure): %.0f orders/sec%n", effectiveRate);
        }
    }

    private void printLatencyTestResults(int ordersSent, int acksReceived, long totalTimeNanos, long backpressureTimeNanos) {
        double totalTimeMs = totalTimeNanos / 1_000_000.0;
        double backpressureTimeMs = backpressureTimeNanos / 1_000_000.0;
        double effectiveTimeMs = totalTimeMs - backpressureTimeMs;
        double overallRate = ordersSent * 1000.0 / totalTimeMs;
        double effectiveRate = effectiveTimeMs > 0 ? ordersSent * 1000.0 / effectiveTimeMs : 0;

        System.out.printf("  Sent: %d orders, Received: %d acks in %.2f ms%n", ordersSent, acksReceived, totalTimeMs);
        System.out.printf("  Overall rate: %.0f orders/sec%n", overallRate);

        if (backpressureTimeMs > 0) {
            System.out.printf("  Backpressure time: %.0f ms (%.1f%%)%n", backpressureTimeMs, backpressureTimeMs * 100.0 / totalTimeMs);
            System.out.printf("  Effective rate (excluding backpressure): %.0f orders/sec%n", effectiveRate);
        }
    }

    private void printLatencyStats(long[] latencies, int count) {
        if (count == 0) {
            System.out.println("  No latency data collected");
            return;
        }

        long[] sorted = Arrays.copyOf(latencies, count);
        Arrays.sort(sorted);

        long min = sorted[0];
        long max = sorted[count - 1];
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += sorted[i];
        }
        double avg = (double) sum / count;

        long p50 = sorted[(int) (count * 0.50)];
        long p90 = sorted[(int) (count * 0.90)];
        long p95 = sorted[(int) (count * 0.95)];
        long p99 = sorted[(int) (count * 0.99)];
        long p999 = sorted[(int) Math.min(count * 0.999, count - 1)];

        System.out.println("\n  Latency Statistics (microseconds):");
        System.out.println("  +-------------+--------------+");
        System.out.println("  | Metric      | Value        |");
        System.out.println("  +-------------+--------------+");
        System.out.printf("  | Count       | %,12d |%n", count);
        System.out.printf("  | Min         | %,12.2f |%n", min / 1000.0);
        System.out.printf("  | Max         | %,12.2f |%n", max / 1000.0);
        System.out.printf("  | Avg         | %,12.2f |%n", avg / 1000.0);
        System.out.printf("  | p50         | %,12.2f |%n", p50 / 1000.0);
        System.out.printf("  | p90         | %,12.2f |%n", p90 / 1000.0);
        System.out.printf("  | p95         | %,12.2f |%n", p95 / 1000.0);
        System.out.printf("  | p99         | %,12.2f |%n", p99 / 1000.0);
        System.out.printf("  | p99.9       | %,12.2f |%n", p999 / 1000.0);
        System.out.println("  +-------------+--------------+");
    }

    /**
     * HdrHistogram percentiles as key=value lines. They must not look like the table rows
     * above ("| p50 | ..."), which scripts parse by taking the last match.
     */
    private void printHistograms(boolean paced) {
        System.out.println();
        System.out.println("  RTT from intended send (CO-corrected), us: "
                + formatHistogram(tracker.getIntendedHistogram()));
        System.out.println("  RTT from actual send, us: " + formatHistogram(tracker.getActualHistogram()));
        if (paced) {
            System.out.println("  Send slip (actual - intended), us: "
                    + formatHistogram(tracker.getSendSlipHistogram()));
        }
    }

    private static String formatHistogram(Histogram h) {
        return com.omnibridge.apps.common.LatencyTracker.formatHistogram(h);
    }

    private void printStageTiming() {
        StageTimingRecorder timing = session.getStageTiming();
        if (timing == null) {
            return;
        }
        System.out.println();
        System.out.print(StageTimingReport.format(timing, session.getConfig().getSessionId()));
        timing.markReported();
    }

    public void stop() {
        running = false;
    }
}
