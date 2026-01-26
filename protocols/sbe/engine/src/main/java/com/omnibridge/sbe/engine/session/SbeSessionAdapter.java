package com.omnibridge.sbe.engine.session;

import com.omnibridge.config.session.DefaultSessionManagementService;
import com.omnibridge.config.session.ManagedSession;
import com.omnibridge.config.session.SessionConnectionState;

import java.net.InetSocketAddress;

/**
 * Adapter that wraps an {@link SbeSession} to provide the {@link ManagedSession} interface.
 * <p>
 * This adapter maps SBE-specific session states and operations to the common
 * session management interface, enabling unified session management across
 * different protocols.
 * <p>
 * State mapping:
 * <ul>
 *   <li>CREATED, DISCONNECTED, TERMINATED → DISCONNECTED</li>
 *   <li>CONNECTING → CONNECTING</li>
 *   <li>CONNECTED, NEGOTIATING, ESTABLISHING → CONNECTED</li>
 *   <li>ESTABLISHED → LOGGED_ON</li>
 *   <li>TERMINATING, STOPPED → STOPPED</li>
 * </ul>
 */
public class SbeSessionAdapter implements ManagedSession, SbeSession.SessionStateListener {

    private static final String PROTOCOL_TYPE = "SBE";

    private final SbeSession<?> session;
    private final DefaultSessionManagementService managementService;
    private volatile boolean enabled = true;
    private volatile SessionConnectionState lastState;

    /**
     * Creates an adapter for the given SBE session.
     *
     * @param session the SBE session to adapt
     * @param managementService the session management service (may be null)
     */
    public SbeSessionAdapter(SbeSession<?> session, DefaultSessionManagementService managementService) {
        this.session = session;
        this.managementService = managementService;
        this.lastState = mapState(session.getSessionState());

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
        return session.getSessionState().isConnected();
    }

    @Override
    public boolean isLoggedOn() {
        return session.getSessionState().isEstablished();
    }

    @Override
    public SessionConnectionState getConnectionState() {
        return mapState(session.getSessionState());
    }

    // ========== Session Control ==========

    @Override
    public void enable() {
        this.enabled = true;
    }

    @Override
    public void disable() {
        this.enabled = false;
        if (isConnected()) {
            session.disconnect();
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // ========== Sequence Numbers ==========

    @Override
    public long incomingSeqNum() {
        return session.getInboundSeqNum();
    }

    @Override
    public long outgoingSeqNum() {
        return session.getOutboundSeqNum();
    }

    @Override
    public void setIncomingSeqNum(long seqNum) {
        session.setInboundSeqNum(seqNum);
    }

    @Override
    public void setOutgoingSeqNum(long seqNum) {
        session.setOutboundSeqNum(seqNum);
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
        if (isConnected()) {
            return connectionAddress();
        }
        return null;
    }

    @Override
    public void updateConnectionAddress(String host, int port) {
        throw new UnsupportedOperationException(
                "SBE session connection address cannot be updated dynamically");
    }

    // ========== Underlying Session Access ==========

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap() {
        return (T) session;
    }

    // ========== SessionStateListener Implementation ==========

    @Override
    public void onStateChange(SbeSession<?> session, SbeSessionState oldState, SbeSessionState newState) {
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
     * Maps SBE session state to common session connection state.
     *
     * @param state the SBE session state
     * @return the mapped connection state
     */
    private static SessionConnectionState mapState(SbeSessionState state) {
        return switch (state) {
            case CREATED, DISCONNECTED, TERMINATED -> SessionConnectionState.DISCONNECTED;
            case CONNECTING -> SessionConnectionState.CONNECTING;
            case CONNECTED, NEGOTIATING, ESTABLISHING -> SessionConnectionState.CONNECTED;
            case ESTABLISHED -> SessionConnectionState.LOGGED_ON;
            case TERMINATING, STOPPED -> SessionConnectionState.STOPPED;
        };
    }

    @Override
    public String toString() {
        return "SbeSessionAdapter{" +
                "sessionId=" + getSessionId() +
                ", state=" + getConnectionState() +
                ", enabled=" + enabled +
                '}';
    }
}
