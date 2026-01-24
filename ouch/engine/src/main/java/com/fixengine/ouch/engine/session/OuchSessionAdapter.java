package com.fixengine.ouch.engine.session;

import com.fixengine.config.session.DefaultSessionManagementService;
import com.fixengine.config.session.ManagedSession;
import com.fixengine.config.session.SessionConnectionState;

import java.net.InetSocketAddress;

/**
 * Adapter that wraps an {@link OuchSession} to provide the {@link ManagedSession} interface.
 *
 * <p>This adapter maps OUCH-specific session states and operations to the common
 * session management interface, enabling unified session management across
 * different protocols.</p>
 *
 * <p>State mapping:</p>
 * <ul>
 *   <li>CREATED, DISCONNECTED → DISCONNECTED</li>
 *   <li>CONNECTING → CONNECTING</li>
 *   <li>CONNECTED, LOGIN_SENT → CONNECTED</li>
 *   <li>LOGGED_IN → LOGGED_ON</li>
 *   <li>LOGOUT_SENT, STOPPED → STOPPED</li>
 * </ul>
 *
 * <p>Note: OUCH uses a single sequence number for message sequencing, so both
 * {@link #incomingSeqNum()} and {@link #outgoingSeqNum()} return the same value.</p>
 */
public class OuchSessionAdapter implements ManagedSession, OuchSession.SessionStateListener {

    private static final String PROTOCOL_TYPE = "OUCH";

    private final OuchSession session;
    private final DefaultSessionManagementService managementService;
    private volatile boolean enabled = true;
    private volatile SessionConnectionState lastState;

    /**
     * Create a new adapter for the given OUCH session.
     *
     * @param session the OUCH session to wrap
     * @param managementService the management service for state change notifications
     */
    public OuchSessionAdapter(OuchSession session, DefaultSessionManagementService managementService) {
        this.session = session;
        this.managementService = managementService;
        this.lastState = mapState(session.getState());

        // Register as listener to forward state changes
        session.addStateListener(this);
    }

    // ========== Identity ==========

    @Override
    public String getSessionId() {
        return session.getSessionId();
    }

    @Override
    public String getSessionName() {
        return session.getSessionName();
    }

    @Override
    public String getProtocolType() {
        return PROTOCOL_TYPE;
    }

    // ========== Connection State ==========

    @Override
    public boolean isConnected() {
        return session.getState().isConnected();
    }

    @Override
    public boolean isLoggedOn() {
        return session.getState().isLoggedIn();
    }

    @Override
    public SessionConnectionState getConnectionState() {
        return mapState(session.getState());
    }

    // ========== Session Control ==========

    @Override
    public void enable() {
        this.enabled = true;
    }

    @Override
    public void disable() {
        this.enabled = false;
        // Disconnect if currently connected
        if (isConnected()) {
            session.disconnect();
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // ========== Sequence Numbers ==========

    /**
     * Get the sequence number.
     *
     * <p>OUCH uses a single sequence number for message tracking, so this returns
     * the same value as {@link #outgoingSeqNum()}.</p>
     *
     * @return the current sequence number
     */
    @Override
    public long incomingSeqNum() {
        // OUCH uses a single sequence number
        return outgoingSeqNum();
    }

    @Override
    public long outgoingSeqNum() {
        // Note: OuchSession uses a private sequenceNumber field
        // We cannot access it directly, but for OUCH the sequence is typically
        // managed by the underlying transport (SoupBinTCP)
        return 0; // Would need getter in OuchSession
    }

    @Override
    public void setIncomingSeqNum(long seqNum) {
        // OUCH sequence numbers are managed by the transport layer
        // This is a no-op for OUCH sessions
    }

    @Override
    public void setOutgoingSeqNum(long seqNum) {
        // OUCH sequence numbers are managed by the transport layer
        // This is a no-op for OUCH sessions
    }

    // ========== Connection Address ==========

    @Override
    public InetSocketAddress connectionAddress() {
        String host = session.getHost();
        int port = session.getPort();
        if (host != null && port > 0) {
            return new InetSocketAddress(host, port);
        }
        return null;
    }

    @Override
    public InetSocketAddress connectedAddress() {
        // For OUCH sessions, return the configured address if connected
        if (isConnected()) {
            return connectionAddress();
        }
        return null;
    }

    @Override
    public void updateConnectionAddress(String host, int port) {
        // OUCH session configuration is immutable after creation
        throw new UnsupportedOperationException(
                "OUCH session connection address cannot be updated dynamically");
    }

    // ========== Underlying Session Access ==========

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap() {
        return (T) session;
    }

    // ========== SessionStateListener Implementation ==========

    @Override
    public void onStateChange(OuchSession session, SessionState oldState, SessionState newState) {
        SessionConnectionState oldMappedState = mapState(oldState);
        SessionConnectionState newMappedState = mapState(newState);

        if (oldMappedState != newMappedState) {
            lastState = newMappedState;
            if (managementService != null) {
                managementService.notifyStateChange(this, oldMappedState, newMappedState);
            }
        }
    }

    // ========== State Mapping ==========

    /**
     * Map OUCH session state to common session connection state.
     *
     * @param state the OUCH session state
     * @return the mapped connection state
     */
    private static SessionConnectionState mapState(SessionState state) {
        return switch (state) {
            case CREATED, DISCONNECTED -> SessionConnectionState.DISCONNECTED;
            case CONNECTING -> SessionConnectionState.CONNECTING;
            case CONNECTED, LOGIN_SENT -> SessionConnectionState.CONNECTED;
            case LOGGED_IN -> SessionConnectionState.LOGGED_ON;
            case LOGOUT_SENT, STOPPED -> SessionConnectionState.STOPPED;
        };
    }

    @Override
    public String toString() {
        return "OuchSessionAdapter{" +
                "sessionId=" + getSessionId() +
                ", state=" + getConnectionState() +
                ", enabled=" + enabled +
                ", protocolVersion=" + session.getProtocolVersion() +
                '}';
    }
}
