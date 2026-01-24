package com.fixengine.apps.fix.initiator;

import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.SessionState;
import com.fixengine.engine.session.SessionStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Session state listener for the FIX initiator.
 * Logs session state changes and signals logon completion.
 */
public class InitiatorStateListener implements SessionStateListener {

    private static final Logger log = LoggerFactory.getLogger(InitiatorStateListener.class);

    private final CountDownLatch logonLatch;
    private final Consumer<String> onLogout;

    /**
     * Create a state listener with a logon latch.
     *
     * @param logonLatch countdown latch to signal when logon completes
     */
    public InitiatorStateListener(CountDownLatch logonLatch) {
        this(logonLatch, null);
    }

    /**
     * Create a state listener with a logon latch and logout callback.
     *
     * @param logonLatch countdown latch to signal when logon completes
     * @param onLogout callback when logout occurs (may be null)
     */
    public InitiatorStateListener(CountDownLatch logonLatch, Consumer<String> onLogout) {
        this.logonLatch = logonLatch;
        this.onLogout = onLogout;
    }

    @Override
    public void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState) {
        log.info("Session state changed: {} -> {}", oldState, newState);
    }

    @Override
    public void onSessionLogon(FixSession session) {
        log.info("Logged on!");
        logonLatch.countDown();
    }

    @Override
    public void onSessionLogout(FixSession session, String reason) {
        log.info("Logged out: {}", reason);
        if (onLogout != null) {
            onLogout.accept(reason);
        }
    }

    @Override
    public void onSessionDisconnected(FixSession session, Throwable reason) {
        log.info("Disconnected: {}", reason != null ? reason.getMessage() : "clean disconnect");
    }

    @Override
    public void onSessionError(FixSession session, Throwable error) {
        log.error("Session error", error);
    }
}
