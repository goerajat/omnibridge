package com.fixengine.samples.initiator;

import com.fixengine.engine.session.FixSession;
import com.fixengine.message.FixTags;
import com.fixengine.message.RingBufferOutgoingMessage;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Runs latency performance tests on a FIX session.
 * Sends orders at a specified rate and measures round-trip latency.
 */
public class LatencyTestRunner {

    private final FixSession session;
    private final InitiatorMessageListener messageListener;
    private final int warmupOrders;
    private final int testOrders;
    private final int ordersPerSecond;
    private final int maxPendingOrders;
    private final int backpressureTimeoutSeconds;

    private final LatencyTracker tracker;
    private volatile boolean running = true;

    public LatencyTestRunner(FixSession session, InitiatorMessageListener messageListener,
                             int warmupOrders, int testOrders, int ordersPerSecond,
                             int maxPendingOrders, int backpressureTimeoutSeconds) {
        this.session = session;
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
     * @return true if test completed successfully
     */
    public boolean run() throws InterruptedException {
        System.out.println("\n========== LATENCY TRACKING MODE ==========\n");

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

    private boolean runWarmup() throws InterruptedException {
        // Reset tracker for warmup phase
        tracker.reset(warmupOrders);
        messageListener.setLatencyTracker(tracker);

        long startTime = System.currentTimeMillis();
        int ordersSent = 0;
        long backpressureStartTime = 0;
        long totalBackpressureTime = 0;

        for (int i = 0; i < warmupOrders && running; i++) {
            // Flow control
            while (tracker.getPendingOrderCount() >= maxPendingOrders && running) {
                if (backpressureStartTime == 0) {
                    backpressureStartTime = System.currentTimeMillis();
                }

                long backpressureDuration = System.currentTimeMillis() - backpressureStartTime;
                if (backpressureDuration > backpressureTimeoutSeconds * 1000L) {
                    System.err.println("\nERROR: Backpressure timeout exceeded (" + backpressureTimeoutSeconds +
                                     "s) - pending orders: " + tracker.getPendingOrderCount());
                    System.err.println("Warmup phase aborted after " + ordersSent + " orders.");
                    long elapsed = System.currentTimeMillis() - startTime;
                    printWarmupResults(ordersSent, elapsed, totalBackpressureTime);
                    return false;
                }
                Thread.sleep(1);
            }

            if (backpressureStartTime > 0) {
                totalBackpressureTime += System.currentTimeMillis() - backpressureStartTime;
                backpressureStartTime = 0;
            }

            try {
                String clOrdId = "WARMUP" + i;
                tracker.recordSendTime(clOrdId, System.nanoTime());
                sendOrder(clOrdId);
                ordersSent++;
            } catch (Exception e) {
                System.err.println("\nERROR: Failed to send warmup order " + i + ": " + e.getMessage());
                System.err.println("Warmup phase aborted after " + ordersSent + " orders.");
                long elapsed = System.currentTimeMillis() - startTime;
                printWarmupResults(ordersSent, elapsed, totalBackpressureTime);
                return false;
            }

            if (i % 1000 == 0 && i > 0) {
                Thread.yield();
            }
        }

        boolean completed = tracker.await(60, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;

        if (!completed) {
            System.out.println("WARNING: Not all warmup acks received within timeout");
        }

        printWarmupResults(ordersSent, elapsed, totalBackpressureTime);
        messageListener.setLatencyTracker(null);
        return true;
    }

    private boolean runLatencyTest() throws InterruptedException {
        // Reset tracker for test phase
        tracker.reset(testOrders);
        messageListener.setLatencyTracker(tracker);

        long intervalNanos = ordersPerSecond > 0 ? 1_000_000_000L / ordersPerSecond : 0;
        long startTime = System.nanoTime();
        long nextSendTime = startTime;
        int ordersSent = 0;
        boolean errorOccurred = false;
        long backpressureStartTime = 0;
        long totalBackpressureTimeNanos = 0;

        for (int i = 0; i < testOrders && running; i++) {
            String clOrdId = "TEST" + i;

            // Flow control
            while (tracker.getPendingOrderCount() >= maxPendingOrders && running) {
                if (backpressureStartTime == 0) {
                    backpressureStartTime = System.nanoTime();
                }

                long backpressureDurationMs = (System.nanoTime() - backpressureStartTime) / 1_000_000;
                if (backpressureDurationMs > backpressureTimeoutSeconds * 1000L) {
                    System.err.println("\nERROR: Backpressure timeout exceeded (" + backpressureTimeoutSeconds +
                                     "s) - pending orders: " + tracker.getPendingOrderCount());
                    System.err.println("Latency test aborted after " + ordersSent + " orders.");
                    errorOccurred = true;
                    break;
                }
                Thread.sleep(1);
            }

            if (errorOccurred) break;

            if (backpressureStartTime > 0) {
                totalBackpressureTimeNanos += System.nanoTime() - backpressureStartTime;
                backpressureStartTime = 0;
            }

            // Rate limiting
            if (intervalNanos > 0) {
                long now = System.nanoTime();
                long sleepNanos = nextSendTime - now;
                if (sleepNanos > 0) {
                    long sleepMillis = sleepNanos / 1_000_000;
                    int sleepNanosRemainder = (int) (sleepNanos % 1_000_000);
                    Thread.sleep(sleepMillis, sleepNanosRemainder);
                }
                nextSendTime += intervalNanos;
            }

            try {
                tracker.recordSendTime(clOrdId, System.nanoTime());
                sendOrder(clOrdId);
                ordersSent++;
            } catch (Exception e) {
                System.err.println("\nERROR: Failed to send test order " + i + ": " + e.getMessage());
                System.err.println("Latency test aborted after " + ordersSent + " orders.");
                errorOccurred = true;
                break;
            }
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
            printLatencyTestResults(ordersSent, receivedCount, totalTimeNanos, totalBackpressureTimeNanos);
            printLatencyStats(tracker.getLatencies(), receivedCount);
        } else {
            System.out.println("  No orders were sent successfully.");
        }

        return !errorOccurred;
    }

    int counter = 1;
    private void sendOrder(String clOrdId) throws Exception {
        RingBufferOutgoingMessage order = session.tryClaimMessage(FixTags.MsgTypes.NewOrderSingle);
        if (order == null) {
            throw new IllegalStateException("Ring buffer full - cannot send order " + clOrdId);
        }

        order.setField(FixTags.ClOrdID, clOrdId);
        order.setField(FixTags.Symbol, "TEST");
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

    public void stop() {
        running = false;
    }
}
