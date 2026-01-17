package com.fixengine.engine.session;

/**
 * Listener for session state changes.
 */
public interface SessionStateListener {

    /**
     * Called when the session state changes.
     *
     * @param session the session
     * @param oldState the previous state
     * @param newState the new state
     */
    void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState);

    /**
     * Called when a session is created.
     *
     * @param session the created session
     */
    default void onSessionCreated(FixSession session) {
    }

    /**
     * Called when a session connects (TCP level).
     *
     * @param session the session
     */
    default void onSessionConnected(FixSession session) {
    }

    /**
     * Called when a session completes logon.
     *
     * @param session the session
     */
    default void onSessionLogon(FixSession session) {
    }

    /**
     * Called when a session logs out.
     *
     * @param session the session
     * @param reason the logout reason (may be null)
     */
    default void onSessionLogout(FixSession session, String reason) {
    }

    /**
     * Called when a session disconnects.
     *
     * @param session the session
     * @param reason the disconnect reason (may be null)
     */
    default void onSessionDisconnected(FixSession session, Throwable reason) {
    }

    /**
     * Called when a session error occurs.
     *
     * @param session the session
     * @param error the error
     */
    default void onSessionError(FixSession session, Throwable error) {
    }
}
