package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test automatic heartbeat timing.
 * Verifies: heartbeats are sent automatically at the configured interval.
 */
public class HeartbeatTest implements SessionTest {

    @Override
    public String getName() {
        return "HeartbeatTest";
    }

    @Override
    public String getDescription() {
        return "Tests automatic heartbeat timing";
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

            int heartbeatInterval = context.getSession().getConfig().getHeartbeatInterval();
            if (heartbeatInterval <= 0) {
                return TestResult.skipped(getName(),
                        "Heartbeat interval not configured");
            }

            // For testing purposes, we'll verify that we receive heartbeats
            // We should receive at least one heartbeat within heartbeatInterval + some tolerance
            long waitTimeMs = (heartbeatInterval + 5) * 1000L; // Add 5 second tolerance

            AtomicInteger heartbeatCount = new AtomicInteger(0);
            CountDownLatch firstHeartbeat = new CountDownLatch(1);

            MessageListener listener = new MessageListener() {
                @Override
                public void onMessage(FixSession session, IncomingFixMessage message) {
                    if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                        heartbeatCount.incrementAndGet();
                        firstHeartbeat.countDown();
                    }
                }
            };

            context.getSession().addMessageListener(listener);

            try {
                // Wait for at least one heartbeat
                boolean received = firstHeartbeat.await(waitTimeMs, TimeUnit.MILLISECONDS);

                if (!received) {
                    return TestResult.failed(getName(),
                            String.format("No heartbeat received within %d seconds (heartbeat interval is %d seconds)",
                                    waitTimeMs / 1000, heartbeatInterval),
                            System.currentTimeMillis() - startTime);
                }

                // Verify session is still logged on
                if (!context.isLoggedOn()) {
                    return TestResult.failed(getName(),
                            "Session disconnected during heartbeat test",
                            System.currentTimeMillis() - startTime);
                }

                return TestResult.passed(getName(),
                        String.format("Received %d heartbeat(s), session maintained", heartbeatCount.get()),
                        System.currentTimeMillis() - startTime);

            } finally {
                context.getSession().removeMessageListener(listener);
            }

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
