package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.message.ApplVerID;
import com.omnibridge.fix.message.FixVersion;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests FIX 5.0 session functionality.
 *
 * <p>This test validates FIX 5.0 specific features:</p>
 * <ul>
 *   <li>FIXT.1.1 transport layer</li>
 *   <li>DefaultApplVerID (tag 1137) in Logon</li>
 *   <li>ApplVerID negotiation</li>
 * </ul>
 */
public class Fix50LogonTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(Fix50LogonTest.class);

    @Override
    public String getName() {
        return "Fix50LogonTest";
    }

    @Override
    public String getDescription() {
        return "Tests FIX 5.0 logon with FIXT.1.1 transport and ApplVerID negotiation";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Check if session is using FIX 5.0
            FixVersion fixVersion = context.getSession().getFixVersion();
            if (fixVersion == null || !fixVersion.usesFixt()) {
                return TestResult.skipped(getName(),
                        "Test requires FIX 5.0+ session (current: " +
                                (fixVersion != null ? fixVersion : "FIX.4.4") + ")");
            }

            log.info("Testing FIX 5.0 session with version: {}", fixVersion);

            // Verify initial logged on state
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Session not logged on initially and could not connect",
                            System.currentTimeMillis() - startTime);
                }
            }

            // Step 1: Verify BeginString is FIXT.1.1
            String beginString = context.getSession().getConfig().getBeginString();
            log.info("BeginString: {}", beginString);
            if (!"FIXT.1.1".equals(beginString)) {
                return TestResult.failed(getName(),
                        "Expected BeginString 'FIXT.1.1', got: " + beginString,
                        System.currentTimeMillis() - startTime);
            }

            // Step 2: Verify negotiated ApplVerID
            ApplVerID negotiatedApplVerID = context.getSession().getNegotiatedApplVerID();
            log.info("Negotiated ApplVerID: {}", negotiatedApplVerID);
            if (negotiatedApplVerID == null) {
                return TestResult.failed(getName(),
                        "No ApplVerID was negotiated during logon",
                        System.currentTimeMillis() - startTime);
            }

            // Step 3: Verify ApplVerID matches expected version
            ApplVerID expectedApplVerID = fixVersion.getDefaultApplVerID();
            if (expectedApplVerID != null && negotiatedApplVerID != expectedApplVerID) {
                log.warn("ApplVerID mismatch: expected {}, negotiated {}",
                        expectedApplVerID, negotiatedApplVerID);
            }

            // Step 4: Test logout and reconnect
            log.info("Testing logout/reconnect with FIX 5.0...");
            context.logout("FIX 5.0 test logout");

            if (!context.waitForDisconnect()) {
                return TestResult.failed(getName(),
                        "Session did not disconnect after logout",
                        System.currentTimeMillis() - startTime);
            }

            context.sleep(500);
            context.connect();

            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Session did not log on after reconnect",
                        System.currentTimeMillis() - startTime);
            }

            // Verify ApplVerID is still negotiated after reconnect
            ApplVerID reconnectedApplVerID = context.getSession().getNegotiatedApplVerID();
            log.info("ApplVerID after reconnect: {}", reconnectedApplVerID);
            if (reconnectedApplVerID == null) {
                return TestResult.failed(getName(),
                        "No ApplVerID negotiated after reconnect",
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("FIX 5.0 logon successful: BeginString=%s, ApplVerID=%s",
                            beginString, negotiatedApplVerID),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("Error during FIX 5.0 logon test", e);
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
