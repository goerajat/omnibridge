package com.fixengine.ouch.engine.session;

/**
 * OUCH session state enumeration.
 *
 * <p>Unlike FIX, OUCH typically uses SoupBinTCP for transport which has its own
 * login/logout mechanism. This state tracks the session lifecycle.</p>
 */
public enum SessionState {
    /** Session created but not connected */
    CREATED,
    /** TCP connection failed or lost */
    DISCONNECTED,
    /** Attempting to establish TCP connection */
    CONNECTING,
    /** TCP connection established, awaiting login */
    CONNECTED,
    /** Login request sent, awaiting response */
    LOGIN_SENT,
    /** Fully logged in and operational */
    LOGGED_IN,
    /** Logout initiated, awaiting confirmation */
    LOGOUT_SENT,
    /** Session permanently stopped */
    STOPPED;

    /**
     * Check if TCP connection is established.
     */
    public boolean isConnected() {
        return switch (this) {
            case CONNECTED, LOGIN_SENT, LOGGED_IN, LOGOUT_SENT -> true;
            default -> false;
        };
    }

    /**
     * Check if session is fully logged in.
     */
    public boolean isLoggedIn() {
        return this == LOGGED_IN;
    }

    /**
     * Check if session can send application messages (orders).
     */
    public boolean canSendOrders() {
        return this == LOGGED_IN;
    }

    /**
     * Check if session is in a terminal state.
     */
    public boolean isTerminal() {
        return this == STOPPED;
    }
}
