package com.fixengine.reference.test.tests;

import com.fixengine.reference.initiator.ReferenceInitiator;
import com.fixengine.reference.test.ReferenceTest;
import com.fixengine.reference.test.TestContext;
import com.fixengine.reference.test.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Tests heartbeat functionality.
 */
public class HeartbeatTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatTest.class);

    @Override
    public String getName() {
        return "HeartbeatTest";
    }

    @Override
    public String getDescription() {
        return "Tests FIX heartbeat mechanism by sending TestRequest and waiting for Heartbeat response";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");

            // Record sequence numbers before test
            int seqBefore = initiator.getExpectedTargetNum();
            context.log("Sequence number before TestRequest: " + seqBefore);

            // Send test request
            String testReqId = initiator.sendTestRequest();
            context.assertNotNull(testReqId, "TestRequest ID should not be null");
            context.log("Sent TestRequest: " + testReqId);

            // Wait a bit for heartbeat response
            context.sleep(3000);

            // Verify sequence number increased (heartbeat received)
            int seqAfter = initiator.getExpectedTargetNum();
            context.log("Sequence number after TestRequest: " + seqAfter);

            context.assertTrue(seqAfter > seqBefore,
                    "Sequence number should increase after heartbeat response");

            // Verify session is still connected
            context.assertTrue(initiator.isLoggedOn(), "Should still be logged on");

            return TestResult.builder(getName())
                    .passed("Heartbeat test passed - received response to TestRequest")
                    .startTime(startTime)
                    .build();

        } catch (AssertionError e) {
            return TestResult.builder(getName())
                    .failed(e.getMessage())
                    .startTime(startTime)
                    .build();
        } catch (Exception e) {
            return TestResult.builder(getName())
                    .error("Exception: " + e.getMessage(), e)
                    .startTime(startTime)
                    .build();
        }
    }
}
