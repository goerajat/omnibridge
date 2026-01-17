package com.fixengine.engine.session;

import com.fixengine.engine.config.SessionConfig;
import com.fixengine.message.FixMessage;
import com.fixengine.message.FixReader;
import com.fixengine.message.FixTags;
import com.fixengine.message.FixWriter;
import com.fixengine.network.NetworkHandler;
import com.fixengine.network.TcpChannel;
import com.fixengine.persistence.FixLogEntry;
import com.fixengine.persistence.FixLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FIX session implementation.
 * Handles FIX protocol logic including logon, logout, heartbeat, and message sequencing.
 */
public class FixSession implements NetworkHandler {

    private static final Logger log = LoggerFactory.getLogger(FixSession.class);

    // FIX timestamp format: YYYYMMDD-HH:MM:SS.sss (UTC)
    private static final DateTimeFormatter FIX_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final SessionConfig config;
    private final FixLogStore logStore;
    private final FixReader reader = new FixReader();
    private final FixWriter writer = new FixWriter();

    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CREATED);
    private final AtomicInteger outgoingSeqNum = new AtomicInteger(1);
    private final AtomicInteger expectedIncomingSeqNum = new AtomicInteger(1);

    private final List<SessionStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<MessageListener> messageListeners = new CopyOnWriteArrayList<>();

    private TcpChannel channel;
    private long lastSentTime;
    private long lastReceivedTime;
    private int testRequestId = 0;
    private boolean testRequestPending = false;

    // Resend state
    private boolean resendInProgress = false;
    private int resendBeginSeqNo = 0;
    private int resendEndSeqNo = 0;

    public FixSession(SessionConfig config, FixLogStore logStore) {
        this.config = config;
        this.logStore = logStore;
    }

    // ==================== Session Management ====================

    /**
     * Set the TCP channel for this session.
     */
    public void setChannel(TcpChannel channel) {
        this.channel = channel;
    }

    /**
     * Get the TCP channel.
     */
    public TcpChannel getChannel() {
        return channel;
    }

    /**
     * Get the session configuration.
     */
    public SessionConfig getConfig() {
        return config;
    }

    /**
     * Get the current session state.
     */
    public SessionState getState() {
        return state.get();
    }

    /**
     * Add a session state listener.
     */
    public void addStateListener(SessionStateListener listener) {
        stateListeners.add(listener);
    }

    /**
     * Remove a session state listener.
     */
    public void removeStateListener(SessionStateListener listener) {
        stateListeners.remove(listener);
    }

    /**
     * Add a message listener.
     */
    public void addMessageListener(MessageListener listener) {
        messageListeners.add(listener);
    }

    /**
     * Remove a message listener.
     */
    public void removeMessageListener(MessageListener listener) {
        messageListeners.remove(listener);
    }

    // ==================== State Transitions ====================

    private void setState(SessionState newState) {
        SessionState oldState = state.getAndSet(newState);
        if (oldState != newState) {
            log.info("[{}] State change: {} -> {}", config.getSessionId(), oldState, newState);
            for (SessionStateListener listener : stateListeners) {
                try {
                    listener.onSessionStateChange(this, oldState, newState);

                    // Fire specific events
                    if (newState == SessionState.CONNECTED) {
                        listener.onSessionConnected(this);
                    } else if (newState == SessionState.LOGGED_ON) {
                        listener.onSessionLogon(this);
                    } else if (newState == SessionState.DISCONNECTED) {
                        listener.onSessionDisconnected(this, null);
                    }
                } catch (Exception e) {
                    log.error("[{}] Error notifying state listener", config.getSessionId(), e);
                }
            }
        }
    }

    // ==================== NetworkHandler Implementation ====================

    @Override
    public void onConnected(TcpChannel channel) {
        log.info("[{}] Connected to {}:{}", config.getSessionId(), config.getHost(), config.getPort());
        this.channel = channel;
        setState(SessionState.CONNECTED);
        lastReceivedTime = System.currentTimeMillis();

        // Initiator sends logon first
        if (config.isInitiator()) {
            sendLogon();
        }
    }

    @Override
    public int onDataReceived(TcpChannel channel, ByteBuffer data) {
        lastReceivedTime = System.currentTimeMillis();
        int bytesConsumed = data.remaining();

        // Feed data to reader
        reader.addData(data);

        // Process complete messages
        FixMessage message;
        while ((message = reader.readMessage()) != null) {
            try {
                processMessage(message);
            } catch (Exception e) {
                log.error("[{}] Error processing message", config.getSessionId(), e);
                sendReject(message.getSeqNum(), message.getMsgType(),
                          FixTags.SESSION_REJECT_REASON_OTHER, e.getMessage());
            }
        }

        return bytesConsumed;
    }

    @Override
    public void onDisconnected(TcpChannel channel, Throwable cause) {
        log.info("[{}] Disconnected: {}", config.getSessionId(),
                cause != null ? cause.getMessage() : "clean disconnect");

        SessionState oldState = state.get();
        setState(SessionState.DISCONNECTED);

        if (config.isResetOnDisconnect()) {
            resetSequenceNumbers();
        }

        // Notify listeners
        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onSessionDisconnected(this, cause);
            } catch (Exception e) {
                log.error("[{}] Error notifying disconnect listener", config.getSessionId(), e);
            }
        }

        this.channel = null;
    }

    @Override
    public void onConnectFailed(String remoteAddress, Throwable cause) {
        log.error("[{}] Connect failed to {}: {}", config.getSessionId(), remoteAddress, cause.getMessage());
        setState(SessionState.DISCONNECTED);

        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onSessionError(this, cause);
            } catch (Exception e) {
                log.error("[{}] Error notifying error listener", config.getSessionId(), e);
            }
        }
    }

    @Override
    public void onAcceptFailed(Throwable cause) {
        log.error("[{}] Accept failed: {}", config.getSessionId(), cause.getMessage());
    }

    // ==================== Message Processing ====================

    private void processMessage(FixMessage message) {
        String msgType = message.getMsgType();
        int seqNum = message.getSeqNum();

        log.debug("[{}] Received: {} SeqNum={}", config.getSessionId(), msgType, seqNum);
        log.info("[{}] IN  >>> {}", config.getSessionId(), formatRawMessage(message.getRawMessage()));

        // Log incoming message
        logMessage(message, FixLogEntry.Direction.INBOUND);

        // Validate session identifiers
        if (!validateSessionIds(message)) {
            return;
        }

        // Handle sequence number gap (except for logon and sequence reset)
        if (!msgType.equals(FixTags.MSG_TYPE_LOGON) &&
            !msgType.equals(FixTags.MSG_TYPE_SEQUENCE_RESET)) {
            if (seqNum > expectedIncomingSeqNum.get()) {
                // Gap detected, request resend
                sendResendRequest(expectedIncomingSeqNum.get(), 0);
                return;
            } else if (seqNum < expectedIncomingSeqNum.get()) {
                // Duplicate or old message
                if (!message.getBoolField(FixTags.POSS_DUP_FLAG)) {
                    log.warn("[{}] Sequence number too low: expected={}, received={}",
                            config.getSessionId(), expectedIncomingSeqNum.get(), seqNum);
                    disconnect("Sequence number too low");
                    return;
                }
                // It's a resend, process normally
            }
        }

        // Process by message type
        switch (msgType) {
            case FixTags.MSG_TYPE_LOGON:
                processLogon(message);
                break;
            case FixTags.MSG_TYPE_LOGOUT:
                processLogout(message);
                break;
            case FixTags.MSG_TYPE_HEARTBEAT:
                processHeartbeat(message);
                break;
            case FixTags.MSG_TYPE_TEST_REQUEST:
                processTestRequest(message);
                break;
            case FixTags.MSG_TYPE_RESEND_REQUEST:
                processResendRequest(message);
                break;
            case FixTags.MSG_TYPE_SEQUENCE_RESET:
                processSequenceReset(message);
                break;
            case FixTags.MSG_TYPE_REJECT:
                processReject(message);
                break;
            case FixTags.MSG_TYPE_BUSINESS_REJECT:
                processBusinessReject(message);
                break;
            default:
                // Application message
                processApplicationMessage(message);
                break;
        }

        // Update expected sequence number
        if (seqNum >= expectedIncomingSeqNum.get()) {
            expectedIncomingSeqNum.set(seqNum + 1);
        }
    }

    private boolean validateSessionIds(FixMessage message) {
        String senderCompId = message.getStringField(FixTags.SENDER_COMP_ID);
        String targetCompId = message.getStringField(FixTags.TARGET_COMP_ID);

        // Their sender should be our target
        if (!config.getTargetCompId().equals(senderCompId)) {
            log.error("[{}] Invalid SenderCompID: expected={}, received={}",
                    config.getSessionId(), config.getTargetCompId(), senderCompId);
            sendReject(message.getSeqNum(), message.getMsgType(),
                      FixTags.SESSION_REJECT_REASON_COMP_ID_PROBLEM, "Invalid SenderCompID");
            disconnect("Invalid SenderCompID");
            return false;
        }

        // Their target should be our sender
        if (!config.getSenderCompId().equals(targetCompId)) {
            log.error("[{}] Invalid TargetCompID: expected={}, received={}",
                    config.getSessionId(), config.getSenderCompId(), targetCompId);
            sendReject(message.getSeqNum(), message.getMsgType(),
                      FixTags.SESSION_REJECT_REASON_COMP_ID_PROBLEM, "Invalid TargetCompID");
            disconnect("Invalid TargetCompID");
            return false;
        }

        return true;
    }

    // ==================== Admin Message Handlers ====================

    private void processLogon(FixMessage message) {
        log.info("[{}] Logon received", config.getSessionId());

        int heartbeatInt = message.getIntField(FixTags.HEARTBT_INT);
        boolean resetSeqNum = message.getBoolField(FixTags.RESET_SEQ_NUM_FLAG);

        SessionState currentState = state.get();

        if (resetSeqNum || config.isResetOnLogon()) {
            log.info("[{}] Resetting sequence numbers", config.getSessionId());
            // For initiator: we already sent logon with seqNum=1, so outgoing should be 2
            // For acceptor: we haven't sent logon yet, so outgoing should be 1
            if (config.isInitiator()) {
                // Initiator already sent logon as seqNum=1
                outgoingSeqNum.set(2);
            } else {
                // Acceptor will send logon with seqNum=1
                outgoingSeqNum.set(1);
            }
            // We just received their logon as seqNum=1, next expected is 2
            // (will be set by processMessage at the end)
            expectedIncomingSeqNum.set(1);
        }

        if (config.isAcceptor() && currentState == SessionState.CONNECTED) {
            // Acceptor responds to logon
            sendLogon();
        }

        setState(SessionState.LOGGED_ON);
        testRequestPending = false;
    }

    private void processLogout(FixMessage message) {
        String text = message.getStringField(FixTags.TEXT);
        log.info("[{}] Logout received: {}", config.getSessionId(), text);

        SessionState currentState = state.get();

        if (currentState == SessionState.LOGGED_ON) {
            // Send logout response
            sendLogout("Logout acknowledged");
        }

        if (config.isResetOnLogout()) {
            resetSequenceNumbers();
        }

        // Notify listeners
        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onSessionLogout(this, text);
            } catch (Exception e) {
                log.error("[{}] Error notifying logout listener", config.getSessionId(), e);
            }
        }

        disconnect(null);
    }

    private void processHeartbeat(FixMessage message) {
        String testReqId = message.getStringField(FixTags.TEST_REQ_ID);
        log.debug("[{}] Heartbeat received, TestReqID={}", config.getSessionId(), testReqId);

        if (testRequestPending && testReqId != null) {
            testRequestPending = false;
        }
    }

    private void processTestRequest(FixMessage message) {
        String testReqId = message.getStringField(FixTags.TEST_REQ_ID);
        log.debug("[{}] TestRequest received, TestReqID={}", config.getSessionId(), testReqId);

        // Respond with heartbeat containing the same TestReqID
        sendHeartbeat(testReqId);
    }

    private void processResendRequest(FixMessage message) {
        int beginSeqNo = message.getIntField(FixTags.BEGIN_SEQ_NO);
        int endSeqNo = message.getIntField(FixTags.END_SEQ_NO);

        log.info("[{}] ResendRequest received: {} to {}", config.getSessionId(), beginSeqNo, endSeqNo);

        setState(SessionState.RESENDING);

        // If endSeqNo is 0, it means "to infinity"
        if (endSeqNo == 0) {
            endSeqNo = outgoingSeqNum.get() - 1;
        }

        // Resend messages from log store
        if (logStore != null) {
            final int finalEndSeqNo = endSeqNo;
            logStore.replay(config.getSessionId(), entry -> {
                if (entry.getDirection() == FixLogEntry.Direction.OUTBOUND &&
                    entry.getSeqNum() >= beginSeqNo && entry.getSeqNum() <= finalEndSeqNo) {

                    // Check if this is an admin message that should be gap-filled
                    if (shouldGapFill(entry.getMsgType())) {
                        // Will be gap-filled
                        return true;
                    }

                    // Resend with PossDupFlag set
                    resendMessage(entry);
                }
                return true;
            });
        }

        // Send gap fill for any admin messages
        sendGapFill(beginSeqNo, endSeqNo);

        setState(SessionState.LOGGED_ON);
    }

    private boolean shouldGapFill(String msgType) {
        // Admin messages should be gap-filled, not resent
        return msgType.equals(FixTags.MSG_TYPE_LOGON) ||
               msgType.equals(FixTags.MSG_TYPE_LOGOUT) ||
               msgType.equals(FixTags.MSG_TYPE_HEARTBEAT) ||
               msgType.equals(FixTags.MSG_TYPE_TEST_REQUEST) ||
               msgType.equals(FixTags.MSG_TYPE_RESEND_REQUEST) ||
               msgType.equals(FixTags.MSG_TYPE_SEQUENCE_RESET);
    }

    private void processSequenceReset(FixMessage message) {
        int newSeqNo = message.getIntField(FixTags.NEW_SEQ_NO);
        boolean gapFill = message.getBoolField(FixTags.GAP_FILL_FLAG);

        log.info("[{}] SequenceReset received: NewSeqNo={}, GapFill={}",
                config.getSessionId(), newSeqNo, gapFill);

        if (gapFill) {
            // Gap fill mode - only allow increasing sequence
            if (newSeqNo >= expectedIncomingSeqNum.get()) {
                expectedIncomingSeqNum.set(newSeqNo);
            } else {
                log.warn("[{}] Invalid gap fill: NewSeqNo {} < expected {}",
                        config.getSessionId(), newSeqNo, expectedIncomingSeqNum.get());
            }
        } else {
            // Reset mode - allow any value
            expectedIncomingSeqNum.set(newSeqNo);
        }
    }

    private void processReject(FixMessage message) {
        int refSeqNum = message.getIntField(FixTags.REF_SEQ_NUM);
        String refMsgType = message.getStringField(FixTags.REF_MSG_TYPE);
        int rejectReason = message.getIntField(FixTags.SESSION_REJECT_REASON);
        String text = message.getStringField(FixTags.TEXT);

        log.warn("[{}] Reject received: RefSeqNum={}, RefMsgType={}, Reason={}, Text={}",
                config.getSessionId(), refSeqNum, refMsgType, rejectReason, text);

        // Notify listeners
        for (MessageListener listener : messageListeners) {
            try {
                listener.onReject(this, refSeqNum, refMsgType, rejectReason, text);
            } catch (Exception e) {
                log.error("[{}] Error notifying reject listener", config.getSessionId(), e);
            }
        }
    }

    private void processBusinessReject(FixMessage message) {
        int refSeqNum = message.getIntField(FixTags.REF_SEQ_NUM);
        int businessRejectReason = message.getIntField(FixTags.BUSINESS_REJECT_REASON);
        String text = message.getStringField(FixTags.TEXT);

        log.warn("[{}] BusinessReject received: RefSeqNum={}, Reason={}, Text={}",
                config.getSessionId(), refSeqNum, businessRejectReason, text);

        // Notify listeners
        for (MessageListener listener : messageListeners) {
            try {
                listener.onBusinessReject(this, refSeqNum, businessRejectReason, text);
            } catch (Exception e) {
                log.error("[{}] Error notifying business reject listener", config.getSessionId(), e);
            }
        }
    }

    private void processApplicationMessage(FixMessage message) {
        log.debug("[{}] Application message received: {}", config.getSessionId(), message.getMsgType());

        // Notify listeners
        for (MessageListener listener : messageListeners) {
            try {
                listener.onMessage(this, message);
            } catch (Exception e) {
                log.error("[{}] Error notifying message listener", config.getSessionId(), e);
            }
        }
    }

    // ==================== Message Sending ====================

    /**
     * Send an application message.
     * Returns the sequence number assigned to the message.
     */
    public int send(FixMessage message) {
        if (!state.get().canSendAppMessage()) {
            throw new IllegalStateException("Cannot send application message in state: " + state.get());
        }

        return sendInternal(message);
    }

    private int sendInternal(FixMessage message) {
        int seqNum = outgoingSeqNum.getAndIncrement();

        writer.clear();
        writer.beginMessage(config.getBeginString(), message.getMsgType());
        writer.addField(FixTags.SENDER_COMP_ID, config.getSenderCompId());
        writer.addField(FixTags.TARGET_COMP_ID, config.getTargetCompId());
        writer.addField(FixTags.MSG_SEQ_NUM, seqNum);
        writer.addField(FixTags.SENDING_TIME, FIX_TIMESTAMP_FORMAT.format(Instant.now()));

        // Add all fields from the message
        for (int tag : message.getTags()) {
            if (tag != FixTags.BEGIN_STRING && tag != FixTags.BODY_LENGTH &&
                tag != FixTags.MSG_TYPE && tag != FixTags.SENDER_COMP_ID &&
                tag != FixTags.TARGET_COMP_ID && tag != FixTags.MSG_SEQ_NUM &&
                tag != FixTags.SENDING_TIME && tag != FixTags.CHECKSUM) {
                writer.addField(tag, message.getStringField(tag));
            }
        }

        byte[] rawMessage = writer.finish();

        // Log outgoing message
        FixLogEntry entry = new FixLogEntry(
            Instant.now().toEpochMilli(),
            seqNum,
            FixLogEntry.Direction.OUTBOUND,
            config.getSessionId(),
            message.getMsgType(),
            0,
            rawMessage,
            null
        );

        if (logStore != null) {
            logStore.write(entry);
        }

        // Send on the wire - check both channel and state
        TcpChannel ch = channel;
        SessionState currentState = state.get();
        if (ch != null && currentState != SessionState.DISCONNECTED) {
            try {
                ch.write(ByteBuffer.wrap(rawMessage));
                lastSentTime = System.currentTimeMillis();
                log.debug("[{}] Sent: {} SeqNum={}", config.getSessionId(), message.getMsgType(), seqNum);
                log.info("[{}] OUT <<< {}", config.getSessionId(), formatRawMessage(rawMessage));
            } catch (java.io.IOException e) {
                log.error("[{}] Error writing to channel", config.getSessionId(), e);
            }
        } else {
            log.debug("[{}] Skipped sending {} SeqNum={} - channel closed or disconnected",
                    config.getSessionId(), message.getMsgType(), seqNum);
        }

        return seqNum;
    }

    private void sendLogon() {
        log.info("[{}] Sending Logon", config.getSessionId());

        setState(SessionState.LOGON_SENT);

        FixMessage logon = new FixMessage();
        logon.setMsgType(FixTags.MSG_TYPE_LOGON);
        logon.setField(FixTags.ENCRYPT_METHOD, 0); // None
        logon.setField(FixTags.HEARTBT_INT, config.getHeartbeatInterval());

        if (config.isResetOnLogon()) {
            logon.setField(FixTags.RESET_SEQ_NUM_FLAG, "Y");
        }

        sendInternal(logon);
    }

    private void sendLogout(String text) {
        log.info("[{}] Sending Logout: {}", config.getSessionId(), text);

        setState(SessionState.LOGOUT_SENT);

        FixMessage logout = new FixMessage();
        logout.setMsgType(FixTags.MSG_TYPE_LOGOUT);
        if (text != null) {
            logout.setField(FixTags.TEXT, text);
        }

        sendInternal(logout);
    }

    private void sendHeartbeat(String testReqId) {
        FixMessage heartbeat = new FixMessage();
        heartbeat.setMsgType(FixTags.MSG_TYPE_HEARTBEAT);
        if (testReqId != null) {
            heartbeat.setField(FixTags.TEST_REQ_ID, testReqId);
        }

        sendInternal(heartbeat);
    }

    /**
     * Send a TestRequest message and return the TestReqID.
     * @return the TestReqID string used in the request
     */
    public String sendTestRequest() {
        String testReqId = String.valueOf(++testRequestId);
        log.debug("[{}] Sending TestRequest: {}", config.getSessionId(), testReqId);

        FixMessage testRequest = new FixMessage();
        testRequest.setMsgType(FixTags.MSG_TYPE_TEST_REQUEST);
        testRequest.setField(FixTags.TEST_REQ_ID, testReqId);

        sendInternal(testRequest);
        testRequestPending = true;
        return testReqId;
    }

    private void sendResendRequest(int beginSeqNo, int endSeqNo) {
        log.info("[{}] Sending ResendRequest: {} to {}", config.getSessionId(), beginSeqNo, endSeqNo);

        FixMessage resendRequest = new FixMessage();
        resendRequest.setMsgType(FixTags.MSG_TYPE_RESEND_REQUEST);
        resendRequest.setField(FixTags.BEGIN_SEQ_NO, beginSeqNo);
        resendRequest.setField(FixTags.END_SEQ_NO, endSeqNo);

        sendInternal(resendRequest);
    }

    private void sendGapFill(int beginSeqNo, int newSeqNo) {
        log.debug("[{}] Sending GapFill: {} to {}", config.getSessionId(), beginSeqNo, newSeqNo);

        FixMessage sequenceReset = new FixMessage();
        sequenceReset.setMsgType(FixTags.MSG_TYPE_SEQUENCE_RESET);
        sequenceReset.setField(FixTags.GAP_FILL_FLAG, "Y");
        sequenceReset.setField(FixTags.NEW_SEQ_NO, newSeqNo);

        // Gap fill uses the sequence number of the first message in the gap
        int seqNum = beginSeqNo;

        writer.clear();
        writer.beginMessage(config.getBeginString(), FixTags.MSG_TYPE_SEQUENCE_RESET);
        writer.addField(FixTags.SENDER_COMP_ID, config.getSenderCompId());
        writer.addField(FixTags.TARGET_COMP_ID, config.getTargetCompId());
        writer.addField(FixTags.MSG_SEQ_NUM, seqNum);
        writer.addField(FixTags.SENDING_TIME, FIX_TIMESTAMP_FORMAT.format(Instant.now()));
        writer.addField(FixTags.POSS_DUP_FLAG, "Y");
        writer.addField(FixTags.GAP_FILL_FLAG, "Y");
        writer.addField(FixTags.NEW_SEQ_NO, newSeqNo);

        byte[] rawMessage = writer.finish();

        TcpChannel ch = channel;
        if (ch != null && state.get() != SessionState.DISCONNECTED) {
            try {
                ch.write(ByteBuffer.wrap(rawMessage));
                lastSentTime = System.currentTimeMillis();
                log.info("[{}] OUT <<< {}", config.getSessionId(), formatRawMessage(rawMessage));
            } catch (java.io.IOException e) {
                log.error("[{}] Error writing gap fill to channel", config.getSessionId(), e);
            }
        }
    }

    private void sendReject(int refSeqNum, String refMsgType, int rejectReason, String text) {
        log.warn("[{}] Sending Reject: RefSeqNum={}, Reason={}, Text={}",
                config.getSessionId(), refSeqNum, rejectReason, text);

        FixMessage reject = new FixMessage();
        reject.setMsgType(FixTags.MSG_TYPE_REJECT);
        reject.setField(FixTags.REF_SEQ_NUM, refSeqNum);
        if (refMsgType != null) {
            reject.setField(FixTags.REF_MSG_TYPE, refMsgType);
        }
        reject.setField(FixTags.SESSION_REJECT_REASON, rejectReason);
        if (text != null) {
            reject.setField(FixTags.TEXT, text);
        }

        sendInternal(reject);
    }

    private void resendMessage(FixLogEntry entry) {
        // Set PossDupFlag and OrigSendingTime for resends
        // The original message is in entry.getRawMessage(), we need to modify it
        log.debug("[{}] Resending message: SeqNum={}", config.getSessionId(), entry.getSeqNum());

        TcpChannel ch = channel;
        if (ch != null && state.get() != SessionState.DISCONNECTED) {
            try {
                ch.write(ByteBuffer.wrap(entry.getRawMessage()));
                lastSentTime = System.currentTimeMillis();
                log.info("[{}] OUT <<< (resend) {}", config.getSessionId(), formatRawMessage(entry.getRawMessage()));
            } catch (java.io.IOException e) {
                log.error("[{}] Error resending message", config.getSessionId(), e);
            }
        }
    }

    // ==================== Session Control ====================

    /**
     * Initiate logon (for initiator sessions).
     */
    public void logon() {
        if (config.isInitiator() && state.get() == SessionState.CONNECTED) {
            sendLogon();
        }
    }

    /**
     * Initiate logout.
     */
    public void logout(String reason) {
        if (state.get().isLoggedOn()) {
            sendLogout(reason);
        }
    }

    /**
     * Disconnect the session.
     */
    public void disconnect(String reason) {
        log.info("[{}] Disconnecting: {}", config.getSessionId(), reason);

        if (channel != null) {
            channel.close();
        }
    }

    /**
     * Reset sequence numbers to 1.
     */
    public void resetSequenceNumbers() {
        log.info("[{}] Resetting sequence numbers", config.getSessionId());
        outgoingSeqNum.set(1);
        expectedIncomingSeqNum.set(1);
    }

    /**
     * Get the next outgoing sequence number.
     */
    public int getOutgoingSeqNum() {
        return outgoingSeqNum.get();
    }

    /**
     * Get the expected incoming sequence number.
     */
    public int getExpectedIncomingSeqNum() {
        return expectedIncomingSeqNum.get();
    }

    /**
     * Set the expected incoming sequence number.
     * @param seqNum the new sequence number (must be >= 1)
     * @throws IllegalArgumentException if seqNum is less than 1
     */
    public void setExpectedIncomingSeqNum(int seqNum) {
        if (seqNum < 1) {
            throw new IllegalArgumentException("Sequence number must be >= 1, got: " + seqNum);
        }
        log.info("[{}] Setting expected incoming sequence number: {} -> {}",
                config.getSessionId(), expectedIncomingSeqNum.get(), seqNum);
        expectedIncomingSeqNum.set(seqNum);
    }

    /**
     * Set the outgoing sequence number.
     * @param seqNum the new sequence number (must be >= 1)
     * @throws IllegalArgumentException if seqNum is less than 1
     */
    public void setOutgoingSeqNum(int seqNum) {
        if (seqNum < 1) {
            throw new IllegalArgumentException("Sequence number must be >= 1, got: " + seqNum);
        }
        log.info("[{}] Setting outgoing sequence number: {} -> {}",
                config.getSessionId(), outgoingSeqNum.get(), seqNum);
        outgoingSeqNum.set(seqNum);
    }

    /**
     * Check heartbeat and send test request if needed.
     * Should be called periodically by the engine.
     */
    public void checkHeartbeat() {
        // Check both state and channel availability
        if (!state.get().isLoggedOn() || channel == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long heartbeatMs = config.getHeartbeatInterval() * 1000L;

        // Send heartbeat if we haven't sent anything in a while
        if (now - lastSentTime > heartbeatMs) {
            sendHeartbeat(null);
        }

        // Check if we need to send a test request
        if (now - lastReceivedTime > heartbeatMs + (heartbeatMs / 2)) {
            if (!testRequestPending) {
                sendTestRequest();
            } else {
                // Test request timeout - disconnect
                log.warn("[{}] TestRequest timeout, disconnecting", config.getSessionId());
                disconnect("TestRequest timeout");
            }
        }
    }

    private void logMessage(FixMessage message, FixLogEntry.Direction direction) {
        if (logStore != null && config.isLogMessages()) {
            FixLogEntry entry = new FixLogEntry(
                Instant.now().toEpochMilli(),
                message.getSeqNum(),
                direction,
                config.getSessionId(),
                message.getMsgType(),
                0,
                message.getRawMessage(),
                null
            );
            logStore.write(entry);
        }
    }

    /**
     * Format raw FIX message bytes for logging.
     * Replaces SOH (0x01) delimiter with '|' for readability.
     */
    private static String formatRawMessage(byte[] rawMessage) {
        if (rawMessage == null || rawMessage.length == 0) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder(rawMessage.length);
        for (byte b : rawMessage) {
            if (b == 0x01) {
                sb.append('|');
            } else {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "FixSession{" +
                "id=" + config.getSessionId() +
                ", state=" + state.get() +
                ", outSeq=" + outgoingSeqNum.get() +
                ", inSeq=" + expectedIncomingSeqNum.get() +
                '}';
    }
}
