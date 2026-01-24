package com.omnibridge.fix.engine.session;

import com.omnibridge.config.ClockProvider;
import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.message.FixReader;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.message.IncomingMessagePool;
import com.omnibridge.fix.message.IncomingMessagePoolConfig;
import com.omnibridge.fix.message.MessagePoolConfig;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.network.NetworkHandler;
import com.omnibridge.network.TcpChannel;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final LogStore logStore;
    private final FixReader reader = new FixReader();

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

    // Flag to track sequence number reset during logon processing
    private boolean seqNumResetDuringLogon = false;

    // Resend state
    private boolean resendInProgress = false;
    private int resendBeginSeqNo = 0;
    private int resendEndSeqNo = 0;

    // Message pool for incoming message parsing
    private final IncomingMessagePool incomingMessagePool;

    // Clock provider for time sources
    private final ClockProvider clockProvider;

    // Thread-local ring buffer message wrappers for concurrent access
    private final ThreadLocal<RingBufferOutgoingMessage> ringBufferMessageThreadLocal;

    // Configuration for ring buffer messages
    private final MessagePoolConfig poolConfig;

    public FixSession(SessionConfig config, LogStore logStore) {
        this.config = config;
        this.logStore = logStore;
        this.clockProvider = config.getClockProvider();

        // Initialize message pool configuration (used by ring buffer message)
        this.poolConfig = MessagePoolConfig.builder()
                .maxMessageLength(config.getMaxMessageLength())
                .maxTagNumber(config.getMaxTagNumber())
                .beginString(config.getBeginString())
                .senderCompId(config.getSenderCompId())
                .targetCompId(config.getTargetCompId())
                .clockProvider(clockProvider)
                .build();

        // Initialize thread-local ring buffer message wrappers for concurrent access
        this.ringBufferMessageThreadLocal = ThreadLocal.withInitial(() -> new RingBufferOutgoingMessage(poolConfig));
        log.info("[{}] Ring buffer capacity: {}", config.getSessionId(), config.getRingBufferCapacity());

        // Initialize incoming message pool (for parsing incoming messages)
        IncomingMessagePoolConfig incomingConfig = IncomingMessagePoolConfig.builder()
                .poolSize(64)
                .maxTagNumber(config.getMaxTagNumber())
                .build();
        this.incomingMessagePool = new IncomingMessagePool(incomingConfig);
        log.info("[{}] Incoming message pool initialized: size={}", config.getSessionId(), 64);
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
    public int getNumBytesToRead(TcpChannel channel) {
        return reader.getBytesNeeded();
    }

    @Override
    public void onConnected(TcpChannel channel) {
        log.info("[{}] Connected to {}:{}", config.getSessionId(), config.getHost(), config.getPort());
        this.channel = channel;
        setState(SessionState.CONNECTED);
        lastReceivedTime = clockProvider.currentTimeMillis();

        // Reset reader state for new connection
        // This is critical for reconnection scenarios where the reader may have
        // stale state (accumulated buffer data, expected message length) from
        // the previous connection
        reader.reset();

        // Initiator sends logon first
        if (config.isInitiator()) {
            sendLogon();
        }
    }

    @Override
    public int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length) {
        lastReceivedTime = clockProvider.currentTimeMillis();

        // Feed data to reader
        reader.addData(buffer, offset, length);

        // Process complete messages
        processIncomingMessages();

        return length;
    }

    /**
     * Process incoming messages using pooled IncomingFixMessage.
     * Messages are released back to the pool after callbacks complete.
     */
    private void processIncomingMessages() {
        while (true) {
            IncomingFixMessage message;
            try {
                message = incomingMessagePool.tryAcquire();
                if (message == null) {
                    // No messages available in pool, fall back to blocking acquire
                    message = incomingMessagePool.acquire();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Try to read a message into the pooled IncomingFixMessage
            boolean hasMessage = reader.readIncomingMessage(message);
            if (!hasMessage) {
                message.release();
                return;
            }

            try {
                processIncomingMessage(message);
            } catch (Exception e) {
                log.error("[{}] Error processing message", config.getSessionId(), e);
                sendReject(message.getSeqNum(), message.getMsgType(),
                          FixTags.SESSION_REJECT_REASON_OTHER, e.getMessage());
            } finally {
                // Release message back to pool after callback completes
                message.release();
            }
        }
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

    /**
     * Process an incoming FIX message (pooled mode).
     * The message will be released back to the pool after this method returns.
     */
    private void processIncomingMessage(IncomingFixMessage message) {
        String msgType = message.getMsgType();
        int seqNum = message.getSeqNum();

        log.debug("[{}] Received: {} SeqNum={}", config.getSessionId(), msgType, seqNum);
        log.debug("[{}] IN  >>> {}", config.getSessionId(), formatRawMessage(message.getRawMessage()));

        // Log incoming message
        logIncomingMessage(message, LogEntry.Direction.INBOUND);

        // Validate session identifiers
        if (!validateIncomingSessionIds(message)) {
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
                if (!message.getBool(FixTags.POSS_DUP_FLAG)) {
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
                processIncomingLogon(message);
                break;
            case FixTags.MSG_TYPE_LOGOUT:
                processIncomingLogout(message);
                break;
            case FixTags.MSG_TYPE_HEARTBEAT:
                processIncomingHeartbeat(message);
                break;
            case FixTags.MSG_TYPE_TEST_REQUEST:
                processIncomingTestRequest(message);
                break;
            case FixTags.MSG_TYPE_RESEND_REQUEST:
                processIncomingResendRequest(message);
                break;
            case FixTags.MSG_TYPE_SEQUENCE_RESET:
                processIncomingSequenceReset(message);
                break;
            case FixTags.MSG_TYPE_REJECT:
                processIncomingReject(message);
                break;
            case FixTags.MSG_TYPE_BUSINESS_REJECT:
                processIncomingBusinessReject(message);
                break;
            default:
                // Application message
                processIncomingApplicationMessage(message);
                break;
        }

        // Notify message listeners for ALL messages (admin and application)
        // This allows listeners to observe admin messages like Heartbeat, TestRequest, etc.
        for (MessageListener listener : messageListeners) {
            try {
                listener.onMessage(this, message);
            } catch (Exception e) {
                log.error("[{}] Error notifying message listener", config.getSessionId(), e);
            }
        }

        // Update expected sequence number
        if (seqNumResetDuringLogon) {
            // After a sequence reset during logon, expect seqNum=2 for the next message
            // regardless of what seqNum the logon message had
            expectedIncomingSeqNum.set(2);
            seqNumResetDuringLogon = false;
        } else if (seqNum >= expectedIncomingSeqNum.get()) {
            expectedIncomingSeqNum.set(seqNum + 1);
        }
    }

    private boolean validateIncomingSessionIds(IncomingFixMessage message) {
        CharSequence senderCompId = message.getSenderCompIdCharSeq();
        CharSequence targetCompId = message.getTargetCompIdCharSeq();

        // Their sender should be our target
        if (!config.getTargetCompId().contentEquals(senderCompId)) {
            log.error("[{}] Invalid SenderCompID: expected={}, received={}",
                    config.getSessionId(), config.getTargetCompId(), senderCompId);
            sendReject(message.getSeqNum(), message.getMsgType(),
                      FixTags.SESSION_REJECT_REASON_COMP_ID_PROBLEM, "Invalid SenderCompID");
            disconnect("Invalid SenderCompID");
            return false;
        }

        // Their target should be our sender
        if (!config.getSenderCompId().contentEquals(targetCompId)) {
            log.error("[{}] Invalid TargetCompID: expected={}, received={}",
                    config.getSessionId(), config.getSenderCompId(), targetCompId);
            sendReject(message.getSeqNum(), message.getMsgType(),
                      FixTags.SESSION_REJECT_REASON_COMP_ID_PROBLEM, "Invalid TargetCompID");
            disconnect("Invalid TargetCompID");
            return false;
        }

        return true;
    }

    private void logIncomingMessage(IncomingFixMessage message, LogEntry.Direction direction) {
        if (logStore != null && config.isLogMessages()) {
            LogEntry entry = LogEntry.builder()
                .timestamp(Instant.now().toEpochMilli())
                .sequenceNumber(message.getSeqNum())
                .direction(direction)
                .streamName(config.getSessionId())
                .rawMessage(message.getRawMessage())
                .build();
            logStore.write(entry);
        }
    }

    // ==================== Incoming Admin Message Handlers (for pooled messages) ====================

    private void processIncomingLogon(IncomingFixMessage message) {
        log.info("[{}] Logon received", config.getSessionId());

        int heartbeatInt = message.getInt(FixTags.HEARTBT_INT);
        boolean resetSeqNum = message.getBool(FixTags.RESET_SEQ_NUM_FLAG);

        SessionState currentState = state.get();

        if (resetSeqNum || config.isResetOnLogon()) {
            log.info("[{}] Resetting sequence numbers", config.getSessionId());
            if (config.isInitiator()) {
                outgoingSeqNum.set(2);
            } else {
                outgoingSeqNum.set(1);
            }
            expectedIncomingSeqNum.set(1);
            // Mark that we just reset - the seqNum increment logic will handle this specially
            seqNumResetDuringLogon = true;
        }

        if (config.isAcceptor() && currentState == SessionState.CONNECTED) {
            sendLogon();
        }

        setState(SessionState.LOGGED_ON);
        testRequestPending = false;
    }

    private void processIncomingLogout(IncomingFixMessage message) {
        CharSequence textCs = message.getCharSequence(FixTags.TEXT);
        String text = textCs != null ? textCs.toString() : null;
        log.info("[{}] Logout received: {}", config.getSessionId(), text);

        SessionState currentState = state.get();

        if (currentState == SessionState.LOGGED_ON) {
            sendLogout("Logout acknowledged");
        }

        if (config.isResetOnLogout()) {
            resetSequenceNumbers();
        }

        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onSessionLogout(this, text);
            } catch (Exception e) {
                log.error("[{}] Error notifying logout listener", config.getSessionId(), e);
            }
        }

        disconnect(null);
    }

    private void processIncomingHeartbeat(IncomingFixMessage message) {
        CharSequence testReqIdCs = message.getCharSequence(FixTags.TEST_REQ_ID);
        String testReqId = testReqIdCs != null ? testReqIdCs.toString() : null;
        log.debug("[{}] Heartbeat received, TestReqID={}", config.getSessionId(), testReqId);

        if (testRequestPending && testReqId != null) {
            testRequestPending = false;
        }
    }

    private void processIncomingTestRequest(IncomingFixMessage message) {
        CharSequence testReqIdCs = message.getCharSequence(FixTags.TEST_REQ_ID);
        String testReqId = testReqIdCs != null ? testReqIdCs.toString() : null;
        log.debug("[{}] TestRequest received, TestReqID={}", config.getSessionId(), testReqId);
        sendHeartbeat(testReqId);
    }

    private void processIncomingResendRequest(IncomingFixMessage message) {
        int beginSeqNo = message.getInt(FixTags.BEGIN_SEQ_NO);
        int endSeqNo = message.getInt(FixTags.END_SEQ_NO);

        log.info("[{}] ResendRequest received: {} to {}", config.getSessionId(), beginSeqNo, endSeqNo);

        setState(SessionState.RESENDING);

        if (endSeqNo == 0) {
            endSeqNo = outgoingSeqNum.get() - 1;
        }

        if (logStore != null) {
            final int finalEndSeqNo = endSeqNo;
            logStore.replay(config.getSessionId(), entry -> {
                if (entry.getDirection() == LogEntry.Direction.OUTBOUND &&
                    entry.getSequenceNumber() >= beginSeqNo && entry.getSequenceNumber() <= finalEndSeqNo) {

                    // Extract msgType from raw message for gap fill check
                    String msgType = extractMsgType(entry.getRawMessage());
                    if (shouldGapFill(msgType)) {
                        return true;
                    }
                    resendMessage(entry);
                }
                return true;
            });
        }

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

    private void processIncomingSequenceReset(IncomingFixMessage message) {
        int newSeqNo = message.getInt(FixTags.NEW_SEQ_NO);
        boolean gapFill = message.getBool(FixTags.GAP_FILL_FLAG);

        log.info("[{}] SequenceReset received: NewSeqNo={}, GapFill={}",
                config.getSessionId(), newSeqNo, gapFill);

        if (gapFill) {
            if (newSeqNo >= expectedIncomingSeqNum.get()) {
                expectedIncomingSeqNum.set(newSeqNo);
            } else {
                log.warn("[{}] Invalid gap fill: NewSeqNo {} < expected {}",
                        config.getSessionId(), newSeqNo, expectedIncomingSeqNum.get());
            }
        } else {
            expectedIncomingSeqNum.set(newSeqNo);
        }
    }

    private void processIncomingReject(IncomingFixMessage message) {
        int refSeqNum = message.getInt(FixTags.REF_SEQ_NUM);
        CharSequence refMsgTypeCs = message.getCharSequence(FixTags.REF_MSG_TYPE);
        String refMsgType = refMsgTypeCs != null ? refMsgTypeCs.toString() : null;
        int rejectReason = message.getInt(FixTags.SESSION_REJECT_REASON);
        CharSequence textCs = message.getCharSequence(FixTags.TEXT);
        String text = textCs != null ? textCs.toString() : null;

        log.warn("[{}] Reject received: RefSeqNum={}, RefMsgType={}, Reason={}, Text={}",
                config.getSessionId(), refSeqNum, refMsgType, rejectReason, text);

        for (MessageListener listener : messageListeners) {
            try {
                listener.onReject(this, refSeqNum, refMsgType, rejectReason, text);
            } catch (Exception e) {
                log.error("[{}] Error notifying reject listener", config.getSessionId(), e);
            }
        }
    }

    private void processIncomingBusinessReject(IncomingFixMessage message) {
        int refSeqNum = message.getInt(FixTags.REF_SEQ_NUM);
        int businessRejectReason = message.getInt(FixTags.BUSINESS_REJECT_REASON);
        CharSequence textCs = message.getCharSequence(FixTags.TEXT);
        String text = textCs != null ? textCs.toString() : null;

        log.warn("[{}] BusinessReject received: RefSeqNum={}, Reason={}, Text={}",
                config.getSessionId(), refSeqNum, businessRejectReason, text);

        for (MessageListener listener : messageListeners) {
            try {
                listener.onBusinessReject(this, refSeqNum, businessRejectReason, text);
            } catch (Exception e) {
                log.error("[{}] Error notifying business reject listener", config.getSessionId(), e);
            }
        }
    }

    private void processIncomingApplicationMessage(IncomingFixMessage message) {
        log.debug("[{}] Application message received: {}", config.getSessionId(), message.getMsgType());
        // Message listener notification is done centrally in processIncomingMessage()
    }

    // ==================== Ring Buffer API (Message Sending) ====================

    /**
     * Try to claim space in the ring buffer for a new message.
     *
     * <p>This method is thread-safe and can be called from multiple threads concurrently.
     * The sequence number is assigned atomically when the claim succeeds.</p>
     *
     * <p>Usage:</p>
     * <pre>{@code
     * RingBufferOutgoingMessage msg = session.tryClaimMessage("D");
     * if (msg != null) {
     *     msg.setField(11, "ORDER-001");
     *     msg.setField(55, "AAPL");
     *     session.commitMessage(msg);
     * } else {
     *     // Ring buffer full - handle backpressure
     * }
     * }</pre>
     *
     * @param msgType the message type (e.g., "D" for NewOrderSingle)
     * @return the ring buffer message ready for field encoding, or null if buffer is full
     * @throws IllegalStateException if not in a state that allows sending
     */
    public RingBufferOutgoingMessage tryClaimMessage(String msgType) {
        if (!state.get().canSendAppMessage()) {
            throw new IllegalStateException("Cannot send application message in state: " + state.get());
        }

        return tryClaimMessageInternal(msgType);
    }

    /**
     * Internal method to try claiming a message (used by both app and admin messages).
     */
    private RingBufferOutgoingMessage tryClaimMessageInternal(String msgType) {
        TcpChannel ch = channel;
        if (ch == null) {
            return null;
        }

        // Calculate claim size: length prefix + max message length
        int claimSize = RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE + config.getMaxMessageLength();

        // Try to claim space in the ring buffer
        int claimIndex = ch.tryClaim(claimSize);
        if (claimIndex < 0) {
            // Ring buffer is full
            return null;
        }

        // Assign sequence number atomically
        int seqNum = outgoingSeqNum.getAndIncrement();

        // Get the buffer and wrap the thread-local message
        MutableDirectBuffer buffer = ch.buffer();
        RingBufferOutgoingMessage message = ringBufferMessageThreadLocal.get();
        message.wrap(buffer, claimIndex, claimSize, seqNum, claimIndex,
                config.getBeginString(), config.getSenderCompId(), config.getTargetCompId());
        message.setMsgType(msgType);

        return message;
    }

    /**
     * Commit a ring buffer message, making it available for sending.
     *
     * <p>This method prepares the message (fills in SendingTime, BodyLength, CheckSum)
     * and commits it to the ring buffer. The message will be sent to the socket
     * when the network event loop flushes the channel.</p>
     *
     * @param msg the ring buffer message to commit
     */
    public void commitMessage(RingBufferOutgoingMessage msg) {
        // Prepare the message
        long sendingTime = clockProvider.currentTimeMillis();
        int messageLength = msg.prepareForSend(sendingTime);

        // Log outgoing message
        if (logStore != null && config.isLogMessages()) {
            byte[] logCopy = msg.toByteArray();
            LogEntry entry = LogEntry.builder()
                .timestamp(Instant.now().toEpochMilli())
                .sequenceNumber(msg.getSeqNum())
                .direction(LogEntry.Direction.OUTBOUND)
                .streamName(config.getSessionId())
                .rawMessage(logCopy)
                .build();
            logStore.write(entry);
        }

        // Commit to ring buffer
        TcpChannel ch = channel;
        if (ch != null) {
            ch.commit(msg.getClaimIndex());
            lastSentTime = clockProvider.currentTimeMillis();
            log.debug("[{}] Committed to ring buffer: {} SeqNum={}",
                    config.getSessionId(), msg.getMsgType(), msg.getSeqNum());
            if (config.isLogMessages()) {
                log.info("[{}] OUT <<< {}", config.getSessionId(), msg.toString());
            }
        }
    }

    /**
     * Abort a ring buffer message, discarding it.
     *
     * <p>Use this method to rollback a claim if encoding fails. The sequence
     * number is also rolled back.</p>
     *
     * @param msg the ring buffer message to abort
     */
    public void abortMessage(RingBufferOutgoingMessage msg) {
        // Rollback sequence number
        outgoingSeqNum.decrementAndGet();

        // Abort the claim
        TcpChannel ch = channel;
        if (ch != null) {
            ch.abort(msg.getClaimIndex());
            log.debug("[{}] Aborted ring buffer message: {}", config.getSessionId(), msg.getMsgType());
        }
    }

    /**
     * Build and send an admin message using the ring buffer.
     * This is the internal method for all admin message sending.
     */
    private int sendAdminMessage(String msgType, java.util.function.Consumer<RingBufferOutgoingMessage> fieldAdder) {
        RingBufferOutgoingMessage msg = tryClaimMessageInternal(msgType);
        if (msg == null) {
            log.warn("[{}] Failed to claim ring buffer for admin message: {}", config.getSessionId(), msgType);
            return -1;
        }

        int seqNum = msg.getSeqNum();

        // Add message-specific fields
        fieldAdder.accept(msg);

        // Commit to ring buffer
        commitMessage(msg);

        return seqNum;
    }

    /**
     * Claim a message with a specific sequence number (for gap fills).
     * Does not auto-increment the outgoing sequence number.
     */
    private RingBufferOutgoingMessage tryClaimMessageWithSeqNum(String msgType, int seqNum) {
        TcpChannel ch = channel;
        if (ch == null) {
            return null;
        }

        // Calculate claim size: length prefix + max message length
        int claimSize = RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE + config.getMaxMessageLength();

        // Try to claim space in the ring buffer
        int claimIndex = ch.tryClaim(claimSize);
        if (claimIndex < 0) {
            // Ring buffer is full
            return null;
        }

        // Get the buffer and wrap the thread-local message
        MutableDirectBuffer buffer = ch.buffer();
        RingBufferOutgoingMessage message = ringBufferMessageThreadLocal.get();
        message.wrap(buffer, claimIndex, claimSize, seqNum, claimIndex,
                config.getBeginString(), config.getSenderCompId(), config.getTargetCompId());
        message.setMsgType(msgType);

        return message;
    }

    private void sendLogon() {
        log.info("[{}] Sending Logon", config.getSessionId());

        setState(SessionState.LOGON_SENT);

        sendAdminMessage(FixTags.MSG_TYPE_LOGON, msg -> {
            msg.setField(FixTags.ENCRYPT_METHOD, 0); // None
            msg.setField(FixTags.HEARTBT_INT, config.getHeartbeatInterval());
            if (config.isResetOnLogon()) {
                msg.setField(FixTags.RESET_SEQ_NUM_FLAG, "Y");
            }
        });
    }

    private void sendLogout(String text) {
        log.info("[{}] Sending Logout: {}", config.getSessionId(), text);

        setState(SessionState.LOGOUT_SENT);

        sendAdminMessage(FixTags.MSG_TYPE_LOGOUT, msg -> {
            if (text != null) {
                msg.setField(FixTags.TEXT, text);
            }
        });
    }

    private void sendHeartbeat(String testReqId) {
        sendAdminMessage(FixTags.MSG_TYPE_HEARTBEAT, msg -> {
            if (testReqId != null) {
                msg.setField(FixTags.TEST_REQ_ID, testReqId);
            }
        });
    }

    /**
     * Send a TestRequest message and return the TestReqID.
     * @return the TestReqID string used in the request
     */
    public String sendTestRequest() {
        String testReqId = String.valueOf(++testRequestId);
        log.debug("[{}] Sending TestRequest: {}", config.getSessionId(), testReqId);

        sendAdminMessage(FixTags.MSG_TYPE_TEST_REQUEST, msg -> {
            msg.setField(FixTags.TEST_REQ_ID, testReqId);
        });
        testRequestPending = true;
        return testReqId;
    }

    private void sendResendRequest(int beginSeqNo, int endSeqNo) {
        log.info("[{}] Sending ResendRequest: {} to {}", config.getSessionId(), beginSeqNo, endSeqNo);

        sendAdminMessage(FixTags.MSG_TYPE_RESEND_REQUEST, msg -> {
            msg.setField(FixTags.BEGIN_SEQ_NO, beginSeqNo);
            msg.setField(FixTags.END_SEQ_NO, endSeqNo);
        });
    }

    private void sendGapFill(int beginSeqNo, int newSeqNo) {
        log.debug("[{}] Sending GapFill: {} to {}", config.getSessionId(), beginSeqNo, newSeqNo);

        // Gap fill uses the sequence number of the first message in the gap (not auto-incremented)
        RingBufferOutgoingMessage msg = tryClaimMessageWithSeqNum(FixTags.MSG_TYPE_SEQUENCE_RESET, beginSeqNo);
        if (msg == null) {
            log.warn("[{}] Failed to claim ring buffer for gap fill", config.getSessionId());
            return;
        }

        msg.setField(FixTags.POSS_DUP_FLAG, "Y");
        msg.setField(FixTags.GAP_FILL_FLAG, "Y");
        msg.setField(FixTags.NEW_SEQ_NO, newSeqNo);

        commitMessage(msg);
    }

    private void sendReject(int refSeqNum, String refMsgType, int rejectReason, String text) {
        log.warn("[{}] Sending Reject: RefSeqNum={}, Reason={}, Text={}",
                config.getSessionId(), refSeqNum, rejectReason, text);

        sendAdminMessage(FixTags.MSG_TYPE_REJECT, msg -> {
            msg.setField(FixTags.REF_SEQ_NUM, refSeqNum);
            if (refMsgType != null) {
                msg.setField(FixTags.REF_MSG_TYPE, refMsgType);
            }
            msg.setField(FixTags.SESSION_REJECT_REASON, rejectReason);
            if (text != null) {
                msg.setField(FixTags.TEXT, text);
            }
        });
    }

    private void resendMessage(LogEntry entry) {
        // Set PossDupFlag and OrigSendingTime for resends
        // The original message is in entry.getRawMessage(), we need to modify it
        log.debug("[{}] Resending message: SeqNum={}", config.getSessionId(), entry.getSequenceNumber());

        TcpChannel ch = channel;
        if (ch != null && state.get() != SessionState.DISCONNECTED) {
            byte[] rawMessage = entry.getRawMessage();
            int written = ch.writeRaw(rawMessage, 0, rawMessage.length);
            if (written > 0) {
                lastSentTime = clockProvider.currentTimeMillis();
                log.info("[{}] OUT <<< (resend) {}", config.getSessionId(), formatRawMessage(rawMessage));
            } else {
                log.error("[{}] Failed to write resend message to ring buffer", config.getSessionId());
            }
        }
    }

    /**
     * Extract the MsgType (tag 35) from a raw FIX message.
     */
    private String extractMsgType(byte[] rawMessage) {
        if (rawMessage == null) return null;
        String msg = new String(rawMessage);
        int start = msg.indexOf("35=");
        if (start < 0) return null;
        start += 3; // Skip "35="
        int end = msg.indexOf('\u0001', start);
        if (end < 0) end = msg.length();
        return msg.substring(start, end);
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

        TcpChannel ch = channel;
        if (ch != null) {
            ch.close();
        }

        // Ensure state transitions to DISCONNECTED
        // The onDisconnected callback may not be called when disconnect is initiated locally
        SessionState currentState = state.get();
        if (currentState != SessionState.DISCONNECTED && currentState != SessionState.CREATED) {
            setState(SessionState.DISCONNECTED);
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

        long now = clockProvider.currentTimeMillis();
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

    /**
     * Format raw FIX message bytes for logging.
     * Replaces SOH (0x01) delimiter with '|' for readability.
     */
    private static String formatRawMessage(byte[] rawMessage) {
        if (rawMessage == null || rawMessage.length == 0) {
            return "<empty>";
        }
        return formatRawMessage(rawMessage, rawMessage.length);
    }

    /**
     * Format raw FIX message bytes for logging with specific length.
     * Replaces SOH (0x01) delimiter with '|' for readability.
     */
    private static String formatRawMessage(byte[] rawMessage, int length) {
        if (rawMessage == null || length == 0) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            byte b = rawMessage[i];
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
