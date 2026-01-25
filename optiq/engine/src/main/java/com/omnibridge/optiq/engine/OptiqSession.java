package com.omnibridge.optiq.engine;

import com.omnibridge.optiq.engine.config.OptiqSessionConfig;
import com.omnibridge.optiq.message.*;
import com.omnibridge.optiq.message.order.*;
import com.omnibridge.optiq.message.session.*;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.sbe.engine.session.SbeSession;
import com.omnibridge.sbe.engine.session.SbeSessionState;
import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.sbe.message.SbeMessageFactory;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Euronext Optiq session implementation.
 * <p>
 * Manages the session lifecycle with the Optiq Order Entry Gateway (OEG)
 * using the Logon/Logout session model.
 * <p>
 * State transitions:
 * <pre>
 * CREATED -> CONNECTING -> CONNECTED -> NEGOTIATING (Logon) -> ESTABLISHED (LogonAck)
 *                                            |                      |
 *                                   DISCONNECTED              TERMINATING (Logout)
 *                                                                   |
 *                                                              TERMINATED
 * </pre>
 */
public class OptiqSession extends SbeSession<OptiqSessionConfig> {

    private static final Logger log = LoggerFactory.getLogger(OptiqSession.class);

    private static final OptiqMessageFactory MESSAGE_FACTORY = new OptiqMessageFactory();

    /** Logical access ID for this session */
    private volatile int logicalAccessId;

    /** Server-assigned heartbeat interval */
    private volatile int heartbeatInterval;

    /** Last time a message was sent */
    private volatile long lastSentTime;

    /** Last time a message was received */
    private volatile long lastReceivedTime;

    /**
     * Creates a new Optiq session.
     *
     * @param config the session configuration
     * @param logStore the log store for message persistence (may be null)
     */
    public OptiqSession(OptiqSessionConfig config, LogStore logStore) {
        super(config, logStore);
        this.logicalAccessId = config.getLogicalAccessId();
        this.heartbeatInterval = config.getHeartbeatInterval();
    }

    @Override
    public SbeMessageFactory getMessageFactory() {
        return MESSAGE_FACTORY;
    }

    @Override
    protected int getExpectedMessageLength(DirectBuffer buffer, int offset, int available) {
        if (available < OptiqMessage.FRAMING_HEADER_SIZE) {
            return -1;
        }

        // Read message length from framing header
        int messageLength = buffer.getShort(offset, SbeMessage.BYTE_ORDER) & 0xFFFF;
        int totalLength = OptiqMessage.FRAMING_HEADER_SIZE + messageLength;

        if (available < totalLength) {
            return -1;
        }

        return totalLength;
    }

    @Override
    protected void initiateHandshake() {
        log.info("[{}] Initiating Optiq logon", sessionId);
        forceState(SbeSessionState.NEGOTIATING);
        sendLogon();
    }

    @Override
    protected void processIncomingMessage(SbeMessage message) {
        lastReceivedTime = System.currentTimeMillis();

        if (!(message instanceof OptiqMessage)) {
            log.warn("[{}] Received non-Optiq message: {}", sessionId, message);
            return;
        }

        OptiqMessageType msgType = ((OptiqMessage) message).getMessageType();
        log.debug("[{}] Processing {}", sessionId, msgType);

        switch (msgType) {
            case LOGON_ACK -> handleLogonAck((LogonAckMessage) message);
            case LOGON_REJECT -> handleLogonReject(message);
            case LOGOUT -> handleLogout((LogoutMessage) message);
            case HEARTBEAT -> handleHeartbeat((HeartbeatMessage) message);
            case EXECUTION_REPORT -> handleExecutionReport((ExecutionReportMessage) message);
            case REJECT -> handleReject((RejectMessage) message);
            default -> {
                log.debug("[{}] Received application message: {}", sessionId, msgType);
                notifyMessage(message);
            }
        }
    }

    // =====================================================
    // Session Messages
    // =====================================================

    /**
     * Sends a Logon message to initiate the session.
     */
    private void sendLogon() {
        LogonMessage msg = tryClaim(LogonMessage.class);
        if (msg == null) {
            log.warn("[{}] Failed to claim Logon message", sessionId);
            return;
        }

        try {
            msg.writeHeader();
            msg.setLogicalAccessId(logicalAccessId)
               .setOegInInstance(config.getOegInstance())
               .setLastMsgSeqNum(getOutboundSeqNum())
               .setSoftwareProvider(config.getSoftwareProvider() != null ?
                       config.getSoftwareProvider() : "OmniBridge")
               .setHeartbeatInterval(heartbeatInterval);

            if (commit(msg)) {
                log.info("[{}] Sent Logon: logicalAccessId={}", sessionId, logicalAccessId);
            }
        } catch (Exception e) {
            abort(msg);
            log.error("[{}] Error sending Logon", sessionId, e);
        }
    }

