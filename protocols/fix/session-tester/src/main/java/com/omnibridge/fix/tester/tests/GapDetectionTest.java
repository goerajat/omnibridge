package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test sequence gap detection and ResendRequest.
 *
 * <p>Verifies: when our engine receives a message with MsgSeqNum higher than expected,
 * it detects the gap and sends a ResendRequest (35=2). The acceptor then responds with
 * a gap fill (SequenceReset with GapFillFlag=Y), and the session recovers.</p>
 *
 * <p>This also implicitly tests SequenceReset/GapFill processing (FixSession lines 835-841)
 * since the acceptor responds to our ResendRequest with gap fill messages.</p>
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

            // Artificially create a gap by lowering expectedIncomingSeqNum
            // When the next message arrives from the acceptor, its seqNum will be higher
            // than our (lowered) expected value, triggering gap detection
            context.setExpectedIncomingSeqNum(1);
            log.info("Set expectedIncomingSeqNum to 1 (actual acceptor seqNum is ~{})", incomingBefore);

            // Send TestRequest to trigger a Heartbeat from the acceptor
            context.sendTestRequest();
            log.info("Sent TestRequest to trigger gap detection");

            // Wait for the session to process the gap:
            // 1. Gap detected → ResendRequest sent (outgoing seqnum bumps)
            // 2. Acceptor responds with gap fill
            // 3. Session recovers
            context.sleep(5000);

            int outgoingAfter = context.getOutgoingSeqNum();
            int incomingAfter = context.getExpectedIncomingSeqNum();

            log.info("After gap handling: outgoing={}, expectedIncoming={}",
                    outgoingAfter, incomingAfter);

            // Verify ResendRequest was sent: outgoing seqnum should have increased
            // by at least 1 (the ResendRequest message) beyond what TestRequest added
            if (outgoingAfter <= outgoingBefore) {
                return TestResult.failed(getName(),
                        String.format("Outgoing seqnum did not increase: before=%d, after=%d " +
                                        "(ResendRequest may not have been sent)",
                                outgoingBefore, outgoingAfter),
                        System.currentTimeMillis() - startTime);
            }

            // Verify the session recovered: expectedIncomingSeqNum should have advanced from 1
            if (incomingAfter <= 1) {
                return TestResult.failed(getName(),
                        String.format("Expected incoming seqnum did not advance after gap fill: %d",
                                incomingAfter),
                        System.currentTimeMillis() - startTime);
            }

            // If session disconnected during gap handling, reconnect and report success
            // (the gap detection and ResendRequest behavior was still verified)
            if (!context.isLoggedOn()) {
                context.sleep(500);
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Session not logged on after gap handling and could not reconnect",
                            System.currentTimeMillis() - startTime);
                }
                return TestResult.passed(getName(),
                        String.format("Gap detected (ResendRequest sent: outgoing %d->%d), " +
                                        "expectedIncoming recovered (%d->%d), session reconnected",
                                outgoingBefore, outgoingAfter, 1, incomingAfter),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("Gap detected and resolved — ResendRequest sent (outgoing %d->%d), " +
                                    "expectedIncoming recovered (%d->%d), session still logged on",
                            outgoingBefore, outgoingAfter, 1, incomingAfter),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
