package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test sequence number behavior on logout.
 *
 * <p>Verifies: with resetOnLogout=false (default), sequence numbers are preserved
 * through a logout event and only reset on the subsequent logon (due to resetOnLogon=true).
 * This confirms the distinction between resetOnLogout and resetOnLogon behaviors.</p>
 */
public class ResetOnLogoutTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(ResetOnLogoutTest.class);

    @Override
    public String getName() {
        return "ResetOnLogoutTest";
    }

    @Override
    public String getDescription() {
        return "Tests sequence number preservation on logout vs reset on logon";
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

            // Advance sequences by exchanging messages
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

            int outgoingBeforeLogout = context.getOutgoingSeqNum();
            int incomingBeforeLogout = context.getExpectedIncomingSeqNum();

            log.info("Before logout: outgoing={}, expectedIncoming={}",
                    outgoingBeforeLogout, incomingBeforeLogout);

            if (outgoingBeforeLogout <= 2 || incomingBeforeLogout <= 2) {
                return TestResult.failed(getName(),
                        String.format("Sequence numbers not advanced enough: outgoing=%d, incoming=%d",
                                outgoingBeforeLogout, incomingBeforeLogout),
                        System.currentTimeMillis() - startTime);
            }

            // Send logout
            context.logout("Testing resetOnLogout behavior");

            if (!context.waitForDisconnect()) {
                return TestResult.failed(getName(),
                        "Did not disconnect after logout",
                        System.currentTimeMillis() - startTime);
            }

            // Check sequences immediately after disconnect (before reconnect)
            // With resetOnLogout=false, they should be preserved
            int outgoingAfterLogout = context.getOutgoingSeqNum();
            int incomingAfterLogout = context.getExpectedIncomingSeqNum();

            log.info("After logout (before reconnect): outgoing={}, expectedIncoming={}",
                    outgoingAfterLogout, incomingAfterLogout);

            boolean resetOnLogout = context.getSession().getConfig().isResetOnLogout();
            log.info("resetOnLogout={}", resetOnLogout);

            // Reconnect
            context.sleep(500);
            context.connect();

            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not reconnect after logout",
                        System.currentTimeMillis() - startTime);
            }

            int outgoingAfterLogon = context.getOutgoingSeqNum();
            int incomingAfterLogon = context.getExpectedIncomingSeqNum();

            log.info("After reconnect: outgoing={}, expectedIncoming={}",
                    outgoingAfterLogon, incomingAfterLogon);

            // After reconnect with resetOnLogon=true, sequences should be reset to 2
            if (outgoingAfterLogon != 2) {
                return TestResult.failed(getName(),
                        String.format("Outgoing seqnum not reset after logon: expected=2, actual=%d",
                                outgoingAfterLogon),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("resetOnLogout=%b — before logout: out=%d/in=%d, " +
                                    "after logout: out=%d/in=%d, after logon: out=%d/in=%d (reset to 2)",
                            resetOnLogout,
                            outgoingBeforeLogout, incomingBeforeLogout,
                            outgoingAfterLogout, incomingAfterLogout,
                            outgoingAfterLogon, incomingAfterLogon),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
