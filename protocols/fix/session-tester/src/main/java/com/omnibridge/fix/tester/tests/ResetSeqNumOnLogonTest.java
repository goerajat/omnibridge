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
 * Test that sequence numbers reset correctly on logon with ResetSeqNumFlag=Y.
 *
 * <p>Verifies: after exchanging messages to advance sequence numbers, a logout and
 * reconnect (with resetOnLogon=true) resets both outgoing and expected incoming
 * sequence numbers.</p>
 */
public class ResetSeqNumOnLogonTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(ResetSeqNumOnLogonTest.class);

    @Override
    public String getName() {
        return "ResetSeqNumOnLogonTest";
    }

    @Override
    public String getDescription() {
        return "Tests sequence number reset on logon with ResetSeqNumFlag=Y";
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

            // Send a few TestRequests to advance sequence numbers
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

            log.info("Before reconnect: outgoing={}, expectedIncoming={}",
                    outgoingBefore, incomingBefore);

            if (outgoingBefore <= 2 || incomingBefore <= 2) {
                return TestResult.failed(getName(),
                        String.format("Sequence numbers not advanced enough: outgoing=%d, incoming=%d",
                                outgoingBefore, incomingBefore),
                        System.currentTimeMillis() - startTime);
            }

            // Logout and reconnect (resetOnLogon=true is configured in SessionTester)
            context.logout("Testing sequence reset");
            if (!context.waitForDisconnect()) {
                return TestResult.failed(getName(),
                        "Did not disconnect after logout",
                        System.currentTimeMillis() - startTime);
            }

            context.sleep(500);
            context.connect();

            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Did not logon after reconnect",
                        System.currentTimeMillis() - startTime);
            }

            int outgoingAfter = context.getOutgoingSeqNum();
            int incomingAfter = context.getExpectedIncomingSeqNum();

            log.info("After reconnect: outgoing={}, expectedIncoming={}",
                    outgoingAfter, incomingAfter);

            // After reset: outgoing should be 2 (logon was seq 1, next is 2)
            // expectedIncoming should be 2 (received logon response seq 1, next expected is 2)
            if (outgoingAfter != 2) {
                return TestResult.failed(getName(),
                        String.format("Outgoing seqnum not reset: expected=2, actual=%d", outgoingAfter),
                        System.currentTimeMillis() - startTime);
            }

            if (incomingAfter != 2) {
                return TestResult.failed(getName(),
                        String.format("Expected incoming seqnum not reset: expected=2, actual=%d", incomingAfter),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("Sequence numbers reset correctly on logon (before: out=%d/in=%d, after: out=%d/in=%d)",
                            outgoingBefore, incomingBefore, outgoingAfter, incomingAfter),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
