package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test duplicate/old message detection without PossDupFlag.
 *
 * <p>Verifies: when a message arrives with MsgSeqNum lower than expected and
 * PossDupFlag is not set, the engine disconnects with "Sequence number too low".
 * This is the FIX protocol's defense against stale or replayed messages.</p>
 *
 * <p>Tests FixSession.java lines 519-528.</p>
 */
public class DuplicateMessageTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(DuplicateMessageTest.class);

    @Override
    public String getName() {
        return "DuplicateMessageTest";
    }

    @Override
    public String getDescription() {
        return "Tests disconnect on incoming message with seqnum lower than expected (no PossDupFlag)";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Ensure a clean session with low sequence numbers
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

            int incomingBefore = context.getExpectedIncomingSeqNum();
            log.info("Current expectedIncomingSeqNum: {}", incomingBefore);

            // Set expectedIncomingSeqNum very high so the next incoming message
            // has seqNum < expected, triggering the "Sequence number too low" path
            int highSeqNum = Math.max(10000, incomingBefore + 10000);
            context.setExpectedIncomingSeqNum(highSeqNum);
            log.info("Set expectedIncomingSeqNum to {}", highSeqNum);

            // Send TestRequest to trigger a Heartbeat from the acceptor
            // The Heartbeat will have the acceptor's actual seqNum (e.g., 5),
            // which is < 10000, and PossDupFlag is not set → disconnect
            context.sendTestRequest();
            log.info("Sent TestRequest — expecting disconnect due to low seqnum in response");

            // Wait for disconnect
            boolean disconnected = context.waitForDisconnect(10000);

            if (!disconnected) {
                // Session didn't disconnect — might still be logged on
                // This can happen if no message arrived from the acceptor yet
                log.warn("Session did not disconnect within timeout");

                // Restore and reconnect
                context.disconnect();
                context.waitForDisconnect(5000);
                context.sleep(500);
                context.connect();
                context.waitForLogon();
                return TestResult.failed(getName(),
                        "Session did not disconnect when receiving message with seqnum < expected",
                        System.currentTimeMillis() - startTime);
            }

            log.info("Session disconnected as expected (state={})", context.getState());

            // Verify state is DISCONNECTED
            if (context.getState() != SessionState.DISCONNECTED) {
                return TestResult.failed(getName(),
                        "Expected DISCONNECTED state, got: " + context.getState(),
                        System.currentTimeMillis() - startTime);
            }

            // Recover: reconnect (resetOnLogon=true will reset sequences)
            context.sleep(500);
            context.connect();
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not reconnect after duplicate message disconnect",
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("Session correctly disconnected on seqnum too low " +
                                    "(expected=%d, actual=~%d), recovered after reconnect",
                            highSeqNum, incomingBefore),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
