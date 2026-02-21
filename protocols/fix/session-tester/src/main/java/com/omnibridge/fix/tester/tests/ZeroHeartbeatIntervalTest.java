package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.config.EngineConfig;
import com.omnibridge.fix.engine.config.SessionConfig;
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

/**
 * Test session behavior with heartbeat interval set to zero (disabled).
 *
 * <p>Validates: the session functions correctly when heartbeat interval is set to 0,
 * effectively disabling periodic heartbeats. The session should still respond to
 * TestRequest messages with Heartbeat responses and should not timeout due to
 * missing heartbeats.</p>
 */
public class ZeroHeartbeatIntervalTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(ZeroHeartbeatIntervalTest.class);

    @Override
    public String getName() {
        return "ZeroHeartbeatIntervalTest";
    }

    @Override
    public String getDescription() {
        return "Validates session functions correctly with heartbeat interval set to 0 (disabled)";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Disconnect main session to free CompIDs
            if (context.isLoggedOn() || context.isConnected()) {
                context.disconnect();
                if (!context.waitForDisconnect(5000)) {
                    return TestResult.failed(getName(),
                            "Could not disconnect main session",
                            System.currentTimeMillis() - startTime);
                }
            }
            context.sleep(500);

            String host = context.getSession().getConfig().getHost();
            int port = context.getSession().getConfig().getPort();
            String senderCompId = context.getSession().getConfig().getSenderCompId();
            String targetCompId = context.getSession().getConfig().getTargetCompId();

            // Step 2: Create engine with heartbeatInterval=0
            FixEngine tempEngine = null;
            try {
                SessionConfig sessionConfig = SessionConfig.builder()
                        .sessionName("ZeroHeartbeatTest")
                        .senderCompId(senderCompId)
                        .targetCompId(targetCompId)
                        .host(host)
                        .port(port)
                        .initiator()
                        .heartbeatInterval(0)
                        .resetOnLogon(true)
                        .build();

                EngineConfig engineConfig = EngineConfig.builder()
                        .addSession(sessionConfig)
                        .build();

                tempEngine = new FixEngine(engineConfig);
                tempEngine.start();

                FixSession tempSession = tempEngine.createSession(sessionConfig);
                tempEngine.connect(sessionConfig.getSessionId());

                // Wait for logon
                long deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline && tempSession.getState() != SessionState.LOGGED_ON) {
                    context.sleep(100);
                }

                if (tempSession.getState() != SessionState.LOGGED_ON) {
                    return TestResult.failed(getName(),
                            "Could not logon with heartbeatInterval=0: state=" + tempSession.getState(),
                            System.currentTimeMillis() - startTime);
                }

                log.info("Logged on with heartbeatInterval=0");

                // Step 3: Verify session responds to TestRequest
                CountDownLatch heartbeatLatch = new CountDownLatch(1);
                MessageListener listener = (session, message) -> {
                    if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                        heartbeatLatch.countDown();
                    }
                };

                tempSession.addMessageListener(listener);
                try {
                    tempSession.sendTestRequest();
                    boolean received = heartbeatLatch.await(5, TimeUnit.SECONDS);
                    if (!received) {
                        return TestResult.failed(getName(),
                                "No Heartbeat response with heartbeatInterval=0",
                                System.currentTimeMillis() - startTime);
                    }
                } finally {
                    tempSession.removeMessageListener(listener);
                }

                log.info("TestRequest/Heartbeat exchange successful with heartbeatInterval=0");

                // Step 4: Wait a bit and verify session stays LOGGED_ON
                // (no heartbeat timeout should fire since interval is 0)
                context.sleep(3000);

                if (tempSession.getState() != SessionState.LOGGED_ON) {
                    return TestResult.failed(getName(),
                            "Session lost LOGGED_ON state with heartbeatInterval=0: state=" + tempSession.getState(),
                            System.currentTimeMillis() - startTime);
                }

                log.info("Session remained LOGGED_ON for 3 seconds with heartbeatInterval=0");

                // Cleanup temp engine
                tempEngine.stop();
                tempEngine = null;

            } finally {
                if (tempEngine != null) {
                    try {
                        tempEngine.stop();
                    } catch (Exception e) {
                        log.debug("Cleanup stop: {}", e.getMessage());
                    }
                }
            }

            // Step 5: Reconnect main session
            context.sleep(500);
            context.connect();
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not reconnect main session after zero heartbeat test",
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    "Session functioned correctly with heartbeatInterval=0 — logon, TestRequest/Heartbeat exchange, " +
                            "and 3-second stability verified",
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            // Try to reconnect main session on error
            try {
                context.sleep(500);
                context.connect();
                context.waitForLogon();
            } catch (Exception reconnectErr) {
                log.debug("Reconnect after error: {}", reconnectErr.getMessage());
            }
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }
}
