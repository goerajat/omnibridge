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
 * Test session stability across multiple disconnect/reconnect cycles.
 *
 * <p>Verifies: the session can survive repeated reconnections without sequence number
 * drift, state corruption, or connection failures. Each cycle sends a TestRequest
 * and verifies a Heartbeat response to confirm the session is fully functional.</p>
 */
public class MultipleReconnectTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(MultipleReconnectTest.class);
    private static final int RECONNECT_CYCLES = 5;

    @Override
    public String getName() {
        return "MultipleReconnectTest";
    }

    @Override
    public String getDescription() {
        return "Tests session stability across " + RECONNECT_CYCLES + " disconnect/reconnect cycles";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            for (int cycle = 1; cycle <= RECONNECT_CYCLES; cycle++) {
                log.info("Reconnect cycle {}/{}", cycle, RECONNECT_CYCLES);

                // Ensure logged on
                if (!context.isLoggedOn()) {
                    context.connect();
                    if (!context.waitForLogon()) {
                        return TestResult.failed(getName(),
                                String.format("Failed to logon at cycle %d", cycle),
                                System.currentTimeMillis() - startTime);
                    }
                }

                // Verify session is functional by sending TestRequest
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
                                String.format("No Heartbeat response at cycle %d", cycle),
                                System.currentTimeMillis() - startTime);
                    }
                } finally {
                    context.getSession().removeMessageListener(listener);
                }

                // Disconnect
                context.logout("Reconnect cycle " + cycle);
                if (!context.waitForDisconnect()) {
                    return TestResult.failed(getName(),
                            String.format("Did not disconnect at cycle %d", cycle),
                            System.currentTimeMillis() - startTime);
                }

                context.sleep(300);

                // Reconnect
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            String.format("Failed to logon after disconnect at cycle %d", cycle),
                            System.currentTimeMillis() - startTime);
                }
            }

            // Final verification
            if (!context.isLoggedOn()) {
                return TestResult.failed(getName(),
                        "Not logged on after all reconnect cycles",
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("Session stable across %d reconnect cycles", RECONNECT_CYCLES),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
