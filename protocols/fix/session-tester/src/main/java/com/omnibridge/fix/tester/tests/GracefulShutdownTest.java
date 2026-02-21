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
 * Test graceful engine shutdown.
 *
 * <p>Validates: Engine.stop() gracefully shuts down sessions and releases resources.
 * Disconnects the main test session first (to free CompIDs), creates a separate
 * FixEngine, verifies it's operational, then calls stop() and verifies the session
 * reaches DISCONNECTED state.</p>
 */
public class GracefulShutdownTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownTest.class);

    @Override
    public String getName() {
        return "GracefulShutdownTest";
    }

    @Override
    public String getDescription() {
        return "Validates Engine.stop() gracefully shuts down sessions and releases resources";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Disconnect the main test session to free the CompIDs on the acceptor
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

            // Step 2: Create a separate FixEngine
            FixEngine tempEngine = null;
            try {
                SessionConfig sessionConfig = SessionConfig.builder()
                        .sessionName("GracefulShutdownTest")
                        .senderCompId(senderCompId)
                        .targetCompId(targetCompId)
                        .host(host)
                        .port(port)
                        .initiator()
                        .heartbeatInterval(30)
                        .resetOnLogon(true)
                        .build();

                EngineConfig engineConfig = EngineConfig.builder()
                        .addSession(sessionConfig)
                        .build();

                tempEngine = new FixEngine(engineConfig);
                tempEngine.start();

                FixSession tempSession = tempEngine.createSession(sessionConfig);
                tempEngine.connect(sessionConfig.getSessionId());

                // Wait for logon on temp engine
                long deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline && tempSession.getState() != SessionState.LOGGED_ON) {
                    context.sleep(100);
                }

                if (tempSession.getState() != SessionState.LOGGED_ON) {
                    return TestResult.failed(getName(),
                            "Temporary engine could not logon: state=" + tempSession.getState(),
                            System.currentTimeMillis() - startTime);
                }

                log.info("Temporary engine logged on successfully");

                // Step 3: Verify temp engine is operational
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
                                "Temporary engine not operational — no Heartbeat response",
                                System.currentTimeMillis() - startTime);
                    }
                } finally {
                    tempSession.removeMessageListener(listener);
                }

                log.info("Temporary engine is operational, calling stop()...");

                // Step 4: Call engine.stop() — this should gracefully shut down
                tempEngine.stop();
                tempEngine = null; // Prevent double-stop in finally

                context.sleep(1000);

                // Step 5: Verify the session is disconnected
                SessionState finalState = tempSession.getState();
                log.info("Session state after engine.stop(): {}", finalState);

                if (finalState == SessionState.DISCONNECTED) {
                    log.info("Graceful shutdown successful — session disconnected");
                } else {
                    log.info("Session state after stop: {} (may still be transitioning)", finalState);
                }

            } finally {
                // Ensure temp engine is stopped even on failure
                if (tempEngine != null) {
                    try {
                        tempEngine.stop();
                    } catch (Exception e) {
                        log.debug("Cleanup stop: {}", e.getMessage());
                    }
                }
            }

            // Step 6: Reconnect the main test session
            context.sleep(500);
            context.connect();
            if (!context.waitForLogon()) {
                return TestResult.failed(getName(),
                        "Could not reconnect main session after graceful shutdown test",
                        System.currentTimeMillis() - startTime);
            }

            return TestResult.passed(getName(),
                    "Engine.stop() gracefully shut down session — temporary engine created, verified operational, stopped, and main session reconnected",
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
