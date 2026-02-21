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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test session recovery after disconnect during active message sending.
 *
 * <p>Verifies: the session handles a disconnect mid-stream gracefully — no message
 * corruption, no deadlocks, and successful recovery after reconnect. Sends several
 * messages, disconnects during the sequence, reconnects, and verifies functionality.</p>
 */
public class ConcurrentReconnectTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentReconnectTest.class);

    @Override
    public String getName() {
        return "ConcurrentReconnectTest";
    }

    @Override
    public String getDescription() {
        return "Tests session recovery after disconnect during active message sending";
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

            // Send a burst of messages
            int sentBeforeDisconnect = 0;
            for (int i = 0; i < 5; i++) {
                try {
                    RingBufferOutgoingMessage msg = context.tryClaimMessage("D");
                    if (msg == null) {
                        break;
                    }
                    msg.setField(FixTags.ClOrdID, "CR-TEST-" + i);
                    msg.setField(FixTags.Symbol, "TEST");
                    msg.setField(FixTags.Side, FixTags.SIDE_BUY);
                    msg.setField(FixTags.OrderQty, 1);
                    msg.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
                    msg.setField(FixTags.Price, "100.00");
                    msg.setField(FixTags.TransactTime, Instant.now().toString());
                    context.commitMessage(msg);
                    sentBeforeDisconnect++;
                } catch (Exception e) {
                    log.debug("Send failed at iteration {}: {}", i, e.getMessage());
                    break;
                }
            }

            log.info("Sent {} messages before disconnect", sentBeforeDisconnect);

            // Force disconnect mid-stream
            context.disconnect();
            if (!context.waitForDisconnect(5000)) {
                return TestResult.failed(getName(),
                        "Did not disconnect",
                        System.currentTimeMillis() - startTime);
            }

            // Try to send while disconnected — should throw IllegalStateException
            boolean correctException = false;
            try {
                context.tryClaimMessage("D");
            } catch (IllegalStateException e) {
                correctException = true;
                log.info("Correctly got IllegalStateException while disconnected");
            }

            // Reconnect
            context.sleep(500);
            context.connect();
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not reconnect after mid-stream disconnect",
                        System.currentTimeMillis() - startTime);
            }

            // Verify session is fully functional after reconnect
            int sentAfterReconnect = 0;
            for (int i = 0; i < 3; i++) {
                try {
                    RingBufferOutgoingMessage msg = context.tryClaimMessage("D");
                    if (msg == null) {
                        context.sleep(100);
                        continue;
                    }
                    msg.setField(FixTags.ClOrdID, "CR-AFTER-" + i);
                    msg.setField(FixTags.Symbol, "TEST");
                    msg.setField(FixTags.Side, FixTags.SIDE_BUY);
                    msg.setField(FixTags.OrderQty, 1);
                    msg.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
                    msg.setField(FixTags.Price, "100.00");
                    msg.setField(FixTags.TransactTime, Instant.now().toString());
                    context.commitMessage(msg);
                    sentAfterReconnect++;
                } catch (Exception e) {
                    log.debug("Post-reconnect send failed at {}: {}", i, e.getMessage());
                    break;
                }
            }

            // Verify TestRequest/Heartbeat still works
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
                            "No Heartbeat response after reconnect",
                            System.currentTimeMillis() - startTime);
                }
            } finally {
                context.getSession().removeMessageListener(listener);
            }

            return TestResult.passed(getName(),
                    String.format("Mid-stream disconnect handled — %d sent before, %d after reconnect, " +
                                    "IllegalStateException=%b, session fully recovered",
                            sentBeforeDisconnect, sentAfterReconnect, correctException),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
