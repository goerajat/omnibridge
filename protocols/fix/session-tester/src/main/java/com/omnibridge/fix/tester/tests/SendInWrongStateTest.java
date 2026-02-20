package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test that tryClaimMessage() throws IllegalStateException when the session is not LOGGED_ON.
 *
 * <p>Verifies: the guard in FixSession.tryClaimMessage() that prevents application messages
 * from being sent in non-LOGGED_ON states (DISCONNECTED, CONNECTED, etc.).</p>
 */
public class SendInWrongStateTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(SendInWrongStateTest.class);

    @Override
    public String getName() {
        return "SendInWrongStateTest";
    }

    @Override
    public String getDescription() {
        return "Tests that sending in wrong state throws IllegalStateException";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Ensure logged on first
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Could not establish logged on state",
                            System.currentTimeMillis() - startTime);
                }
            }

            // Disconnect
            context.disconnect();
            if (!context.waitForDisconnect(5000)) {
                return TestResult.failed(getName(),
                        "Did not disconnect",
                        System.currentTimeMillis() - startTime);
            }

            // Try to send while disconnected — should throw
            boolean threwException = false;
            String exceptionMessage = null;
            try {
                context.tryClaimMessage("D"); // NewOrderSingle
            } catch (IllegalStateException e) {
                threwException = true;
                exceptionMessage = e.getMessage();
                log.info("Got expected IllegalStateException: {}", e.getMessage());
            }

            if (!threwException) {
                // Reconnect before failing
                context.connect();
                context.waitForLogon();
                return TestResult.failed(getName(),
                        "tryClaimMessage() did not throw IllegalStateException when DISCONNECTED",
                        System.currentTimeMillis() - startTime);
            }

            // Reconnect and verify sending works again
            context.sleep(300);
            context.connect();
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not reconnect after disconnect",
                        System.currentTimeMillis() - startTime);
            }

            // Verify we can claim and abort a message successfully
            var msg = context.tryClaimMessage("D");
            if (msg == null) {
                return TestResult.failed(getName(),
                        "tryClaimMessage() returned null after reconnect (buffer full?)",
                        System.currentTimeMillis() - startTime);
            }
            context.getSession().abortMessage(msg);

            return TestResult.passed(getName(),
                    String.format("IllegalStateException thrown correctly (%s), sending works after reconnect",
                            exceptionMessage),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
