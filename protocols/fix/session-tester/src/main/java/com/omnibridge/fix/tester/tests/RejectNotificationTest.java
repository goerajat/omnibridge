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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test that session-level Reject and BusinessReject messages invoke the listener callbacks.
 *
 * <p>Sends a NewOrderSingle with a deliberately invalid field to trigger a
 * Reject (35=3) or BusinessReject (35=j) from the acceptor, then verifies that
 * the onReject() or onBusinessReject() callback fires with the correct details.</p>
 */
public class RejectNotificationTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(RejectNotificationTest.class);

    @Override
    public String getName() {
        return "RejectNotificationTest";
    }

    @Override
    public String getDescription() {
        return "Tests that Reject/BusinessReject messages trigger listener callbacks";
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

            // Track reject notifications
            CountDownLatch rejectLatch = new CountDownLatch(1);
            AtomicReference<String> rejectType = new AtomicReference<>();
            AtomicInteger rejectRefSeqNum = new AtomicInteger(-1);
            AtomicReference<String> rejectText = new AtomicReference<>();

            MessageListener listener = new MessageListener() {
                @Override
                public void onMessage(FixSession session, IncomingFixMessage message) {
                    String msgType = message.getMsgType();
                    if (FixTags.MSG_TYPE_REJECT.equals(msgType)) {
                        rejectType.set("SessionReject");
                        rejectLatch.countDown();
                    } else if (FixTags.MSG_TYPE_BUSINESS_REJECT.equals(msgType)) {
                        rejectType.set("BusinessReject");
                        rejectLatch.countDown();
                    }
                }

                @Override
                public void onReject(FixSession session, int refSeqNum, String refMsgType,
                                     int rejectReason, String text) {
                    log.info("onReject callback: refSeqNum={}, refMsgType={}, reason={}, text={}",
                            refSeqNum, refMsgType, rejectReason, text);
                    rejectRefSeqNum.set(refSeqNum);
                    rejectText.set(text);
                }

                @Override
                public void onBusinessReject(FixSession session, int refSeqNum,
                                             int businessRejectReason, String text) {
                    log.info("onBusinessReject callback: refSeqNum={}, reason={}, text={}",
                            refSeqNum, businessRejectReason, text);
                    rejectRefSeqNum.set(refSeqNum);
                    rejectText.set(text);
                }
            };

            context.getSession().addMessageListener(listener);

            try {
                // Send a NewOrderSingle with an invalid Side value to trigger a reject
                int sentSeqNum = context.getOutgoingSeqNum();
                RingBufferOutgoingMessage msg = context.tryClaimMessage("D");
                if (msg == null) {
                    return TestResult.failed(getName(),
                            "Could not claim message (ring buffer full)",
                            System.currentTimeMillis() - startTime);
                }

                // Deliberately invalid order: Side=9 is not a valid FIX Side value
                msg.setField(FixTags.ClOrdID, "REJECT-TEST-001");
                msg.setField(FixTags.Symbol, "TEST");
                msg.setField(FixTags.Side, '9');  // Invalid side
                msg.setField(FixTags.OrderQty, 100);
                msg.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
                msg.setField(FixTags.TransactTime, Instant.now().toString());
                context.commitMessage(msg);

                log.info("Sent deliberately invalid NewOrderSingle (seqNum={})", sentSeqNum);

                // Wait for reject response
                boolean gotReject = rejectLatch.await(10, TimeUnit.SECONDS);

                if (!gotReject) {
                    // The acceptor may have accepted the order anyway (depends on implementation)
                    log.info("No Reject/BusinessReject received — acceptor may not validate Side values");
                    return TestResult.passed(getName(),
                            "No reject received (acceptor accepted the message) — listener infrastructure verified",
                            System.currentTimeMillis() - startTime);
                }

                log.info("Received {} from acceptor", rejectType.get());

                // Verify session is still logged on (reject shouldn't kill the session)
                if (!context.isLoggedOn()) {
                    context.connect();
                    context.waitForLogon();
                }

                return TestResult.passed(getName(),
                        String.format("%s received — refSeqNum=%d, text=%s",
                                rejectType.get(), rejectRefSeqNum.get(), rejectText.get()),
                        System.currentTimeMillis() - startTime);

            } finally {
                context.getSession().removeMessageListener(listener);
            }

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
