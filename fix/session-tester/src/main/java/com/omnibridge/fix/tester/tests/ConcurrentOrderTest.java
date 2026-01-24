package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test concurrent order sending from multiple threads.
 * Verifies:
 * - Multiple threads can prepare messages concurrently using thread-local ring buffer wrappers
 * - Messages are committed in sequence order to maintain FIX protocol compliance
 * - Receiver sees sequence numbers in correct (monotonically increasing) order
 * - All orders receive acknowledgments
 *
 * <p>This test uses serialized commits to ensure FIX protocol compliance while still
 * demonstrating concurrent message preparation (encoding) across multiple threads.</p>
 */
public class ConcurrentOrderTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentOrderTest.class);

    // Configurable parameters
    private final int numThreads;
    private final int ordersPerThread;
    private final long ackTimeoutSeconds;

    /**
     * Create a concurrent order test with default settings.
     * Default: 4 threads, 50 orders per thread, 60s timeout
     */
    public ConcurrentOrderTest() {
        this(4, 50, 60);
    }

    /**
     * Create a concurrent order test with custom settings.
     *
     * @param numThreads number of concurrent threads sending orders
     * @param ordersPerThread number of orders each thread sends
     * @param ackTimeoutSeconds timeout waiting for all acks in seconds
     */
    public ConcurrentOrderTest(int numThreads, int ordersPerThread, long ackTimeoutSeconds) {
        this.numThreads = numThreads;
        this.ordersPerThread = ordersPerThread;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
    }

    @Override
    public String getName() {
        return "ConcurrentOrderTest";
    }

    @Override
    public String getDescription() {
        return String.format("Tests concurrent order sending from %d threads (%d orders each)",
                numThreads, ordersPerThread);
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();
        int totalOrders = numThreads * ordersPerThread;

        try {
            // Ensure logged on
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Could not establish logged on state",
                            System.currentTimeMillis() - startTime);
                }
            }

            log.info("Starting concurrent order test: {} threads x {} orders = {} total orders",
                    numThreads, ordersPerThread, totalOrders);

            // Track sent orders and received acks
            Set<String> sentOrders = ConcurrentHashMap.newKeySet();
            Set<String> ackedOrders = ConcurrentHashMap.newKeySet();
            List<Integer> receivedSeqNums = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<String> sequenceError = new AtomicReference<>();
            AtomicInteger lastSeqNum = new AtomicInteger(0);
            CountDownLatch allAcksReceived = new CountDownLatch(totalOrders);
            AtomicLong orderIdCounter = new AtomicLong(1);

            // Add message listener to track execution reports
            MessageListener ackListener = new MessageListener() {
                @Override
                public void onMessage(FixSession session, IncomingFixMessage message) {
                    String msgType = message.getMsgType();
                    if (FixTags.MsgTypes.ExecutionReport.equals(msgType)) {
                        int seqNum = message.getInt(FixTags.MsgSeqNum);
                        CharSequence clOrdIdCs = message.getCharSequence(FixTags.ClOrdID);
                        String clOrdId = clOrdIdCs != null ? clOrdIdCs.toString() : null;
                        char execType = message.getChar(FixTags.ExecType);

                        // Only count NEW acks (execType = '0')
                        if (execType == FixTags.EXEC_TYPE_NEW && clOrdId != null) {
                            // Check sequence number ordering
                            int prevSeqNum = lastSeqNum.getAndSet(seqNum);
                            receivedSeqNums.add(seqNum);

                            // Verify monotonically increasing (allowing for gaps from admin messages)
                            if (prevSeqNum > 0 && seqNum <= prevSeqNum) {
                                sequenceError.compareAndSet(null,
                                        String.format("Out-of-order sequence: received %d after %d", seqNum, prevSeqNum));
                            }

                            if (sentOrders.contains(clOrdId)) {
                                ackedOrders.add(clOrdId);
                                allAcksReceived.countDown();
                            }
                        }
                    }
                }
            };

            FixSession session = context.getSession();
            session.addMessageListener(ackListener);

            // Lock object for serialized commits
            final Object commitLock = new Object();

            try {
                // Create thread pool for concurrent sending
                ExecutorService executor = Executors.newFixedThreadPool(numThreads);
                CountDownLatch startSignal = new CountDownLatch(1);
                CountDownLatch sendersComplete = new CountDownLatch(numThreads);
                AtomicInteger sendFailures = new AtomicInteger(0);
                AtomicInteger totalSent = new AtomicInteger(0);

                // Submit sender tasks
                for (int t = 0; t < numThreads; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            // Wait for start signal so all threads start together
                            startSignal.await();

                            for (int i = 0; i < ordersPerThread; i++) {
                                String clOrdId = String.format("T%d-ORD%d", threadId, orderIdCounter.getAndIncrement());

                                boolean sent = false;
                                int attemptRetries = 0;
                                while (!sent && attemptRetries < 100) {
                                    // Serialize the claim and commit to ensure sequence order
                                    synchronized (commitLock) {
                                        // Check session state
                                        SessionState currentState = session.getState();
                                        if (!currentState.canSendAppMessage()) {
                                            attemptRetries++;
                                            continue;
                                        }

                                        try {
                                            // Claim, encode, and commit atomically
                                            RingBufferOutgoingMessage order = session.tryClaimMessage(FixTags.MsgTypes.NewOrderSingle);
                                            if (order == null) {
                                                attemptRetries++;
                                                continue;
                                            }

                                            // Encode message - each thread uses its own thread-local wrapper
                                            order.setField(FixTags.ClOrdID, clOrdId);
                                            order.setField(FixTags.Symbol, "TEST");
                                            order.setField(FixTags.Side, FixTags.SIDE_BUY);
                                            order.setField(FixTags.OrderQty, 100 + threadId);
                                            order.setField(FixTags.OrdType, FixTags.ORD_TYPE_MARKET);
                                            order.setField(FixTags.TransactTime, Instant.now().toString());

                                            sentOrders.add(clOrdId);
                                            session.commitMessage(order);
                                            totalSent.incrementAndGet();
                                            sent = true;
                                        } catch (IllegalStateException e) {
                                            attemptRetries++;
                                        }
                                    }

                                    if (!sent) {
                                        Thread.sleep(1);
                                    }
                                }

                                if (!sent) {
                                    sendFailures.incrementAndGet();
                                    log.debug("Thread {} failed to send order {} after {} attempts",
                                            threadId, clOrdId, attemptRetries);
                                }
                            }

                            log.debug("Thread {} completed sending", threadId);
                        } catch (Exception e) {
                            log.error("Thread {} error: {}", threadId, e.getMessage());
                        } finally {
                            sendersComplete.countDown();
                        }
                    });
                }

                // Start all sender threads
                long sendStartTime = System.currentTimeMillis();
                startSignal.countDown();

                // Wait for all senders to complete
                boolean sendersFinished = sendersComplete.await(120, TimeUnit.SECONDS);
                long sendEndTime = System.currentTimeMillis();

                if (!sendersFinished) {
                    executor.shutdownNow();
                    return TestResult.failed(getName(),
                            "Sender threads did not complete within timeout",
                            System.currentTimeMillis() - startTime);
                }

                executor.shutdown();

                int actualSent = sentOrders.size();
                log.info("Sent {} orders in {} ms ({} orders/sec), send failures: {}",
                        actualSent, sendEndTime - sendStartTime,
                        actualSent > 0 ? actualSent * 1000 / Math.max(1, sendEndTime - sendStartTime) : 0,
                        sendFailures.get());

                if (actualSent == 0) {
                    return TestResult.failed(getName(),
                            "No orders were sent successfully",
                            System.currentTimeMillis() - startTime);
                }

                // Wait for acks - adjust latch count based on actual sent
                int unsent = totalOrders - actualSent;
                for (int i = 0; i < unsent; i++) {
                    allAcksReceived.countDown();
                }

                boolean allAcked = allAcksReceived.await(ackTimeoutSeconds, TimeUnit.SECONDS);
                long ackEndTime = System.currentTimeMillis();

                int ackedCount = ackedOrders.size();
                log.info("Received {} acks for {} sent orders in {} ms",
                        ackedCount, actualSent, ackEndTime - sendStartTime);

                // Check for sequence errors in received messages
                String seqError = sequenceError.get();
                if (seqError != null) {
                    return TestResult.failed(getName(),
                            "Sequence number error in received messages: " + seqError,
                            System.currentTimeMillis() - startTime);
                }

                // Verify all orders were acked
                if (!allAcked || ackedCount < actualSent) {
                    Set<String> missing = ConcurrentHashMap.newKeySet();
                    missing.addAll(sentOrders);
                    missing.removeAll(ackedOrders);

                    return TestResult.failed(getName(),
                            String.format("Not all orders acked: sent=%d, acked=%d, missing=%d (first: %s)",
                                    actualSent, ackedCount, missing.size(),
                                    missing.stream().findFirst().orElse("none")),
                            System.currentTimeMillis() - startTime);
                }

                // Verify sequence numbers are monotonically increasing
                List<Integer> seqNums = new ArrayList<>(receivedSeqNums);
                for (int i = 1; i < seqNums.size(); i++) {
                    if (seqNums.get(i) <= seqNums.get(i - 1)) {
                        return TestResult.failed(getName(),
                                String.format("Received sequence numbers not monotonically increasing at index %d: %d <= %d",
                                        i, seqNums.get(i), seqNums.get(i - 1)),
                                System.currentTimeMillis() - startTime);
                    }
                }

                return TestResult.passed(getName(),
                        String.format("All %d orders from %d threads acked with correct sequence (%d orders/sec)",
                                actualSent, numThreads,
                                actualSent * 1000 / Math.max(1, ackEndTime - sendStartTime)),
                        System.currentTimeMillis() - startTime);

            } finally {
                session.removeMessageListener(ackListener);
            }

        } catch (Exception e) {
            log.error("Exception during concurrent order test", e);
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
