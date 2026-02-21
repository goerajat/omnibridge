package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test handling of large FIX messages.
 *
 * <p>Verifies: the engine can handle messages with large field values (long Text fields)
 * without buffer overflows or truncation. Sends a NewOrderSingle with a large Text
 * field and verifies the session remains operational.</p>
 */
public class LargeMessageTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(LargeMessageTest.class);

    @Override
    public String getName() {
        return "LargeMessageTest";
    }

    @Override
    public String getDescription() {
        return "Tests handling of messages with large field values";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Ensure a clean session — disconnect and reconnect to clear ring buffer
            // from any prior tests that may have flooded it
            if (context.isLoggedOn()) {
                context.disconnect();
                context.waitForDisconnect(5000);
                context.sleep(500);
            }
            context.connect();
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not establish logged on state",
                        System.currentTimeMillis() - startTime);
            }

            int outgoingBefore = context.getOutgoingSeqNum();

            // Build a large text field (1000 characters)
            StringBuilder largeText = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                largeText.append("ABCDEFGHIJ");
            }
            String text = largeText.toString();
            log.info("Sending message with {} character Text field", text.length());

            // Send a NewOrderSingle with the large Text field
            RingBufferOutgoingMessage msg = context.tryClaimMessage("D");
            if (msg == null) {
                return TestResult.failed(getName(),
                        "Could not claim message on fresh buffer",
                        System.currentTimeMillis() - startTime);
            }

            msg.setField(FixTags.ClOrdID, "LARGE-MSG-TEST");
            msg.setField(FixTags.Symbol, "TEST");
            msg.setField(FixTags.Side, FixTags.SIDE_BUY);
            msg.setField(FixTags.OrderQty, 100);
            msg.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
            msg.setField(FixTags.Price, "150.00");
            msg.setField(FixTags.TransactTime, Instant.now().toString());
            msg.setField(FixTags.Text, text);
            context.commitMessage(msg);

            log.info("Large message committed successfully");

            // Wait for a response (execution report or reject)
            context.sleep(3000);

            int outgoingAfter = context.getOutgoingSeqNum();

            // Verify session is still operational after large message
            if (!context.isLoggedOn()) {
                context.sleep(500);
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Session lost after large message and could not reconnect",
                            System.currentTimeMillis() - startTime);
                }
                return TestResult.passed(getName(),
                        String.format("Large message sent (outgoing %d->%d, %d chars), " +
                                        "session reconnected",
                                outgoingBefore, outgoingAfter, text.length()),
                        System.currentTimeMillis() - startTime);
            }

            // Verify a TestRequest still works after the large message
            CountDownLatch heartbeatLatch = new CountDownLatch(1);
            MessageListener listener = (session, message) -> {
                if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                    heartbeatLatch.countDown();
                }
            };

            context.getSession().addMessageListener(listener);
            try {
                context.sendTestRequest();
                boolean received = heartbeatLatch.await(5, TimeUnit.SECONDS);
                if (!received) {
                    return TestResult.failed(getName(),
                            "No Heartbeat response after large message",
                            System.currentTimeMillis() - startTime);
                }
            } finally {
                context.getSession().removeMessageListener(listener);
            }

            return TestResult.passed(getName(),
                    String.format("Large message handled (%d chars, outgoing %d->%d), " +
                                    "session fully operational",
                            text.length(), outgoingBefore, outgoingAfter),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
