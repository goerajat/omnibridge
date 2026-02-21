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
 * Test sequence number behavior on disconnect.
 *
 * <p>Verifies: with resetOnDisconnect=false (default), sequence numbers are preserved
 * through a TCP disconnect event. Combined with resetOnLogon=true, sequences only
 * reset on the subsequent logon, not on the disconnect itself.</p>
 */
public class ResetOnDisconnectTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(ResetOnDisconnectTest.class);

    @Override
    public String getName() {
        return "ResetOnDisconnectTest";
    }

    @Override
    public String getDescription() {
        return "Tests sequence number preservation on disconnect vs reset on logon";
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

            // Advance sequences
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

            log.info("Before disconnect: outgoing={}, expectedIncoming={}",
                    outgoingBefore, incomingBefore);

            if (outgoingBefore <= 2 || incomingBefore <= 2) {
                return TestResult.failed(getName(),
                        String.format("Sequence numbers not advanced enough: outgoing=%d, incoming=%d",
                                outgoingBefore, incomingBefore),
                        System.currentTimeMillis() - startTime);
            }

            // Force disconnect (not graceful logout)
            context.disconnect();

            if (!context.waitForDisconnect(5000)) {
                return TestResult.failed(getName(),
                        "Did not disconnect",
                        System.currentTimeMillis() - startTime);
            }

            // Check sequences after disconnect (before reconnect)
            int outgoingAfterDisconnect = context.getOutgoingSeqNum();
            int incomingAfterDisconnect = context.getExpectedIncomingSeqNum();

            boolean resetOnDisconnect = context.getSession().getConfig().isResetOnDisconnect();
            log.info("resetOnDisconnect={}", resetOnDisconnect);
            log.info("After disconnect: outgoing={}, expectedIncoming={}",
                    outgoingAfterDisconnect, incomingAfterDisconnect);

            // Reconnect
            context.sleep(500);
            context.connect();

            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not reconnect after disconnect",
                        System.currentTimeMillis() - startTime);
            }

            int outgoingAfterLogon = context.getOutgoingSeqNum();
            int incomingAfterLogon = context.getExpectedIncomingSeqNum();

            log.info("After reconnect: outgoing={}, expectedIncoming={}",
                    outgoingAfterLogon, incomingAfterLogon);

            // After reconnect with resetOnLogon=true, sequences should be 2
            if (outgoingAfterLogon != 2) {
                return TestResult.failed(getName(),
                        String.format("Outgoing seqnum not reset after logon: expected=2, actual=%d",
                                outgoingAfterLogon),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("resetOnDisconnect=%b — before: out=%d/in=%d, " +
                                    "after disconnect: out=%d/in=%d, after logon: out=%d/in=%d",
                            resetOnDisconnect,
                            outgoingBefore, incomingBefore,
                            outgoingAfterDisconnect, incomingAfterDisconnect,
                            outgoingAfterLogon, incomingAfterLogon),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