    /**
     * Handles a LogonAck message.
     */
    private void handleLogonAck(LogonAckMessage msg) {
        if (state.get() != SbeSessionState.NEGOTIATING) {
            log.warn("[{}] Unexpected LogonAck in state {}", sessionId, state.get());
            return;
        }

        int responseLogicalAccessId = msg.getLogicalAccessId();
        long lastMsgSeqNum = msg.getLastMsgSeqNum();
        int serverHeartbeat = msg.getHeartbeatInterval();

        log.info("[{}] Received LogonAck: logicalAccessId={}, lastSeqNum={}, heartbeat={}",
                sessionId, responseLogicalAccessId, lastMsgSeqNum, serverHeartbeat);

        this.logicalAccessId = responseLogicalAccessId;
        this.heartbeatInterval = serverHeartbeat;
        setInboundSeqNum(lastMsgSeqNum);

        forceState(SbeSessionState.ESTABLISHED);
        log.info("[{}] Session established", sessionId);
    }

    /**
     * Handles a LogonReject message.
     */
    private void handleLogonReject(SbeMessage msg) {
        log.error("[{}] Logon rejected", sessionId);
        forceState(SbeSessionState.DISCONNECTED);
    }

    /**
     * Handles a Logout message.
     */
    private void handleLogout(LogoutMessage msg) {
        int reasonCode = msg.getLogoutReasonCode();
        String reason = msg.getLogoutReasonText();

        log.info("[{}] Received Logout: reasonCode={}, reason={}", sessionId, reasonCode, reason);

        forceState(SbeSessionState.TERMINATED);
    }

    /**
     * Handles a Heartbeat message.
     */
    private void handleHeartbeat(HeartbeatMessage msg) {
        log.debug("[{}] Received Heartbeat", sessionId);

        // Respond with Heartbeat if needed
        if (state.get() == SbeSessionState.ESTABLISHED) {
            // Optiq heartbeats are typically sent after a TestRequest
        }
    }

    /**
     * Sends a Heartbeat message.
     */
    public void sendHeartbeat() {
        HeartbeatMessage msg = tryClaim(HeartbeatMessage.class);
        if (msg == null) {
            return;
        }

        try {
            msg.writeHeader();
            msg.setLogicalAccessId(logicalAccessId);

            if (commit(msg)) {
                lastSentTime = System.currentTimeMillis();
                log.debug("[{}] Sent Heartbeat", sessionId);
            }
        } catch (Exception e) {
            abort(msg);
        }
    }

    /**
     * Sends a Logout message to gracefully close the session.
     */
    public void sendLogout(String reason) {
        LogoutMessage msg = tryClaim(LogoutMessage.class);
        if (msg == null) {
            return;
        }

        try {
            msg.writeHeader();
            msg.setLogicalAccessId(logicalAccessId)
               .setLogoutReasonCode(0)
               .setLogoutReasonText(reason != null ? reason : "Normal logout");

            if (commit(msg)) {
                log.info("[{}] Sent Logout", sessionId);
                forceState(SbeSessionState.TERMINATING);
            }
        } catch (Exception e) {
            abort(msg);
        }
    }

    // =====================================================
    // Application Message Handling
    // =====================================================

    private void handleExecutionReport(ExecutionReportMessage msg) {
        long clientOrderId = msg.getClientOrderId();
        long orderId = msg.getOrderId();
        byte orderStatus = msg.getOrderStatus();

        log.debug("[{}] Received ExecutionReport: clientOrderId={}, orderId={}, status={}",
                sessionId, clientOrderId, orderId, orderStatus);

        notifyMessage(msg);
    }

    private void handleReject(RejectMessage msg) {
        long clientOrderId = msg.getClientOrderId();
        int errorCode = msg.getErrorCode();

        log.warn("[{}] Received Reject: clientOrderId={}, errorCode={}",
                sessionId, clientOrderId, errorCode);

        notifyMessage(msg);
    }

    // =====================================================
    // Accessors
    // =====================================================

    /**
     * Gets the logical access ID.
     *
     * @return the logical access ID
     */
    public int getLogicalAccessId() {
        return logicalAccessId;
    }

    /**
     * Gets the heartbeat interval in seconds.
     *
     * @return the interval
     */
    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * Checks if a heartbeat should be sent.
     *
     * @return true if a heartbeat is due
     */
    public boolean isHeartbeatDue() {
        return (System.currentTimeMillis() - lastSentTime) > (heartbeatInterval * 1000L / 2);
    }

    @Override
    public void disconnect() {
        if (state.get() == SbeSessionState.ESTABLISHED) {
            sendLogout("Client disconnect");
        }
        super.disconnect();
    }

    @Override
    public void reset() {
        super.reset();
        lastSentTime = 0;
        lastReceivedTime = 0;
    }
}
