package com.fixengine.engine.session;

import com.fixengine.config.session.DefaultSessionManagementService;
import com.fixengine.config.session.ManagedSession;
import com.fixengine.config.session.SessionConnectionState;

import java.net.InetSocketAddress;

/**
 * Adapter that wraps a {@link FixSession} to provide the {@link ManagedSession} interface.
 *
 * <p>This adapter maps FIX-specific session states and operations to the common
 * session management interface, enabling unified session management across
 * different protocols.</p>
 *
 * <p>State mapping:</p>
 * <ul>
 *   <li>CREATED, DISCONNECTED → DISCONNECTED</li>
 *   <li>CONNECTING → CONNECTING</li>
 *   <li>CONNECTED, LOGON_SENT → CONNECTED</li>
 *   <li>LOGGED_ON, RESENDING → LOGGED_ON</li>
 *   <li>LOGOUT_SENT, STOPPED → STOPPED</li>
 * </ul>
 */
public class FixSessionAdapter implements ManagedSession, SessionStateListener {

    private static final String PROTOCOL_TYPE = "FIX";

    private final FixSession session;
    private final DefaultSessionManagementService managementService;
    private volatile boolean enabled = true;
    private volatile SessionConnectionState lastState;

    /**
     * Create a new adapter for the given FIX session.
     *
     * @param session the FIX session to wrap
     * @param managementService the management service for state change notifications
     */
    public FixSessionAdapter(FixSession session, DefaultSessionManagementService managementService) {
        this.session = session;
        this.managementService = managementService;
        this.lastState = mapState(session.getState());

        // Register as listener to forward state changes
        session.addStateListener(this);
    }

    // ========== Identity ==========

    @Override
    public String getSessionId() {
        return session.getConfig().getSessionId();
    }

    @Override
    public String getSessionName() {
        return session.getConfig().getSessionId();
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
        return session.getState().isLoggedOn();
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
            session.disconnect("Session disabled");
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // ========== Sequence Numbers ==========

    @Override
    public long incomingSeqNum() {
        return session.getExpectedIncomingSeqNum();
    }

    @Override
    public long outgoingSeqNum() {
        return session.getOutgoingSeqNum();
    }

    @Override
    public void setIncomingSeqNum(long seqNum) {
        session.setExpectedIncomingSeqNum((int) seqNum);
    }

    @Override
    public void setOutgoingSeqNum(long seqNum) {
        session.setOutgoingSeqNum((int) seqNum);
    }

    // ========== Connection Address ==========

    @Override
    public InetSocketAddress connectionAddress() {
        String host = session.getConfig().getHost();
        int port = session.getConfig().getPort();
        if (host != null && port > 0) {
            return new InetSocketAddress(host, port);
        }
        return null;
    }

    @Override
    public InetSocketAddress connectedAddress() {
        // For FIX sessions, return the configured address if connected
        if (isConnected()) {
            return connectionAddress();
        }
        return null;
    }

    @Override
    public void updateConnectionAddress(String host, int port) {
        // FIX session configuration is immutable after creation
        // This would require reconnecting with a new configuration
        throw new UnsupportedOperationException(
                "FIX session connection address cannot be updated dynamically");
    }

    // ========== Underlying Session Access ==========

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap() {
        return (T) session;
    }

    // ========== SessionStateListener Implementation ==========

    @Override
    public void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState) {
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
     * Map FIX session state to common session connection state.
     *
     * @param state the FIX session state
     * @return the mapped connection state
     */
    private static SessionConnectionState mapState(SessionState state) {
        return switch (state) {
            case CREATED, DISCONNECTED -> SessionConnectionState.DISCONNECTED;
            case CONNECTING -> SessionConnectionState.CONNECTING;
            case CONNECTED, LOGON_SENT -> SessionConnectionState.CONNECTED;
            case LOGGED_ON, RESENDING -> SessionConnectionState.LOGGED_ON;
            case LOGOUT_SENT, STOPPED -> SessionConnectionState.STOPPED;
        };
    }

    @Override
    public String toString() {
        return "FixSessionAdapter{" +
                "sessionId=" + getSessionId() +
                ", state=" + getConnectionState() +
                ", enabled=" + enabled +
                '}';
    }
}
