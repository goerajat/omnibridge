package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;

/**
 * Test logon and logout functionality.
 * Verifies: logout, disconnect verification, reconnect, logon verification.
 */
public class LogonLogoutTest implements SessionTest {

    @Override
    public String getName() {
        return "LogonLogoutTest";
    }

    @Override
    public String getDescription() {
        return "Tests session logout, disconnect, reconnect, and logon sequence";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Verify initial logged on state
            if (!context.isLoggedOn()) {
                // Try to connect first
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Session not logged on initially and could not connect",
                            System.currentTimeMillis() - startTime);
                }
            }

            // Step 1: Logout
            context.logout("Test logout");

            // Step 2: Wait for disconnect
            if (!context.waitForDisconnect()) {
                return TestResult.failed(getName(),
                        "Session did not disconnect after logout",
                        System.currentTimeMillis() - startTime);
            }

            // Verify disconnected state
            if (context.isConnected()) {
                return TestResult.failed(getName(),
                        "Session still shows as connected after logout",
                        System.currentTimeMillis() - startTime);
            }

            // Step 3: Reconnect
            context.sleep(500); // Brief pause before reconnect
            context.connect();

            // Step 4: Wait for logon
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Session did not log on after reconnect",
                        System.currentTimeMillis() - startTime);
            }

            // Verify logged on state
            if (!context.isLoggedOn()) {
                return TestResult.failed(getName(),
                        "Session does not report logged on after logon",
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    "Logout, disconnect, reconnect, and logon all successful",
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
