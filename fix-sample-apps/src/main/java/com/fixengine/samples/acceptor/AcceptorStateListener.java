package com.fixengine.samples.acceptor;

import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.SessionState;
import com.fixengine.engine.session.SessionStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session state listener for the FIX acceptor.
 * Logs session state changes and connection events.
 */
public class AcceptorStateListener implements SessionStateListener {

    private static final Logger log = LoggerFactory.getLogger(AcceptorStateListener.class);

    private final AcceptorMessageListener messageListener;

    /**
     * Create a state listener without message listener reference.
     */
    public AcceptorStateListener() {
        this(null);
    }

    /**
     * Create a state listener with message listener reference for statistics.
     *
     * @param messageListener the message listener to get statistics from (may be null)
     */
    public AcceptorStateListener(AcceptorMessageListener messageListener) {
        this.messageListener = messageListener;
    }

    @Override
    public void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState) {
        log.info("Session {} state changed: {} -> {}", session.getConfig().getSessionId(), oldState, newState);
    }

    @Override
    public void onSessionLogon(FixSession session) {
        log.info("Session {} logged on", session.getConfig().getSessionId());
    }

    @Override
    public void onSessionLogout(FixSession session, String reason) {
        log.info("Session {} logged out: {}", session.getConfig().getSessionId(), reason);

        // Log statistics on logout
        if (messageListener != null) {
            logStatistics();
        }
    }

    @Override
    public void onSessionError(FixSession session, Throwable error) {
        log.error("Session {} error", session.getConfig().getSessionId(), error);
    }

    private void logStatistics() {
        log.error("============================================================");
        log.error("Acceptor Statistics");
        log.error("============================================================");
        log.error("  Orders received:           {}", messageListener.getOrdersReceived());
        log.error("  Cancel requests received:  {}", messageListener.getCancelRequestsReceived());
        log.error("  Replace requests received: {}", messageListener.getReplaceRequestsReceived());
        log.error("  Total messages received:   {}", messageListener.getTotalMessagesReceived());
        log.error("  Execution reports sent:    {}", messageListener.getExecutionReportsSent());
        log.error("============================================================");
    }
}
