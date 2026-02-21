package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test session state consistency during unusual events.
 *
 * <p>Verifies: the session maintains consistent state through rapid state transitions.
 * Checks that after logon, the session reports LOGGED_ON, and that all state queries
 * (isLoggedOn, isConnected, getState) are consistent with each other.</p>
 */
public class LogonWhileConnectedTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(LogonWhileConnectedTest.class);

    @Override
    public String getName() {
        return "LogonWhileConnectedTest";
    }

    @Override
    public String getDescription() {
        return "Tests session state consistency — verifies state queries are coherent";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Could not establish logged on state",
                            System.currentTimeMillis() - startTime);
                }
            }

            // Test 1: Verify state consistency when logged on
            SessionState state = context.getState();
            boolean isLoggedOn = context.isLoggedOn();
            boolean isConnected = context.isConnected();

            log.info("State check 1: state={}, isLoggedOn={}, isConnected={}", state, isLoggedOn, isConnected);

            if (state != SessionState.LOGGED_ON) {
                return TestResult.failed(getName(),
                        "Expected LOGGED_ON state, got: " + state,
                        System.currentTimeMillis() - startTime);
            }

            if (!isLoggedOn) {
                return TestResult.failed(getName(),
                        "isLoggedOn() returns false but state is LOGGED_ON",
                        System.currentTimeMillis() - startTime);
            }

            if (!isConnected) {
                return TestResult.failed(getName(),
                        "isConnected() returns false but state is LOGGED_ON",
                        System.currentTimeMillis() - startTime);
            }

            // Test 2: Rapid disconnect and check state transitions
            context.disconnect();
            if (!context.waitForDisconnect(5000)) {
                return TestResult.failed(getName(),
                        "Did not disconnect",
                        System.currentTimeMillis() - startTime);
            }

            state = context.getState();
            isLoggedOn = context.isLoggedOn();
            isConnected = context.isConnected();

            log.info("State check 2 (disconnected): state={}, isLoggedOn={}, isConnected={}",
                    state, isLoggedOn, isConnected);

            if (state != SessionState.DISCONNECTED) {
                return TestResult.failed(getName(),
                        "Expected DISCONNECTED state, got: " + state,
                        System.currentTimeMillis() - startTime);
            }

            if (isLoggedOn) {
                return TestResult.failed(getName(),
                        "isLoggedOn() returns true but state is DISCONNECTED",
                        System.currentTimeMillis() - startTime);
            }

            if (isConnected) {
                return TestResult.failed(getName(),
                        "isConnected() returns true but state is DISCONNECTED",
                        System.currentTimeMillis() - startTime);
            }

            // Test 3: Reconnect and verify state restored
            context.sleep(300);
            context.connect();

            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not reconnect",
                        System.currentTimeMillis() - startTime);
            }

            state = context.getState();
            isLoggedOn = context.isLoggedOn();
            isConnected = context.isConnected();

            log.info("State check 3 (reconnected): state={}, isLoggedOn={}, isConnected={}",
                    state, isLoggedOn, isConnected);

            if (state != SessionState.LOGGED_ON || !isLoggedOn || !isConnected) {
                return TestResult.failed(getName(),
                        String.format("State inconsistency after reconnect: state=%s, loggedOn=%b, connected=%b",
                                state, isLoggedOn, isConnected),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    "State queries consistent across LOGGED_ON -> DISCONNECTED -> LOGGED_ON transitions",
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
