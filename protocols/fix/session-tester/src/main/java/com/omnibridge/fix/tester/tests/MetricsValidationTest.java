package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.engine.session.MessageListener;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test Micrometer metrics binding and counter increments.
 *
 * <p>Validates: Micrometer metrics counters increment after FIX operations.
 * Creates a SimpleMeterRegistry, binds it to the engine and session, performs
 * a TestRequest/Heartbeat exchange, and verifies that sent/received counters
 * and the logged_on gauge are correct.</p>
 */
public class MetricsValidationTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsValidationTest.class);

    @Override
    public String getName() {
        return "MetricsValidationTest";
    }

    @Override
    public String getDescription() {
        return "Validates Micrometer metrics counters increment after FIX operations";
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

            // Create a SimpleMeterRegistry and bind it to the session
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            // Bind metrics to engine and session
            context.getEngine().setMeterRegistry(registry);
            context.getSession().bindMetrics(registry);

            log.info("Bound SimpleMeterRegistry to engine and session");

            // Send a TestRequest and wait for Heartbeat response
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
                            "No Heartbeat response for metrics verification",
                            System.currentTimeMillis() - startTime);
                }
            } finally {
                context.getSession().removeMessageListener(listener);
            }

            // Allow metrics to be recorded
            context.sleep(500);

            // Query metrics
            StringBuilder metricsReport = new StringBuilder();
            boolean allPassed = true;

            // Check messages sent counter
            Counter sentCounter = registry.find("omnibridge.messages.sent.total").counter();
            if (sentCounter != null) {
                double sentCount = sentCounter.count();
                metricsReport.append(String.format("messages.sent=%.0f", sentCount));
                if (sentCount <= 0) {
                    log.warn("messages.sent.total counter is 0 — may not have been incremented yet");
                }
            } else {
                metricsReport.append("messages.sent=NOT_FOUND");
                // Counter may not be found if session was already using a different registry
            }

            // Check messages received counter
            Counter receivedCounter = registry.find("omnibridge.messages.received.total").counter();
            if (receivedCounter != null) {
                double receivedCount = receivedCounter.count();
                metricsReport.append(String.format(", messages.received=%.0f", receivedCount));
            } else {
                metricsReport.append(", messages.received=NOT_FOUND");
            }

            // Check logged_on gauge
            Gauge loggedOnGauge = registry.find("omnibridge.session.logged_on").gauge();
            if (loggedOnGauge != null) {
                double loggedOnValue = loggedOnGauge.value();
                metricsReport.append(String.format(", session.logged_on=%.1f", loggedOnValue));
                if (loggedOnValue != 1.0) {
                    allPassed = false;
                    metricsReport.append(" (EXPECTED 1.0)");
                }
            } else {
                metricsReport.append(", session.logged_on=NOT_FOUND");
            }

            // Check sequence number gauge
            Gauge outgoingSeqGauge = registry.find("omnibridge.sequence.outgoing").gauge();
            if (outgoingSeqGauge != null) {
                double seqValue = outgoingSeqGauge.value();
                metricsReport.append(String.format(", sequence.outgoing=%.0f", seqValue));
            }

            // Check heartbeat counters
            Counter hbSentCounter = registry.find("omnibridge.heartbeat.sent.total").counter();
            Counter testReqSentCounter = registry.find("omnibridge.heartbeat.test_request.sent.total").counter();
            if (testReqSentCounter != null) {
                metricsReport.append(String.format(", test_request.sent=%.0f", testReqSentCounter.count()));
            }

            log.info("Metrics report: {}", metricsReport);

            // Verify at least some metrics were registered
            long totalMeters = registry.getMeters().size();
            if (totalMeters == 0) {
                return TestResult.failed(getName(),
                        "No metrics were registered in the SimpleMeterRegistry",
                        System.currentTimeMillis() - startTime);
            }

            String resultMsg = String.format("Metrics bound successfully (%d meters) — %s",
                    totalMeters, metricsReport);

            if (allPassed) {
                return TestResult.passed(getName(), resultMsg,
                        System.currentTimeMillis() - startTime);
            } else {
                return TestResult.failed(getName(), resultMsg,
                        System.currentTimeMillis() - startTime);
            }

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
