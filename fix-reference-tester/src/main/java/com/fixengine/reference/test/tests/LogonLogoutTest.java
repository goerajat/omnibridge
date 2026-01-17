package com.fixengine.reference.test.tests;

import com.fixengine.reference.initiator.ReferenceInitiator;
import com.fixengine.reference.test.ReferenceTest;
import com.fixengine.reference.test.TestContext;
import com.fixengine.reference.test.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Tests logon and logout functionality.
 */
public class LogonLogoutTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(LogonLogoutTest.class);

    @Override
    public String getName() {
        return "LogonLogoutTest";
    }

    @Override
    public String getDescription() {
        return "Tests FIX session logon and logout sequence";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");
            context.log("Verified session is logged on");

            // Request logout
            context.log("Requesting logout...");
            initiator.logout();

            // Wait for logout
            boolean loggedOut = context.waitForLogout(initiator, 10000);
            context.assertTrue(loggedOut, "Should have logged out");
            context.log("Logout confirmed");

            // Verify sequence numbers are reasonable
            int senderSeq = initiator.getExpectedSenderNum();
            int targetSeq = initiator.getExpectedTargetNum();
            context.log("Sequence numbers after logout - Sender: " + senderSeq + ", Target: " + targetSeq);

            return TestResult.builder(getName())
                    .passed("Logon and logout completed successfully")
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
