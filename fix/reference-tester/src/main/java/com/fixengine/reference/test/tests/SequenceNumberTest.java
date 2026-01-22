package com.fixengine.reference.test.tests;

import com.fixengine.reference.initiator.ReferenceInitiator;
import com.fixengine.reference.test.ReferenceTest;
import com.fixengine.reference.test.TestContext;
import com.fixengine.reference.test.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Tests sequence number handling.
 */
public class SequenceNumberTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(SequenceNumberTest.class);

    @Override
    public String getName() {
        return "SequenceNumberTest";
    }

    @Override
    public String getDescription() {
        return "Tests sequence number tracking and increment behavior";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");

            // Get initial sequence numbers
            int initialSenderSeq = initiator.getExpectedSenderNum();
            int initialTargetSeq = initiator.getExpectedTargetNum();

            context.log("Initial sequence numbers - Sender: " + initialSenderSeq + ", Target: " + initialTargetSeq);

            // Verify sequence numbers are positive
            context.assertTrue(initialSenderSeq > 0, "Sender sequence should be positive");
            context.assertTrue(initialTargetSeq > 0, "Target sequence should be positive");

            // Send a test request (this will increment sender seq)
            String testReqId = initiator.sendTestRequest();
            context.assertNotNull(testReqId, "TestRequest ID should not be null");
            context.log("Sent TestRequest: " + testReqId);

            // Wait for response
            context.sleep(2000);

            // Check sequence numbers again
            int afterSenderSeq = initiator.getExpectedSenderNum();
            int afterTargetSeq = initiator.getExpectedTargetNum();

            context.log("After TestRequest - Sender: " + afterSenderSeq + ", Target: " + afterTargetSeq);

            // Sender sequence should have increased (we sent a message)
            context.assertTrue(afterSenderSeq > initialSenderSeq,
                    "Sender sequence should have increased after sending message");

            // Target sequence should have increased (we received heartbeat response)
            context.assertTrue(afterTargetSeq > initialTargetSeq,
                    "Target sequence should have increased after receiving response");

            return TestResult.builder(getName())
                    .passed("Sequence numbers tracking correctly - incremented as expected")
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
