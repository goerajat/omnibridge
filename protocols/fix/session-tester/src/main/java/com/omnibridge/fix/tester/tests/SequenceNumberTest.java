package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;

/**
 * Test sequence number operations.
 * Verifies: get/set/reset sequence numbers.
 */
public class SequenceNumberTest implements SessionTest {

    @Override
    public String getName() {
        return "SequenceNumberTest";
    }

    @Override
    public String getDescription() {
        return "Tests sequence number get, set, and reset operations";
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

            // Test 1: Get current sequence numbers
            int initialOutSeq = context.getOutgoingSeqNum();
            int initialInSeq = context.getExpectedIncomingSeqNum();

            if (initialOutSeq < 1 || initialInSeq < 1) {
                return TestResult.failed(getName(),
                        String.format("Invalid initial sequence numbers: out=%d, in=%d",
                                initialOutSeq, initialInSeq),
                        System.currentTimeMillis() - startTime);
            }

            // Test 2: Set outgoing sequence number
            int newOutSeq = 100;
            context.setOutgoingSeqNum(newOutSeq);
            if (context.getOutgoingSeqNum() != newOutSeq) {
                return TestResult.failed(getName(),
                        String.format("Failed to set outgoing seq num: expected=%d, actual=%d",
                                newOutSeq, context.getOutgoingSeqNum()),
                        System.currentTimeMillis() - startTime);
            }

            // Test 3: Set incoming sequence number
            int newInSeq = 200;
            context.setExpectedIncomingSeqNum(newInSeq);
            if (context.getExpectedIncomingSeqNum() != newInSeq) {
                return TestResult.failed(getName(),
                        String.format("Failed to set incoming seq num: expected=%d, actual=%d",
                                newInSeq, context.getExpectedIncomingSeqNum()),
                        System.currentTimeMillis() - startTime);
            }

            // Test 4: Validate illegal sequence number (< 1)
            boolean exceptionThrown = false;
            try {
                context.setOutgoingSeqNum(0);
            } catch (IllegalArgumentException e) {
                exceptionThrown = true;
            }
            if (!exceptionThrown) {
                return TestResult.failed(getName(),
                        "Setting sequence number to 0 should throw IllegalArgumentException",
                        System.currentTimeMillis() - startTime);
            }

            exceptionThrown = false;
            try {
                context.setExpectedIncomingSeqNum(-1);
            } catch (IllegalArgumentException e) {
                exceptionThrown = true;
            }
            if (!exceptionThrown) {
                return TestResult.failed(getName(),
                        "Setting sequence number to -1 should throw IllegalArgumentException",
                        System.currentTimeMillis() - startTime);
            }

            // Test 5: Reset sequence numbers (locally) and verify
            context.resetSequenceNumbers();
            if (context.getOutgoingSeqNum() != 1 || context.getExpectedIncomingSeqNum() != 1) {
                return TestResult.failed(getName(),
                        String.format("Reset failed: out=%d (expected 1), in=%d (expected 1)",
                                context.getOutgoingSeqNum(), context.getExpectedIncomingSeqNum()),
                        System.currentTimeMillis() - startTime);
            }

            // Restore original sequence numbers so subsequent tests can continue
            // without needing to re-establish the session
            context.setOutgoingSeqNum(initialOutSeq);
            context.setExpectedIncomingSeqNum(initialInSeq);

            return TestResult.passed(getName(),
                    "All sequence number operations successful",
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
