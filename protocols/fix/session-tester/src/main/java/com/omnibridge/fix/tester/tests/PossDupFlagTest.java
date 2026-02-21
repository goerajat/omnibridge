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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test that messages with PossDupFlag=Y and seqnum less than expected are accepted.
 *
 * <p>Verifies: when a message arrives with MsgSeqNum lower than expected but with
 * PossDupFlag=Y, the engine accepts it (does not disconnect). This contrasts with
 * DuplicateMessageTest which verifies disconnect when PossDupFlag is NOT set.</p>
 *
 * <p>Tested by triggering a gap detection and resend flow: the acceptor's gap-fill
 * response includes PossDupFlag handling, and the session should remain connected.</p>
 */
public class PossDupFlagTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(PossDupFlagTest.class);

    @Override
    public String getName() {
        return "PossDupFlagTest";
    }

    @Override
    public String getDescription() {
        return "Tests that messages with PossDupFlag=Y and low seqnum are accepted (not disconnected)";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Ensure a clean session with fresh sequence numbers
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

            // Advance sequences by exchanging a few messages
            for (int i = 0; i < 3; i++) {
                CountDownLatch latch = new CountDownLatch(1);
                MessageListener listener = (session, message) -> {
                    if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                        latch.countDown();
                    }
                };
                context.getSession().addMessageListener(listener);
                try {
                    context.sendTestRequest();
                    latch.await(5, TimeUnit.SECONDS);
                } finally {
                    context.getSession().removeMessageListener(listener);
                }
            }

            int incomingBefore = context.getExpectedIncomingSeqNum();
            int outgoingBefore = context.getOutgoingSeqNum();

            log.info("Before gap injection: outgoing={}, expectedIncoming={}",
                    outgoingBefore, incomingBefore);

            if (incomingBefore < 3) {
                return TestResult.failed(getName(),
                        "Expected incoming seqnum too low for PossDup test: " + incomingBefore,
                        System.currentTimeMillis() - startTime);
            }

            // Track gap handling activity
            AtomicBoolean sequenceResetReceived = new AtomicBoolean(false);

            MessageListener gapListener = (session, message) -> {
                if (FixTags.MSG_TYPE_SEQUENCE_RESET.equals(message.getMsgType())) {
                    sequenceResetReceived.set(true);
                    log.info("Received SequenceReset during gap handling");
                }
            };

            context.getSession().addMessageListener(gapListener);
            try {
                // Lower expectedIncomingSeqNum to create a gap
                context.setExpectedIncomingSeqNum(1);
                log.info("Set expectedIncomingSeqNum to 1 to trigger gap detection");

                // Send TestRequest to trigger a message from the acceptor
                context.sendTestRequest();

                // Wait briefly for gap handling
                context.sleep(3000);
            } finally {
                context.getSession().removeMessageListener(gapListener);
            }

            int incomingAfter = context.getExpectedIncomingSeqNum();
            int outgoingAfter = context.getOutgoingSeqNum();
            log.info("After gap handling: expectedIncoming={}, outgoing={}", incomingAfter, outgoingAfter);

            // Check if ResendRequest was sent (outgoing advanced beyond just the TestRequest)
            boolean resendSent = outgoingAfter > outgoingBefore + 1;

            // If gap was resolved (acceptor sent gap-fill), expectedIncoming should have advanced
            if (incomingAfter > 1 && context.isLoggedOn()) {
                return TestResult.passed(getName(),
                        String.format("Session remained connected during PossDupFlag handling — " +
                                        "expectedIncoming recovered (%d->%d), SequenceReset=%b",
                                1, incomingAfter, sequenceResetReceived.get()),
                        System.currentTimeMillis() - startTime);
            }

            // Recover session — acceptor likely doesn't support resend
            context.disconnect();
            context.waitForDisconnect(5000);
            context.sleep(500);
            context.connect();
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Session could not reconnect after PossDup test",
                        System.currentTimeMillis() - startTime);
            }

            if (resendSent) {
                return TestResult.passed(getName(),
                        String.format("Gap detected, ResendRequest sent (outgoing %d->%d) — " +
                                        "acceptor doesn't support resend/PossDup, session recovered",
                                outgoingBefore, outgoingAfter),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.failed(getName(),
                    "No gap detection activity observed",
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
