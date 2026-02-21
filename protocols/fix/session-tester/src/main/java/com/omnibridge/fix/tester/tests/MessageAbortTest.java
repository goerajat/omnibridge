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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test message abort/rollback mechanics.
 *
 * <p>Validates: abortMessage() correctly rolls back the sequence number and ring
 * buffer claim. After aborting a claimed message, the sequence number should
 * return to its original value, and subsequent claim+commit should succeed
 * normally with the correct sequence number.</p>
 */
public class MessageAbortTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(MessageAbortTest.class);

    @Override
    public String getName() {
        return "MessageAbortTest";
    }

    @Override
    public String getDescription() {
        return "Validates abortMessage() rolls back sequence number and ring buffer claim";
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

            // Record sequence number before
            int seqNumBefore = context.getOutgoingSeqNum();
            log.info("Outgoing seqnum before abort test: {}", seqNumBefore);

            // Claim a message (this increments the sequence number)
            RingBufferOutgoingMessage msg = context.tryClaimMessage("D");
            if (msg == null) {
                return TestResult.failed(getName(),
                        "Could not claim message from ring buffer",
                        System.currentTimeMillis() - startTime);
            }

            int seqNumAfterClaim = context.getOutgoingSeqNum();
            log.info("Outgoing seqnum after claim: {}", seqNumAfterClaim);

            // Abort the message (should rollback sequence number)
            context.getSession().abortMessage(msg);

            int seqNumAfterAbort = context.getOutgoingSeqNum();
            log.info("Outgoing seqnum after abort: {}", seqNumAfterAbort);

            if (seqNumAfterAbort != seqNumBefore) {
                return TestResult.failed(getName(),
                        String.format("Seqnum not rolled back after abort: before=%d, afterAbort=%d",
                                seqNumBefore, seqNumAfterAbort),
                        System.currentTimeMillis() - startTime);
            }

            // Now verify the session still works by sending a TestRequest
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
                            "No Heartbeat response after abort — session may be corrupted",
                            System.currentTimeMillis() - startTime);
                }
            } finally {
                context.getSession().removeMessageListener(listener);
            }

            int seqNumAfterTestReq = context.getOutgoingSeqNum();
            log.info("Outgoing seqnum after TestRequest: {}", seqNumAfterTestReq);

            // After abort + one TestRequest, seqnum should be original + 1
            if (seqNumAfterTestReq != seqNumBefore + 1) {
                return TestResult.failed(getName(),
                        String.format("Seqnum incorrect after abort+send: expected=%d, actual=%d",
                                seqNumBefore + 1, seqNumAfterTestReq),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("abortMessage() correctly rolled back seqnum (%d -> %d -> %d) " +
                                    "and session continued normally (seqnum now %d)",
                            seqNumBefore, seqNumAfterClaim, seqNumAfterAbort, seqNumAfterTestReq),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
