package com.fixengine.config.session;

/**
 * Common connection state for all managed sessions.
 *
 * <p>This enum provides a unified view of session state across different
 * protocols (FIX, OUCH, etc.), abstracting protocol-specific state details.</p>
 */
public enum SessionConnectionState {

    /**
     * Session is not connected.
     */
    DISCONNECTED,

    /**
     * Session is attempting to connect.
     */
    CONNECTING,

    /**
     * TCP connection established but not yet logged on.
     */
    CONNECTED,

    /**
     * Session is fully logged on and operational.
     */
    LOGGED_ON,

    /**
     * Session is stopped and will not reconnect.
     */
    STOPPED;

    /**
     * Check if the session has a TCP connection established.
     *
     * @return true if connected (CONNECTED or LOGGED_ON)
     */
    public boolean isConnected() {
        return this == CONNECTED || this == LOGGED_ON;
    }

    /**
     * Check if the session is fully logged on and operational.
     *
     * @return true if logged on
     */
    public boolean isLoggedOn() {
        return this == LOGGED_ON;
    }

    /**
     * Check if the session is in a terminal state.
     *
     * @return true if stopped
     */
    public boolean isTerminal() {
        return this == STOPPED;
    }
}
