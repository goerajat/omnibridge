package com.fixengine.tester;

import com.fixengine.engine.FixEngine;
import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.SessionState;
import com.fixengine.engine.session.SessionStateListener;
import com.fixengine.message.RingBufferOutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test context providing utilities for FIX session testing.
 */
public class TestContext {

    private static final Logger log = LoggerFactory.getLogger(TestContext.class);

    private final FixEngine engine;
    private final FixSession session;
    private final long defaultTimeoutMs;

    public TestContext(FixEngine engine, FixSession session, long defaultTimeoutMs) {
        this.engine = engine;
        this.session = session;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * Get the FIX engine.
     */
    public FixEngine getEngine() {
        return engine;
    }

    /**
     * Get the test session.
     */
    public FixSession getSession() {
        return session;
    }

    /**
     * Get the session ID.
     */
    public String getSessionId() {
        return session.getConfig().getSessionId();
    }

    /**
     * Wait for the session to reach a specific state.
     *
     * @param targetState the state to wait for
     * @param timeoutMs timeout in milliseconds
     * @return true if the state was reached, false if timeout
     */
    public boolean waitForState(SessionState targetState, long timeoutMs) {
        if (session.getState() == targetState) {
            return true;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<SessionState> reachedState = new AtomicReference<>();

        SessionStateListener listener = new SessionStateListener() {
            @Override
            public void onSessionStateChange(FixSession s, SessionState oldState, SessionState newState) {
                if (s == session && newState == targetState) {
                    reachedState.set(newState);
                    latch.countDown();
                }
            }
        };

        session.addStateListener(listener);
        try {
            // Check again in case state changed while adding listener
            if (session.getState() == targetState) {
                return true;
            }
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            session.removeStateListener(listener);
        }
    }

    /**
     * Wait for the session to be logged on.
     */
    public boolean waitForLogon(long timeoutMs) {
        return waitForState(SessionState.LOGGED_ON, timeoutMs);
    }

    /**
     * Wait for the session to be logged on using default timeout.
     */
    public boolean waitForLogon() {
        return waitForLogon(defaultTimeoutMs);
    }

    /**
     * Wait for the session to be disconnected.
     */
    public boolean waitForDisconnect(long timeoutMs) {
        return waitForState(SessionState.DISCONNECTED, timeoutMs);
    }

    /**
     * Wait for the session to be disconnected using default timeout.
     */
    public boolean waitForDisconnect() {
        return waitForDisconnect(defaultTimeoutMs);
    }

    /**
     * Wait for the session to be connected (TCP level).
     */
    public boolean waitForConnect(long timeoutMs) {
        // Wait for any connected state
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (session.getState().isConnected()) {
                return true;
            }
            sleep(50);
        }
        return false;
    }

    /**
     * Sleep for the specified duration.
     */
    public void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Connect the session (for initiator sessions).
     */
    public void connect() {
        engine.connect(getSessionId());
    }

    /**
     * Disconnect the session.
     */
    public void disconnect() {
        session.disconnect("Test disconnect");
    }

    /**
     * Send a logout message.
     */
    public void logout(String reason) {
        session.logout(reason);
    }

    /**
     * Send a test request and return the TestReqID.
     */
    public String sendTestRequest() {
        return session.sendTestRequest();
    }

    /**
     * Try to claim a ring buffer message for sending.
     *
     * @param msgType the message type
     * @return the ring buffer message, or null if buffer is full
     */
    public RingBufferOutgoingMessage tryClaimMessage(String msgType) {
        return session.tryClaimMessage(msgType);
    }

    /**
     * Commit a ring buffer message.
     *
     * @param message the message to commit
     */
    public void commitMessage(RingBufferOutgoingMessage message) {
        session.commitMessage(message);
    }

    /**
     * Get the current outgoing sequence number.
     */
    public int getOutgoingSeqNum() {
        return session.getOutgoingSeqNum();
    }

    /**
     * Get the expected incoming sequence number.
     */
    public int getExpectedIncomingSeqNum() {
        return session.getExpectedIncomingSeqNum();
    }

    /**
     * Set the outgoing sequence number.
     */
    public void setOutgoingSeqNum(int seqNum) {
        session.setOutgoingSeqNum(seqNum);
    }

    /**
     * Set the expected incoming sequence number.
     */
    public void setExpectedIncomingSeqNum(int seqNum) {
        session.setExpectedIncomingSeqNum(seqNum);
    }

    /**
     * Reset sequence numbers to 1.
     */
    public void resetSequenceNumbers() {
        session.resetSequenceNumbers();
    }

    /**
     * Get the current session state.
     */
    public SessionState getState() {
        return session.getState();
    }

    /**
     * Check if the session is logged on.
     */
    public boolean isLoggedOn() {
        return session.getState().isLoggedOn();
    }

    /**
     * Check if the session is connected (TCP level).
     */
    public boolean isConnected() {
        return session.getState().isConnected();
    }

    /**
     * Get the default timeout in milliseconds.
     */
    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }
}
