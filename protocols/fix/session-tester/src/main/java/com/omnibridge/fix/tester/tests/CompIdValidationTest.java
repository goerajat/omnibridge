package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test CompID validation on the active session.
 *
 * <p>Verifies: the session has correct SenderCompID and TargetCompID configured,
 * and that incoming messages from the acceptor contain the expected CompIDs.
 * The acceptor's SenderCompID should match our TargetCompID and vice versa.</p>
 */
public class CompIdValidationTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(CompIdValidationTest.class);

    @Override
    public String getName() {
        return "CompIdValidationTest";
    }

    @Override
    public String getDescription() {
        return "Tests that session CompIDs are correctly configured and validated";
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

            // Verify session config has CompIDs set
            String senderCompId = context.getSession().getConfig().getSenderCompId();
            String targetCompId = context.getSession().getConfig().getTargetCompId();

            log.info("Session CompIDs: SenderCompID={}, TargetCompID={}", senderCompId, targetCompId);

            if (senderCompId == null || senderCompId.isEmpty()) {
                return TestResult.failed(getName(),
                        "SenderCompID is not configured",
                        System.currentTimeMillis() - startTime);
            }

            if (targetCompId == null || targetCompId.isEmpty()) {
                return TestResult.failed(getName(),
                        "TargetCompID is not configured",
                        System.currentTimeMillis() - startTime);
            }

            // Verify incoming messages have correct CompIDs by triggering a heartbeat exchange
            CountDownLatch messageLatch = new CountDownLatch(1);
            final String[] incomingSenderCompId = {null};
            final String[] incomingTargetCompId = {null};

            MessageListener listener = (session, message) -> {
                if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                    // Extract CompIDs from incoming message
                    incomingSenderCompId[0] = message.getSenderCompId();
                    incomingTargetCompId[0] = message.getTargetCompId();
                    messageLatch.countDown();
                }
            };

            context.getSession().addMessageListener(listener);
            try {
                context.sendTestRequest();
                boolean received = messageLatch.await(10, TimeUnit.SECONDS);

                if (!received) {
                    return TestResult.failed(getName(),
                            "No Heartbeat received to validate CompIDs",
                            System.currentTimeMillis() - startTime);
                }

                log.info("Incoming message CompIDs: SenderCompID={}, TargetCompID={}",
                        incomingSenderCompId[0], incomingTargetCompId[0]);

                // The acceptor's SenderCompID should match our TargetCompID
                if (incomingSenderCompId[0] != null && !targetCompId.equals(incomingSenderCompId[0])) {
                    return TestResult.failed(getName(),
                            String.format("CompID mismatch — our TargetCompID=%s but acceptor SenderCompID=%s",
                                    targetCompId, incomingSenderCompId[0]),
                            System.currentTimeMillis() - startTime);
                }

                // The acceptor's TargetCompID should match our SenderCompID
                if (incomingTargetCompId[0] != null && !senderCompId.equals(incomingTargetCompId[0])) {
                    return TestResult.failed(getName(),
                            String.format("CompID mismatch — our SenderCompID=%s but acceptor TargetCompID=%s",
                                    senderCompId, incomingTargetCompId[0]),
                            System.currentTimeMillis() - startTime);
                }
            } finally {
                context.getSession().removeMessageListener(listener);
            }

            return TestResult.passed(getName(),
                    String.format("CompIDs validated — Sender=%s, Target=%s, " +
                                    "incoming SenderCompID=%s, incoming TargetCompID=%s",
                            senderCompId, targetCompId,
                            incomingSenderCompId[0], incomingTargetCompId[0]),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
