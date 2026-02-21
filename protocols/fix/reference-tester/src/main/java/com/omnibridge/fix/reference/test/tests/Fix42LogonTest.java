package com.omnibridge.fix.reference.test.tests;

import com.omnibridge.fix.reference.initiator.ReferenceInitiator;
import com.omnibridge.fix.reference.test.ReferenceTest;
import com.omnibridge.fix.reference.test.TestContext;
import com.omnibridge.fix.reference.test.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Session;
import quickfix.SessionID;

import java.time.Instant;

/**
 * Tests FIX 4.2 session functionality.
 *
 * <p>Validates FIX 4.2 specific behavior when connecting to a FIX 4.2 acceptor:</p>
 * <ul>
 *   <li>BeginString is "FIX.4.2"</li>
 *   <li>No FIXT.1.1 transport or ApplVerID</li>
 *   <li>Session establishment and heartbeat</li>
 * </ul>
 *
 * <p>This test will be skipped if the session is not configured for FIX 4.2.</p>
 */
public class Fix42LogonTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(Fix42LogonTest.class);

    @Override
    public String getName() {
        return "Fix42LogonTest";
    }

    @Override
    public String getDescription() {
        return "Tests FIX 4.2 session establishment (requires FIX 4.2 configuration)";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            SessionID sessionId = initiator.getCurrentSession();
            if (sessionId == null) {
                return TestResult.builder(getName())
                        .failed("No active session")
                        .startTime(startTime)
                        .build();
            }

            String beginString = sessionId.getBeginString();
            log.info("Testing session with BeginString: {}", beginString);

            // Check if this is a FIX 4.2 session
            if (!"FIX.4.2".equals(beginString)) {
                return TestResult.builder(getName())
                        .skipped("Test requires FIX.4.2 session (current: " + beginString + ")")
                        .startTime(startTime)
                        .build();
            }

            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");
            log.info("FIX 4.2 session is logged on");

            // Get session and verify state
            Session session = Session.lookupSession(sessionId);
            if (session == null) {
                return TestResult.builder(getName())
                        .failed("Could not lookup session")
                        .startTime(startTime)
                        .build();
            }

            context.assertTrue(session.isLoggedOn(), "Session should be logged on");

            // Verify no FIXT.1.1 transport (FIX 4.2 uses single dictionary)
            context.assertTrue(!beginString.equals("FIXT.1.1"),
                    "FIX 4.2 should not use FIXT.1.1 transport");

            // Send a test request to verify session is functional
            String testReqId = initiator.sendTestRequest();
            context.assertNotNull(testReqId, "TestRequest should be sent");
            log.info("Sent TestRequest: {}", testReqId);

            context.sleep(2000);

            context.assertTrue(initiator.isLoggedOn(), "Should still be logged on after TestRequest");

            // Verify sequence numbers
            int senderSeq = initiator.getExpectedSenderNum();
            int targetSeq = initiator.getExpectedTargetNum();
            log.info("Sequence numbers: Sender={}, Target={}", senderSeq, targetSeq);
            context.assertTrue(senderSeq >= 2, "Sender sequence should be at least 2");
            context.assertTrue(targetSeq >= 2, "Target sequence should be at least 2");

            return TestResult.builder(getName())
                    .passed("FIX 4.2 session established and functional")
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
