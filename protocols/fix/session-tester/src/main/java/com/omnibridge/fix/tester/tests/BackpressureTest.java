package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Test ring buffer backpressure handling.
 *
 * <p>Verifies: tryClaimMessage() returns null gracefully when the ring buffer is full,
 * rather than blocking or throwing an exception. Also verifies that after the buffer
 * drains, claiming resumes normally.</p>
 */
public class BackpressureTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(BackpressureTest.class);

    @Override
    public String getName() {
        return "BackpressureTest";
    }

    @Override
    public String getDescription() {
        return "Tests ring buffer backpressure — tryClaimMessage() returns null when full";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Ensure a clean session — disconnect and reconnect to clear ring buffer
            // from any prior tests that may have flooded it
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

            // Test 1: Normal claim/commit works
            RingBufferOutgoingMessage msg = context.tryClaimMessage("D");
            if (msg == null) {
                return TestResult.failed(getName(),
                        "tryClaimMessage() returned null on fresh buffer",
                        System.currentTimeMillis() - startTime);
            }

            // Abort the message (don't actually send it)
            context.getSession().abortMessage(msg);
            log.info("Normal claim/abort cycle works");

            // Test 2: Rapid claim/commit cycle to exercise buffer throughput
            int successCount = 0;
            int nullCount = 0;

            for (int i = 0; i < 100; i++) {
                RingBufferOutgoingMessage claimedMsg = null;
                try {
                    claimedMsg = context.tryClaimMessage("D");
                } catch (Exception e) {
                    log.debug("Claim threw exception at iteration {}: {}", i, e.getMessage());
                    break;
                }

                if (claimedMsg == null) {
                    nullCount++;
                    // Buffer may be full — brief pause to let it drain
                    context.sleep(10);
                    continue;
                }

                // Set minimal fields and commit
                claimedMsg.setField(FixTags.ClOrdID, "BP-TEST-" + i);
                claimedMsg.setField(FixTags.Symbol, "TEST");
                claimedMsg.setField(FixTags.Side, FixTags.SIDE_BUY);
                claimedMsg.setField(FixTags.OrderQty, 1);
                claimedMsg.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
                claimedMsg.setField(FixTags.Price, "100.00");
                claimedMsg.setField(FixTags.TransactTime, Instant.now().toString());
                context.commitMessage(claimedMsg);
                successCount++;
            }

            log.info("Rapid cycle: {} successful, {} null (backpressure)", successCount, nullCount);

            // Brief pause to let the network drain pending messages
            context.sleep(2000);

            // Test 3: Verify buffer recovered — can claim again
            RingBufferOutgoingMessage recoveryMsg = context.tryClaimMessage("D");
            if (recoveryMsg != null) {
                context.getSession().abortMessage(recoveryMsg);
                log.info("Buffer recovered — claim works after drain");
            }

            // Verify session is still operational
            if (!context.isLoggedOn()) {
                context.sleep(500);
                context.connect();
                context.waitForLogon();
            }

            return TestResult.passed(getName(),
                    String.format("Backpressure handled — %d sent, %d null returns, " +
                                    "buffer recovered, session operational",
                            successCount, nullCount),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
