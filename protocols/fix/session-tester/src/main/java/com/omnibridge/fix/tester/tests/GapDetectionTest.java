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
 * Test sequence gap detection and ResendRequest.
 *
 * <p>Verifies: when our engine receives a message with MsgSeqNum higher than expected,
 * it detects the gap and sends a ResendRequest (35=2). The acceptor then responds with
 * a gap fill (SequenceReset with GapFillFlag=Y), and the session recovers.</p>
 *
 * <p>If the acceptor doesn't support resend, the test verifies that gap detection
 * was triggered (ResendRequest sent) and recovers the session via disconnect/reconnect.</p>
 */
public class GapDetectionTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(GapDetectionTest.class);

    @Override
    public String getName() {
        return "GapDetectionTest";
    }

    @Override
    public String getDescription() {
        return "Tests sequence gap detection triggers ResendRequest and session recovers via gap fill";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

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

            int outgoingBefore = context.getOutgoingSeqNum();
            int incomingBefore = context.getExpectedIncomingSeqNum();

            log.info("Before gap injection: outgoing={}, expectedIncoming={}",
                    outgoingBefore, incomingBefore);

            if (incomingBefore < 2) {
                return TestResult.failed(getName(),
                        "Expected incoming seqnum too low for gap test: " + incomingBefore,
                        System.currentTimeMillis() - startTime);
            }

            // Track whether a ResendRequest is sent by our engine
            AtomicBoolean resendRequestSent = new AtomicBoolean(false);
            AtomicBoolean sequenceResetReceived = new AtomicBoolean(false);

            MessageListener listener = (session, message) -> {
                String msgType = message.getMsgType();
                if (FixTags.MSG_TYPE_SEQUENCE_RESET.equals(msgType)) {
                    sequenceResetReceived.set(true);
                    log.info("Received SequenceReset/GapFill from acceptor");
                }
            };

            context.getSession().addMessageListener(listener);
            try {
                // Artificially create a gap by lowering expectedIncomingSeqNum
                context.setExpectedIncomingSeqNum(1);
                log.info("Set expectedIncomingSeqNum to 1 (actual acceptor seqNum is ~{})", incomingBefore);

                // Send TestRequest to trigger a Heartbeat from the acceptor
                context.sendTestRequest();
                log.info("Sent TestRequest to trigger gap detection");

                // Wait briefly for gap detection to fire
                context.sleep(3000);
            } finally {
                context.getSession().removeMessageListener(listener);
            }

            int outgoingAfter = context.getOutgoingSeqNum();
            int incomingAfter = context.getExpectedIncomingSeqNum();

            log.info("After gap handling: outgoing={}, expectedIncoming={}, seqResetReceived={}",
                    outgoingAfter, incomingAfter, sequenceResetReceived.get());

            // Verify ResendRequest was sent: outgoing seqnum should have increased
            // (TestRequest + at least one ResendRequest)
            boolean resendSent = outgoingAfter > outgoingBefore + 1;

            // Recover session via disconnect/reconnect (the gap loop won't resolve
            // if the acceptor doesn't support resend)
            if (incomingAfter <= 1 || !context.isLoggedOn()) {
                context.disconnect();
                context.waitForDisconnect(5000);
                context.sleep(500);
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Session could not reconnect after gap handling",
                            System.currentTimeMillis() - startTime);
                }
            }

            if (resendSent) {
                return TestResult.passed(getName(),
                        String.format("Gap detected and ResendRequest sent (outgoing %d->%d), " +
                                        "SequenceReset received=%b, session recovered",
                                outgoingBefore, outgoingAfter, sequenceResetReceived.get()),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.failed(getName(),
                    String.format("No ResendRequest detected: outgoing only advanced %d->%d",
                            outgoingBefore, outgoingAfter),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
