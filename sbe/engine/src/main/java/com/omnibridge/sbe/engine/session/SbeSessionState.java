package com.omnibridge.sbe.engine.session;

/**
 * SBE session state enumeration.
 * <p>
 * Unlike FIX which has a complex logon sequence, SBE protocols like iLink 3
 * use a negotiation/establishment model. This state tracks the session lifecycle
 * common to SBE-based protocols.
 * <p>
 * Typical state flow:
 * <pre>
 * CREATED -> CONNECTING -> CONNECTED -> NEGOTIATING -> ESTABLISHED -> LOGGED_OUT/DISCONNECTED
 * </pre>
 * <p>
 * Protocol-specific implementations may add additional states or transitions.
 */
public enum SbeSessionState {

    /** Session created but not connected */
    CREATED,

    /** TCP connection failed or lost */
    DISCONNECTED,

    /** Attempting to establish TCP connection */
    CONNECTING,

    /** TCP connection established, awaiting negotiation */
    CONNECTED,

    /** Negotiation in progress (protocol-specific handshake) */
    NEGOTIATING,

    /** Establishment in progress (session initialization) */
    ESTABLISHING,

    /** Fully established and operational */
    ESTABLISHED,

    /** Termination initiated, awaiting confirmation */
    TERMINATING,

    /** Session terminated gracefully */
    TERMINATED,

    /** Session permanently stopped */
    STOPPED;

    /**
     * Check if TCP connection is established.
     *
     * @return true if underlying TCP connection is active
     */
    public boolean isConnected() {
        return switch (this) {
            case CONNECTED, NEGOTIATING, ESTABLISHING, ESTABLISHED, TERMINATING -> true;
            default -> false;
        };
    }

    /**
     * Check if session is fully established.
     *
     * @return true if session is ready for application messages
     */
    public boolean isEstablished() {
        return this == ESTABLISHED;
    }

    /**
     * Check if session can send application messages.
     *
     * @return true if session accepts outbound application messages
     */
    public boolean canSendMessages() {
        return this == ESTABLISHED;
    }

    /**
     * Check if session is in a terminal state.
     *
     * @return true if session cannot be restarted
     */
    public boolean isTerminal() {
        return this == STOPPED;
    }

    /**
     * Check if session is in a disconnected or terminated state.
     *
     * @return true if session is not active
     */
    public boolean isInactive() {
        return switch (this) {
            case CREATED, DISCONNECTED, TERMINATED, STOPPED -> true;
            default -> false;
        };
    }

    /**
     * Check if session is in handshake phase (negotiating or establishing).
     *
     * @return true if session is performing protocol handshake
     */
    public boolean isHandshaking() {
        return this == NEGOTIATING || this == ESTABLISHING;
    }
}
