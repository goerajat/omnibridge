package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Test outgoing sequence number increment correctness.
 *
 * <p>Validates: the outgoing sequence number increments correctly when sending
 * multiple application messages. Sends 5 NewOrderSingle messages via
 * claim+set+commit and verifies the outgoing seqnum increased by exactly 5.</p>
 */
public class OutgoingMessageCountTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(OutgoingMessageCountTest.class);

    private static final int MESSAGE_COUNT = 5;

    @Override
    public String getName() {
        return "OutgoingMessageCountTest";
    }

    @Override
    public String getDescription() {
        return "Validates outgoing sequence number increments correctly for " + MESSAGE_COUNT + " sent messages";
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

            int seqNumBefore = context.getOutgoingSeqNum();
            log.info("Outgoing seqnum before sending {} messages: {}", MESSAGE_COUNT, seqNumBefore);

            // Send MESSAGE_COUNT NewOrderSingle messages
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                RingBufferOutgoingMessage msg = context.tryClaimMessage("D");
                if (msg == null) {
                    return TestResult.failed(getName(),
                            String.format("Could not claim message %d/%d from ring buffer",
                                    i + 1, MESSAGE_COUNT),
                            System.currentTimeMillis() - startTime);
                }

                msg.setField(FixTags.ClOrdID, "SEQCOUNT-" + (i + 1));
                msg.setField(FixTags.Symbol, "TEST");
                msg.setField(FixTags.Side, FixTags.SIDE_BUY);
                msg.setField(FixTags.OrderQty, 100);
                msg.setField(FixTags.OrdType, FixTags.ORD_TYPE_MARKET);
                msg.setField(FixTags.TransactTime, Instant.now().toString());
                context.commitMessage(msg);
            }

            // Allow messages to be sent
            context.sleep(1000);

            int seqNumAfter = context.getOutgoingSeqNum();
            int delta = seqNumAfter - seqNumBefore;

            log.info("Outgoing seqnum after sending: {} (delta={})", seqNumAfter, delta);

            if (delta != MESSAGE_COUNT) {
                return TestResult.failed(getName(),
                        String.format("Outgoing seqnum delta incorrect: expected=%d, actual=%d (before=%d, after=%d)",
                                MESSAGE_COUNT, delta, seqNumBefore, seqNumAfter),
                        System.currentTimeMillis() - startTime);
            }

            // Verify session is still operational
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Session lost after sending messages",
                            System.currentTimeMillis() - startTime);
                }
            }

            return TestResult.passed(getName(),
                    String.format("Outgoing seqnum correctly incremented by %d (%d -> %d) after sending %d messages",
                            delta, seqNumBefore, seqNumAfter, MESSAGE_COUNT),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
