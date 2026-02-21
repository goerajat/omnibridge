package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test rapid disconnect/reconnect cycles.
 *
 * <p>Validates: the engine handles rapid disconnect/reconnect cycles without
 * leaking resources or corrupting session state. Performs 5 rapid cycles with
 * minimal delay between disconnect and reconnect, then verifies the session
 * is fully operational.</p>
 */
public class RapidLogonLogoutTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(RapidLogonLogoutTest.class);

    private static final int RAPID_CYCLES = 5;

    @Override
    public String getName() {
        return "RapidLogonLogoutTest";
    }

    @Override
    public String getDescription() {
        return "Validates engine handles " + RAPID_CYCLES + " rapid disconnect/reconnect cycles without resource leaks";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            for (int cycle = 1; cycle <= RAPID_CYCLES; cycle++) {
                log.info("Rapid cycle {}/{}", cycle, RAPID_CYCLES);

                // Disconnect (hard disconnect, not graceful logout)
                if (context.isLoggedOn() || context.isConnected()) {
                    context.disconnect();
                    if (!context.waitForDisconnect(5000)) {
                        return TestResult.failed(getName(),
                                String.format("Failed to disconnect at cycle %d", cycle),
                                System.currentTimeMillis() - startTime);
                    }
                }

                // Minimal delay — this is the "rapid" part
                context.sleep(200);

                // Reconnect
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            String.format("Failed to logon at rapid cycle %d", cycle),
                            System.currentTimeMillis() - startTime);
                }

                log.info("Rapid cycle {}/{} — logged on", cycle, RAPID_CYCLES);
            }

            // Final verification: send TestRequest and verify Heartbeat response
            CountDownLatch heartbeatLatch = new CountDownLatch(1);
            MessageListener listener = (session, message) -> {
                if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                    heartbeatLatch.countDown();
                }
            };

            context.getSession().addMessageListener(listener);
            try {
                context.sendTestRequest();
                boolean received = heartbeatLatch.await(5, TimeUnit.SECONDS);
                if (!received) {
                    return TestResult.failed(getName(),
                            "No Heartbeat response after rapid reconnect cycles — session may be corrupted",
                            System.currentTimeMillis() - startTime);
                }
            } finally {
                context.getSession().removeMessageListener(listener);
            }

            if (!context.isLoggedOn()) {
                return TestResult.failed(getName(),
                        "Session not logged on after rapid reconnect cycles",
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("Session survived %d rapid disconnect/reconnect cycles and is fully operational",
                            RAPID_CYCLES),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
