package com.fixengine.tester.tests;

import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.MessageListener;
import com.fixengine.message.FixMessage;
import com.fixengine.message.FixTags;
import com.fixengine.tester.SessionTest;
import com.fixengine.tester.TestContext;
import com.fixengine.tester.TestResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test TestRequest/Heartbeat functionality.
 * Verifies: sending TestRequest and receiving corresponding Heartbeat with correct TestReqID.
 */
public class TestRequestTest implements SessionTest {

    @Override
    public String getName() {
        return "TestRequestTest";
    }

    @Override
    public String getDescription() {
        return "Tests TestRequest message and Heartbeat response";
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

            // Set up a listener to catch the heartbeat response
            CountDownLatch heartbeatReceived = new CountDownLatch(1);
            AtomicReference<String> receivedTestReqId = new AtomicReference<>();

            MessageListener listener = new MessageListener() {
                @Override
                public void onMessage(FixSession session, FixMessage message) {
                    if (FixTags.MSG_TYPE_HEARTBEAT.equals(message.getMsgType())) {
                        String testReqId = message.getStringField(FixTags.TEST_REQ_ID);
                        receivedTestReqId.set(testReqId);
                        heartbeatReceived.countDown();
                    }
                }
            };

            context.getSession().addMessageListener(listener);

            try {
                // Send TestRequest
                String sentTestReqId = context.sendTestRequest();

                // Wait for heartbeat response
                boolean received = heartbeatReceived.await(context.getDefaultTimeoutMs(), TimeUnit.MILLISECONDS);

                if (!received) {
                    return TestResult.failed(getName(),
                            "Did not receive Heartbeat response within timeout",
                            System.currentTimeMillis() - startTime);
                }

                // Verify TestReqID matches
                String actualTestReqId = receivedTestReqId.get();
                if (sentTestReqId != null && !sentTestReqId.equals(actualTestReqId)) {
                    return TestResult.failed(getName(),
                            String.format("TestReqID mismatch: sent=%s, received=%s",
                                    sentTestReqId, actualTestReqId),
                            System.currentTimeMillis() - startTime);
                }

                return TestResult.passed(getName(),
                        String.format("TestRequest/Heartbeat exchange successful (TestReqID=%s)", sentTestReqId),
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
