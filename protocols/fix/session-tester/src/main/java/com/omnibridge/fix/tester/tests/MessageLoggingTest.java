package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test that message exchange produces correct sequence numbers and message counts.
 *
 * <p>Verifies: after exchanging a known number of messages (TestRequest/Heartbeat pairs),
 * the sequence numbers have advanced by the expected amount. This validates that the
 * message counting, sequencing, and logging infrastructure is working correctly.</p>
 */
public class MessageLoggingTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(MessageLoggingTest.class);

    private static final int MESSAGE_COUNT = 5;

    @Override
    public String getName() {
        return "MessageLoggingTest";
    }

    @Override
    public String getDescription() {
        return "Tests message exchange produces correct sequence numbers and counts";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Could not establish logged on state",
                            System.currentTimeMillis() - startTime);
                }
            }

            int outgoingBefore = context.getOutgoingSeqNum();
            int incomingBefore = context.getExpectedIncomingSeqNum();

            log.info("Before: outgoing={}, expectedIncoming={}", outgoingBefore, incomingBefore);

            // Send known number of TestRequests and count Heartbeat responses
            AtomicInteger heartbeatCount = new AtomicInteger(0);

            for (int i = 0; i < MESSAGE_COUNT; i++) {
                CountDownLatch latch = new CountDownLatch(1);
                MessageListener listener = (session, message) -> {
                    if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                        heartbeatCount.incrementAndGet();
                        latch.countDown();
                    }
                };
                context.getSession().addMessageListener(listener);
                try {
                    context.sendTestRequest();
                    boolean received = latch.await(5, TimeUnit.SECONDS);
                    if (!received) {
                        return TestResult.failed(getName(),
                                String.format("No Heartbeat response for TestRequest %d/%d",
                                        i + 1, MESSAGE_COUNT),
                                System.currentTimeMillis() - startTime);
                    }
                } finally {
                    context.getSession().removeMessageListener(listener);
                }
            }

            int outgoingAfter = context.getOutgoingSeqNum();
            int incomingAfter = context.getExpectedIncomingSeqNum();

            log.info("After: outgoing={}, expectedIncoming={}", outgoingAfter, incomingAfter);
            log.info("Heartbeats received: {}", heartbeatCount.get());

            // Verify outgoing seqnum advanced by MESSAGE_COUNT (one per TestRequest)
            int outgoingDelta = outgoingAfter - outgoingBefore;
            if (outgoingDelta != MESSAGE_COUNT) {
                return TestResult.failed(getName(),
                        String.format("Outgoing seqnum delta incorrect: expected=%d, actual=%d",
                                MESSAGE_COUNT, outgoingDelta),
                        System.currentTimeMillis() - startTime);
            }

            // Verify incoming seqnum advanced by at least MESSAGE_COUNT (one Heartbeat per TestRequest)
            int incomingDelta = incomingAfter - incomingBefore;
            if (incomingDelta < MESSAGE_COUNT) {
                return TestResult.failed(getName(),
                        String.format("Incoming seqnum delta too low: expected>=%d, actual=%d",
                                MESSAGE_COUNT, incomingDelta),
                        System.currentTimeMillis() - startTime);
            }

            // Verify heartbeat count matches
            if (heartbeatCount.get() != MESSAGE_COUNT) {
                return TestResult.failed(getName(),
                        String.format("Heartbeat count mismatch: expected=%d, received=%d",
                                MESSAGE_COUNT, heartbeatCount.get()),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("Exchanged %d TestRequest/Heartbeat pairs — " +
                                    "outgoing: %d->%d (+%d), incoming: %d->%d (+%d), " +
                                    "heartbeats: %d",
                            MESSAGE_COUNT,
                            outgoingBefore, outgoingAfter, outgoingDelta,
                            incomingBefore, incomingAfter, incomingDelta,
                            heartbeatCount.get()),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
