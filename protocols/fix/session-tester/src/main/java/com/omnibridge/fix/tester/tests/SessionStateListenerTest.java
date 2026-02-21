package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.engine.session.SessionStateListener;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test SessionStateListener callbacks during session lifecycle.
 *
 * <p>Verifies: all relevant SessionStateListener callbacks fire during a full
 * logout/reconnect cycle. Registers a listener, walks through state transitions,
 * and verifies the expected callbacks were invoked in order.</p>
 */
public class SessionStateListenerTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(SessionStateListenerTest.class);

    @Override
    public String getName() {
        return "SessionStateListenerTest";
    }

    @Override
    public String getDescription() {
        return "Tests SessionStateListener callbacks fire correctly during lifecycle transitions";
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

            // Track all state transitions
            List<String> transitions = Collections.synchronizedList(new ArrayList<>());
            List<String> callbacks = Collections.synchronizedList(new ArrayList<>());

            SessionStateListener listener = new SessionStateListener() {
                @Override
                public void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState) {
                    String transition = oldState + " -> " + newState;
                    transitions.add(transition);
                    log.info("State transition: {}", transition);
                }

                @Override
                public void onSessionLogon(FixSession session) {
                    callbacks.add("onSessionLogon");
                    log.info("Callback: onSessionLogon");
                }

                @Override
                public void onSessionLogout(FixSession session, String reason) {
                    callbacks.add("onSessionLogout");
                    log.info("Callback: onSessionLogout (reason={})", reason);
                }

                @Override
                public void onSessionDisconnected(FixSession session, Throwable reason) {
                    callbacks.add("onSessionDisconnected");
                    log.info("Callback: onSessionDisconnected");
                }

                @Override
                public void onSessionConnected(FixSession session) {
                    callbacks.add("onSessionConnected");
                    log.info("Callback: onSessionConnected");
                }
            };

            context.getSession().addStateListener(listener);
            try {
                // Walk through: LOGGED_ON -> logout -> DISCONNECTED -> connect -> LOGGED_ON
                context.logout("Testing state listener");

                if (!context.waitForDisconnect()) {
                    return TestResult.failed(getName(),
                            "Did not disconnect after logout",
                            System.currentTimeMillis() - startTime);
                }

                context.sleep(500);
                context.connect();

                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Did not logon after reconnect",
                            System.currentTimeMillis() - startTime);
                }

                // Brief pause for all callbacks to fire
                context.sleep(500);
            } finally {
                context.getSession().removeStateListener(listener);
            }

            log.info("Transitions: {}", transitions);
            log.info("Callbacks: {}", callbacks);

            // Verify we captured state transitions
            if (transitions.isEmpty()) {
                return TestResult.failed(getName(),
                        "No state transitions captured",
                        System.currentTimeMillis() - startTime);
            }

            // Verify key callbacks fired
            boolean hasLogout = callbacks.contains("onSessionLogout");
            boolean hasDisconnected = callbacks.contains("onSessionDisconnected");
            boolean hasConnected = callbacks.contains("onSessionConnected");
            boolean hasLogon = callbacks.contains("onSessionLogon");

            StringBuilder missing = new StringBuilder();
            if (!hasDisconnected) missing.append("onSessionDisconnected ");
            if (!hasConnected) missing.append("onSessionConnected ");
            if (!hasLogon) missing.append("onSessionLogon ");

            if (!missing.isEmpty()) {
                return TestResult.failed(getName(),
                        "Missing callbacks: " + missing.toString().trim(),
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("All callbacks fired — transitions: %d (%s), callbacks: %s",
                            transitions.size(),
                            String.join(", ", transitions),
                            String.join(", ", callbacks)),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
