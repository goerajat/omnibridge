package com.fixengine.engine.session;

/**
 * FIX session state.
 */
public enum SessionState {
    /**
     * Session is created but not started.
     */
    CREATED,

    /**
     * Session is disconnected and waiting to connect/accept.
     */
    DISCONNECTED,

    /**
     * Initiator is connecting to the acceptor.
     */
    CONNECTING,

    /**
     * TCP connection established, waiting for logon.
     */
    CONNECTED,

    /**
     * Logon sent, waiting for logon response.
     */
    LOGON_SENT,

    /**
     * Logon received, session is active.
     */
    LOGGED_ON,

    /**
     * Logout sent, waiting for logout response.
     */
    LOGOUT_SENT,

    /**
     * Session is being reset (resend request in progress).
     */
    RESENDING,

    /**
     * Session is stopped.
     */
    STOPPED;

    /**
     * Check if the session is in a connected state (TCP level).
     */
    public boolean isConnected() {
        return this == CONNECTED || this == LOGON_SENT || this == LOGGED_ON ||
                this == LOGOUT_SENT || this == RESENDING;
    }

    /**
     * Check if the session is logged on.
     */
    public boolean isLoggedOn() {
        return this == LOGGED_ON || this == RESENDING;
    }

    /**
     * Check if the session can send application messages.
     */
    public boolean canSendAppMessage() {
        return this == LOGGED_ON;
    }
}
