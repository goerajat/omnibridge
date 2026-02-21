package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.engine.session.SessionStateListener;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test maxReconnectAttempts enforcement.
 *
 * <p>Verifies: when configured with a limited number of reconnect attempts and
 * connecting to a non-existent endpoint, the engine stops reconnecting after
 * the configured maximum. This prevents infinite reconnect loops.</p>
 */
public class MaxReconnectAttemptsTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(MaxReconnectAttemptsTest.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int DEAD_PORT = 19999;

    @Override
    public String getName() {
        return "MaxReconnectAttemptsTest";
    }

    @Override
    public String getDescription() {
        return "Tests that reconnection stops after maxReconnectAttempts is reached";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Ensure the main session is still logged on
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Could not establish logged on state for main session",
                            System.currentTimeMillis() - startTime);
                }
            }

            // Create a new session pointing to a dead port with limited reconnect attempts
            String testSessionName = "MaxReconnectTest-" + System.currentTimeMillis();
            SessionConfig testConfig;
            try {
                testConfig = SessionConfig.builder()
                        .sessionName(testSessionName)
                        .senderCompId("TESTER-MR")
                        .targetCompId("NOBODY")
                        .host("localhost")
                        .port(DEAD_PORT)
                        .initiator()
                        .heartbeatInterval(5)
                        .maxReconnectAttempts(MAX_ATTEMPTS)
                        .reconnectInterval(1)
                        .build();
            } catch (Exception e) {
                return TestResult.skipped(getName(),
                        "Could not create test session config: " + e.getMessage());
            }

            FixSession testSession;
            try {
                testSession = context.getEngine().createSession(testConfig);
            } catch (Exception e) {
                return TestResult.skipped(getName(),
                        "Could not create test session: " + e.getMessage());
            }

            // Track connect failures
            AtomicInteger connectAttempts = new AtomicInteger(0);
            SessionStateListener stateListener = new SessionStateListener() {
                @Override
                public void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState) {
                    if (session == testSession) {
                        log.info("MaxReconnect test session: {} -> {}", oldState, newState);
                        if (newState == SessionState.CONNECTING) {
                            connectAttempts.incrementAndGet();
                        }
                    }
                }
            };

            testSession.addStateListener(stateListener);
            try {
                // Initiate connection to the dead port
                context.getEngine().connect(testConfig.getSessionId());

                // Wait enough time for all reconnect attempts plus margin
                // MAX_ATTEMPTS * reconnectInterval(1s) + connection timeout + margin
                long waitTime = (MAX_ATTEMPTS + 2) * 2000L;
                context.sleep(waitTime);

                int attempts = connectAttempts.get();
                SessionState finalState = testSession.getState();

                log.info("After waiting: {} connect attempts, state={}", attempts, finalState);

                if (attempts > MAX_ATTEMPTS + 1) {
                    return TestResult.failed(getName(),
                            String.format("Too many reconnect attempts: %d (max configured: %d)",
                                    attempts, MAX_ATTEMPTS),
                            System.currentTimeMillis() - startTime);
                }

                return TestResult.passed(getName(),
                        String.format("Reconnection stopped after %d attempts (max=%d), final state=%s",
                                attempts, MAX_ATTEMPTS, finalState),
                        System.currentTimeMillis() - startTime);

            } finally {
                testSession.removeStateListener(stateListener);
                // Clean up the test session
                try {
                    testSession.disconnect("Test cleanup");
                } catch (Exception e) {
                    log.debug("Cleanup disconnect: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
