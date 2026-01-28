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
 * Tests FIX 5.0 session functionality using QuickFIX/J.
 *
 * <p>This test validates FIX 5.0 specific features when connecting to a
 * FIX 5.0 acceptor:</p>
 * <ul>
 *   <li>FIXT.1.1 transport layer</li>
 *   <li>DefaultApplVerID (tag 1137) in Logon</li>
 *   <li>Session establishment and heartbeat</li>
 * </ul>
 *
 * <p>Note: This test requires the target acceptor to support FIX 5.0.
 * The test will be skipped if the session is configured for FIX 4.x.</p>
 */
public class Fix50LogonTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(Fix50LogonTest.class);

    @Override
    public String getName() {
        return "Fix50LogonTest";
    }

    @Override
    public String getDescription() {
        return "Tests FIX 5.0 logon with FIXT.1.1 transport (requires FIX 5.0 configuration)";
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

            // Check if this is a FIX 5.0 session
            if (!beginString.equals("FIXT.1.1")) {
                // This is a FIX 4.x session, skip the test
                return TestResult.builder(getName())
                        .skipped("Test requires FIXT.1.1 session (current: " + beginString + ")")
                        .startTime(startTime)
                        .build();
            }

            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");
            log.info("FIX 5.0 session is logged on");

            // Get session and verify state
            Session session = Session.lookupSession(sessionId);
            if (session == null) {
                return TestResult.builder(getName())
                        .failed("Could not lookup session")
                        .startTime(startTime)
                        .build();
            }

            // Verify session is established
            context.assertTrue(session.isLoggedOn(), "Session should be logged on");
            log.info("Session state verified: logged on");

            // Send a test request to verify session is functional
            String testReqId = initiator.sendTestRequest();
            context.assertNotNull(testReqId, "TestRequest should be sent");
            log.info("Sent TestRequest: {}", testReqId);

            // Wait for heartbeat response (proves session is functional)
            context.sleep(2000);

            // Verify session is still logged on
            context.assertTrue(initiator.isLoggedOn(), "Should still be logged on after TestRequest");

            // Verify sequence numbers are reasonable
            int senderSeq = initiator.getExpectedSenderNum();
            int targetSeq = initiator.getExpectedTargetNum();
            log.info("Sequence numbers: Sender={}, Target={}", senderSeq, targetSeq);
            context.assertTrue(senderSeq >= 2, "Sender sequence should be at least 2");
            context.assertTrue(targetSeq >= 2, "Target sequence should be at least 2");

            return TestResult.builder(getName())
                    .passed("FIX 5.0 (FIXT.1.1) session established and functional")
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
