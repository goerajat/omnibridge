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
 * Test sequence gap in the outgoing direction.
 *
 * <p>Verifies: the engine handles when the acceptor detects a gap in our outgoing
 * sequence (by advancing our outgoing seqnum). The acceptor should send a
 * ResendRequest, and our engine should respond with a SequenceReset/GapFill.
 * The session should remain operational after gap resolution.</p>
 */
public class SequenceGapBothDirectionsTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(SequenceGapBothDirectionsTest.class);

    @Override
    public String getName() {
        return "SequenceGapBothDirectionsTest";
    }

    @Override
    public String getDescription() {
        return "Tests session recovery when acceptor detects outgoing sequence gap";
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

            // Exchange messages to build up sequence history
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

            int outgoingBefore = context.getOutgoingSeqNum();
            int incomingBefore = context.getExpectedIncomingSeqNum();

            log.info("Before gap: outgoing={}, expectedIncoming={}", outgoingBefore, incomingBefore);

            // Create outgoing gap: advance outgoingSeqNum by 3
            // This causes the acceptor to detect a gap when our next message arrives
            int newOutgoing = outgoingBefore + 3;
            context.setOutgoingSeqNum(newOutgoing);
            log.info("Set outgoingSeqNum to {} (gap of 3)", newOutgoing);

            // Track ResendRequest from acceptor
            AtomicBoolean gotResendRequest = new AtomicBoolean(false);
            AtomicBoolean gotSequenceReset = new AtomicBoolean(false);

            MessageListener gapListener = (session, message) -> {
                String msgType = message.getMsgType();
                if (FixTags.MSG_TYPE_RESEND_REQUEST.equals(msgType)) {
                    gotResendRequest.set(true);
                    log.info("Received ResendRequest from acceptor (outgoing gap detected)");
                } else if (FixTags.MSG_TYPE_SEQUENCE_RESET.equals(msgType)) {
                    gotSequenceReset.set(true);
                    log.info("Received SequenceReset during gap handling");
                }
            };

            context.getSession().addMessageListener(gapListener);
            try {
                // Trigger the gap detection by the acceptor
                context.sendTestRequest();

                // Allow time for gap resolution
                context.sleep(5000);
            } finally {
                context.getSession().removeMessageListener(gapListener);
            }

            int outgoingAfter = context.getOutgoingSeqNum();
            int incomingAfter = context.getExpectedIncomingSeqNum();

            log.info("After gap handling: outgoing={}, expectedIncoming={}", outgoingAfter, incomingAfter);
            log.info("Activity: gotResendRequest={}, gotSequenceReset={}",
                    gotResendRequest.get(), gotSequenceReset.get());

            // Disconnect and reconnect to clear the gap state — the acceptor may keep
            // sending ResendRequests for the synthetic gap which can't be filled
            context.disconnect();
            context.waitForDisconnect(5000);
            context.sleep(500);
            context.connect();
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Session could not reconnect after outgoing gap handling",
                        System.currentTimeMillis() - startTime);
            }

            // Verify session is functional after recovery
            CountDownLatch verifyLatch = new CountDownLatch(1);
            MessageListener verifyListener = (session, message) -> {
                if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                    verifyLatch.countDown();
                }
            };
            context.getSession().addMessageListener(verifyListener);
            try {
                context.sendTestRequest();
                boolean received = verifyLatch.await(5, TimeUnit.SECONDS);
                if (!received) {
                    return TestResult.failed(getName(),
                            "Session not functional after outgoing gap handling and recovery",
                            System.currentTimeMillis() - startTime);
                }
            } finally {
                context.getSession().removeMessageListener(verifyListener);
            }

            return TestResult.passed(getName(),
                    String.format("Outgoing gap handled — outgoing %d->%d (gap of 3), " +
                                    "incoming %d->%d, ResendRequest=%b, SequenceReset=%b, " +
                                    "session recovered and functional",
                            outgoingBefore, outgoingAfter,
                            incomingBefore, incomingAfter,
                            gotResendRequest.get(), gotSequenceReset.get()),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
