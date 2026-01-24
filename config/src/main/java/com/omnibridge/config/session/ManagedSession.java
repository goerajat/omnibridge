package com.omnibridge.config.session;

import java.net.InetSocketAddress;

/**
 * Unified interface for managing sessions across different protocols.
 *
 * <p>This interface provides a common API for session management regardless of
 * the underlying protocol (FIX, OUCH, etc.). It abstracts protocol-specific
 * details while providing core session operations.</p>
 *
 * <p>Implementations wrap protocol-specific session objects and adapt their
 * behavior to this common interface.</p>
 */
public interface ManagedSession {

    // ========== Identity ==========

    /**
     * Get the unique session identifier.
     *
     * <p>For FIX sessions, this is typically in the format "SenderCompID-TargetCompID".
     * For OUCH sessions, this may be the session name or a similar identifier.</p>
     *
     * @return the unique session ID
     */
    String getSessionId();

    /**
     * Get the human-readable session name.
     *
     * @return the session name
     */
    String getSessionName();

    /**
     * Get the protocol type for this session.
     *
     * @return the protocol type (e.g., "FIX", "OUCH")
     */
    String getProtocolType();

    // ========== Connection State ==========

    /**
     * Check if the session has an active TCP connection.
     *
     * @return true if connected at TCP level
     */
    boolean isConnected();

    /**
     * Check if the session is fully logged on and operational.
     *
     * @return true if logged on
     */
    boolean isLoggedOn();

    /**
     * Get the current connection state.
     *
     * @return the current session connection state
     */
    SessionConnectionState getConnectionState();

    // ========== Session Control ==========

    /**
     * Enable the session, allowing it to connect.
     */
    void enable();

    /**
     * Disable the session, preventing it from connecting.
     */
    void disable();

    /**
     * Check if the session is enabled.
     *
     * @return true if the session is enabled
     */
    boolean isEnabled();

    // ========== Sequence Numbers ==========

    /**
     * Get the expected incoming sequence number.
     *
     * <p>For protocols with bidirectional sequence numbers (FIX), this returns
     * the expected incoming sequence number. For protocols with a single sequence
     * number (OUCH), this returns the same value as {@link #outgoingSeqNum()}.</p>
     *
     * @return the expected incoming sequence number
     */
    long incomingSeqNum();

    /**
     * Get the outgoing sequence number.
     *
     * @return the outgoing sequence number
     */
    long outgoingSeqNum();

    /**
     * Set the expected incoming sequence number.
     *
     * @param seqNum the new incoming sequence number
     */
    void setIncomingSeqNum(long seqNum);

    /**
     * Set the outgoing sequence number.
     *
     * @param seqNum the new outgoing sequence number
     */
    void setOutgoingSeqNum(long seqNum);

    // ========== Connection Address ==========

    /**
     * Get the configured connection address.
     *
     * <p>This is the address the session is configured to connect to (initiator)
     * or bind to (acceptor).</p>
     *
     * @return the configured address, or null if not configured
     */
    InetSocketAddress connectionAddress();

    /**
     * Get the currently connected address.
     *
     * <p>This is the actual address the session is connected to. May differ
     * from the configured address if DNS resolution changed or if the
     * address was updated dynamically.</p>
     *
     * @return the connected address, or null if not connected
     */
    InetSocketAddress connectedAddress();

    /**
     * Update the connection address.
     *
     * <p>This updates the configured host and port for the next connection
     * attempt. Does not affect an active connection.</p>
     *
     * @param host the new host
     * @param port the new port
     */
    void updateConnectionAddress(String host, int port);

    // ========== Underlying Session Access ==========

    /**
     * Get the underlying protocol-specific session object.
     *
     * <p>This allows access to protocol-specific functionality not exposed
     * through this interface. Use with caution as it couples code to
     * specific protocol implementations.</p>
     *
     * @param <T> the expected session type
     * @return the underlying session object
     */
    <T> T unwrap();
}
