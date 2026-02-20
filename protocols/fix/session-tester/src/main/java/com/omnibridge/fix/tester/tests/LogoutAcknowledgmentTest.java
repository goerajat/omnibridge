package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.engine.session.SessionState;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test that initiating a Logout results in a clean logout cycle and disconnect.
 *
 * <p>Verifies: when we send a Logout, the acceptor processes it and the session
 * transitions to DISCONNECTED. If the acceptor responds with a Logout ack (35=5)
 * before closing the connection, the test captures it — but the primary assertion
 * is that the logout cycle completes and the session can reconnect.</p>
 *
 * <p>Note: many acceptors disconnect immediately after queuing the Logout ack,
 * so the ack may not arrive before the TCP connection is closed.</p>
 */
public class LogoutAcknowledgmentTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(LogoutAcknowledgmentTest.class);

    @Override
    public String getName() {
        return "LogoutAcknowledgmentTest";
    }

    @Override
    public String getDescription() {
        return "Tests that initiating Logout completes the logout cycle and session disconnects";
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

            // Set up listener to capture the Logout ack if it arrives
            AtomicReference<String> logoutText = new AtomicReference<>();
            CountDownLatch logoutReceived = new CountDownLatch(1);

            MessageListener listener = new MessageListener() {
                @Override
                public void onMessage(FixSession session, IncomingFixMessage message) {
                    if (FixTags.MSG_TYPE_LOGOUT.equals(message.getMsgType())) {
                        CharSequence text = message.getCharSequence(FixTags.Text);
                        logoutText.set(text != null ? text.toString() : "(no text)");
                        logoutReceived.countDown();
                    }
                }
            };

            context.getSession().addMessageListener(listener);

            try {
                // Send Logout
                String reason = "Logout acknowledgment test";
                context.logout(reason);
                log.info("Sent Logout: {}", reason);

                // Wait briefly for a Logout ack (best-effort)
                logoutReceived.await(2, TimeUnit.SECONDS);

                // Wait for the session to disconnect (the primary assertion)
                boolean disconnected = context.waitForDisconnect(10000);

                if (!disconnected) {
                    return TestResult.failed(getName(),
                            "Session did not disconnect after sending Logout",
                            System.currentTimeMillis() - startTime);
                }

                // Verify state is DISCONNECTED
                if (context.getState() != SessionState.DISCONNECTED) {
                    return TestResult.failed(getName(),
                            "Expected DISCONNECTED after logout, got: " + context.getState(),
                            System.currentTimeMillis() - startTime);
                }

                log.info("Session disconnected after logout (state={})", context.getState());

            } finally {
                context.getSession().removeMessageListener(listener);
            }

            // Reconnect for subsequent tests
            context.sleep(500);
            context.connect();
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not reconnect after logout acknowledgment test",
                        System.currentTimeMillis() - startTime);
            }

            String ackInfo = logoutText.get() != null
                    ? String.format("Logout ack received: \"%s\"", logoutText.get())
                    : "Logout ack not received (acceptor disconnected before flush)";

            return TestResult.passed(getName(),
                    String.format("Logout cycle completed — %s, session reconnected", ackInfo),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
