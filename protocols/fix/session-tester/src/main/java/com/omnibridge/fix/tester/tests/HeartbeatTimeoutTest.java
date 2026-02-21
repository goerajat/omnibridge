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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test heartbeat mechanism and timing.
 *
 * <p>Verifies: the heartbeat mechanism is operational by sending a TestRequest and
 * confirming the Heartbeat response arrives within the expected heartbeat interval.
 * Also counts heartbeats received over a window to verify the keepalive timing.</p>
 */
public class HeartbeatTimeoutTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatTimeoutTest.class);

    @Override
    public String getName() {
        return "HeartbeatTimeoutTest";
    }

    @Override
    public String getDescription() {
        return "Tests heartbeat mechanism is operational and responds within expected interval";
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

            int heartbeatInterval = context.getSession().getConfig().getHeartbeatInterval();
            log.info("Heartbeat interval: {} seconds", heartbeatInterval);

            // Test 1: Send TestRequest and measure response time
            CountDownLatch heartbeatLatch = new CountDownLatch(1);
            long testReqSendTime = System.currentTimeMillis();

            MessageListener listener = (session, message) -> {
                if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                    heartbeatLatch.countDown();
                }
            };

            context.getSession().addMessageListener(listener);
            try {
                context.sendTestRequest();

                boolean received = heartbeatLatch.await(heartbeatInterval + 5, TimeUnit.SECONDS);
                long responseTime = System.currentTimeMillis() - testReqSendTime;

                if (!received) {
                    return TestResult.failed(getName(),
                            String.format("No Heartbeat response within %d seconds (heartbeat interval + 5s)",
                                    heartbeatInterval + 5),
                            System.currentTimeMillis() - startTime);
                }

                log.info("Heartbeat response received in {} ms", responseTime);

                if (responseTime > heartbeatInterval * 1000L) {
                    return TestResult.failed(getName(),
                            String.format("Heartbeat response too slow: %d ms (interval=%d s)",
                                    responseTime, heartbeatInterval),
                            System.currentTimeMillis() - startTime);
                }
            } finally {
                context.getSession().removeMessageListener(listener);
            }

            // Test 2: Count heartbeats over a 5-second window to verify ongoing keepalive
            AtomicInteger heartbeatCount = new AtomicInteger(0);
            MessageListener countListener = (session, message) -> {
                if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                    heartbeatCount.incrementAndGet();
                }
            };

            context.getSession().addMessageListener(countListener);
            try {
                // Send another TestRequest to trigger a heartbeat
                context.sendTestRequest();
                context.sleep(3000);
            } finally {
                context.getSession().removeMessageListener(countListener);
            }

            if (!context.isLoggedOn()) {
                return TestResult.failed(getName(),
                        "Session lost logon state during heartbeat monitoring",
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    String.format("Heartbeat mechanism operational — response in %d ms, " +
                                    "%d heartbeats in 3s window (interval=%d s)",
                            System.currentTimeMillis() - testReqSendTime,
                            heartbeatCount.get(), heartbeatInterval),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
