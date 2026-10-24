package com.omnibridge.fix.engine.session;

import com.omnibridge.config.ClockProvider;
import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.engine.timing.StageTimingRecorder;
import com.omnibridge.fix.engine.timing.StageTimingReport;
import com.omnibridge.fix.message.ApplVerID;
import com.omnibridge.fix.message.FixReader;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.FixVersion;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.message.IncomingMessagePool;
import com.omnibridge.fix.message.IncomingMessagePoolConfig;
import com.omnibridge.fix.message.MessagePoolConfig;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.network.NetworkHandler;
import com.omnibridge.network.TcpChannel;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import io.micrometer.core.instrument.*;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
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
    private final FixReader reader;

    // Reads that returned the full cap on this connection (more data was probably waiting).
    // Written on the event loop only; volatile so the application can read it.
    private final int readCapBytes;
    private volatile long readCapHits;
    private boolean readCapSummaryLogged;

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

    // FIX 5.0+ support
    private final FixVersion fixVersion;
    private ApplVerID negotiatedApplVerID;

    // Thread-local ring buffer message wrappers for concurrent access
    private final ThreadLocal<RingBufferOutgoingMessage> ringBufferMessageThreadLocal;

    // Configuration for ring buffer messages
    private final MessagePoolConfig poolConfig;

    // Latency stage timing (null when off). Stamps taken on the sending thread (claim, commit,
    // prepared) travel in the last TIMING_TAIL_BYTES of the claimed ring record, past the
    // message's own space; the event loop keeps the rest in the fields below.
    private static final int TIMING_TAIL_BYTES = 3 * Long.BYTES;
    private static final Logger timingLog = LoggerFactory.getLogger("com.omnibridge.fix.engine.timing");
    private final StageTimingRecorder stageTiming;
    private final int claimTailBytes;
    private boolean headTimed;
    private long headClaim;
    private long headCommit;
    private long headPrepared;
    private long headDrain;
    private long headBeforeWrite;
    private long recvRead;
    private long recvFramed;
    private long recvParsed;

    // ==================== Metrics (pre-resolved for hot path) ====================
    private Counter messagesReceivedCounter;
    private Counter messagesSentCounter;
    private Counter messagesRejectedCounter;
    private Counter businessRejectsCounter;
    private Counter logonCounter;
    private Counter logoutCounter;
    private Counter disconnectCounter;
    private Counter connectFailedCounter;
    private Counter heartbeatSentCounter;
    private Counter heartbeatReceivedCounter;
    private Counter testRequestSentCounter;
    private Counter testRequestReceivedCounter;
    private Counter heartbeatTimeoutCounter;
    private Counter sequenceGapCounter;
    private Counter sequenceResetCounter;
    private Counter resendRequestSentCounter;
    private Counter resendRequestReceivedCounter;
    private Counter claimFailedCounter;
    private Counter readCapHitCounter;
    private DistributionSummary processingTimeSummary;

    // Local message counters (single-threaded network loop, no sync needed)
    private long localMessagesSentCount;
    private long localMessagesReceivedCount;

    public FixSession(SessionConfig config, LogStore logStore) {
        this.config = config;
        this.logStore = logStore;
        this.clockProvider = config.getClockProvider();

        // FIX 5.0+ support
        this.fixVersion = config.getFixVersion();
        this.negotiatedApplVerID = config.getDefaultApplVerID();

        // Initialize message pool configuration (used by ring buffer message)
        MessagePoolConfig.Builder poolConfigBuilder = MessagePoolConfig.builder()
                .maxMessageLength(config.getMaxMessageLength())
                .maxTagNumber(config.getMaxTagNumber())
                .beginString(config.getBeginString())
                .senderCompId(config.getSenderCompId())
                .targetCompId(config.getTargetCompId())
                .clockProvider(clockProvider);

        // Configure FIX version for FIXT.1.1 mode
        if (fixVersion != null) {
            poolConfigBuilder.fixVersion(fixVersion);
            if (config.getDefaultApplVerID() != null) {
                poolConfigBuilder.defaultApplVerID(config.getDefaultApplVerID());
            }
        }

        this.poolConfig = poolConfigBuilder.build();

        // Reads are capped; the reader holds at most one read plus a partial message, so sized
        // like this it never grows on the event loop
        this.readCapBytes = config.getReadCapBytes();
        this.reader = new FixReader(Math.max(64 * 1024, readCapBytes + config.getMaxMessageLength()));

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

        if (config.getStageTimingCapacity() > 0) {
            this.stageTiming = new StageTimingRecorder(config.getStageTimingCapacity());
            this.claimTailBytes = TIMING_TAIL_BYTES;
            reader.setFrameTiming(true);
            log.info("[{}] Stage timing enabled: {} rows per direction", config.getSessionId(),
                    stageTiming.getCapacity());
        } else {
            this.stageTiming = null;
            this.claimTailBytes = 0;
        }

        // Recover sequence numbers from persistence store
        recoverSequenceNumbers();
    }

    /**
     * Recover sequence numbers from the persistence store.
     * Sets outgoingSeqNum and expectedIncomingSeqNum based on the last persisted entries.
     */
    private void recoverSequenceNumbers() {
        if (logStore == null || !config.isLogMessages()) {
            return;
        }

        String sessionId = config.getSessionId();

        try {
            LogEntry lastOutbound = logStore.getLatest(sessionId, LogEntry.Direction.OUTBOUND);
            if (lastOutbound != null && lastOutbound.getSequenceNumber() > 0) {
                int recovered = lastOutbound.getSequenceNumber() + 1;
                outgoingSeqNum.set(recovered);
                log.info("[{}] Recovered outgoing sequence number: {} (next={})",
                        sessionId, lastOutbound.getSequenceNumber(), recovered);
            }

            LogEntry lastInbound = logStore.getLatest(sessionId, LogEntry.Direction.INBOUND);
            if (lastInbound != null && lastInbound.getSequenceNumber() > 0) {
                int recovered = lastInbound.getSequenceNumber() + 1;
                expectedIncomingSeqNum.set(recovered);
                log.info("[{}] Recovered expected incoming sequence number: {} (next={})",
                        sessionId, lastInbound.getSequenceNumber(), recovered);
            }
        } catch (Exception e) {
            log.warn("[{}] Failed to recover sequence numbers from persistence, starting from 1: {}",
                    sessionId, e.getMessage());
        }
    }

    // ==================== Metrics Binding ====================

    /**
     * Bind Micrometer metrics for this session.
     * Pre-resolves all meters with session tags for zero-allocation hot-path use.
     *
     * @param registry the meter registry (null to disable metrics)
     */
    public void bindMetrics(MeterRegistry registry) {
        if (registry == null) {
            return;
        }

        Tags sessionTags = Tags.of(
                "session_id", config.getSessionId(),
                "protocol", "FIX",
                "role", config.isAcceptor() ? "acceptor" : "initiator"
        );

        // Session state gauge
        Gauge.builder("omnibridge.session.state", state, s -> s.get().ordinal())
                .tags(sessionTags)
                .description("Session state ordinal")
                .register(registry);

        Gauge.builder("omnibridge.session.logged_on", state, s -> s.get() == SessionState.LOGGED_ON ? 1.0 : 0.0)
                .tags(sessionTags)
                .description("Fully authenticated (0/1)")
                .register(registry);

        Gauge.builder("omnibridge.session.connected", state,
                        s -> s.get().isConnected() ? 1.0 : 0.0)
                .tags(sessionTags)
                .description("TCP connected (0/1)")
                .register(registry);

        // Sequence number gauges
        Gauge.builder("omnibridge.sequence.outgoing", outgoingSeqNum, AtomicInteger::doubleValue)
                .tags(sessionTags)
                .description("Current outgoing seqnum")
                .register(registry);

        Gauge.builder("omnibridge.sequence.incoming_expected", expectedIncomingSeqNum, AtomicInteger::doubleValue)
                .tags(sessionTags)
                .description("Expected incoming seqnum")
                .register(registry);

        // Heartbeat timing gauges
        Gauge.builder("omnibridge.heartbeat.last_received.seconds", this,
                        s -> (s.clockProvider.currentTimeMillis() - s.lastReceivedTime) / 1000.0)
                .tags(sessionTags)
                .description("Seconds since last message received")
                .register(registry);

        Gauge.builder("omnibridge.heartbeat.last_sent.seconds", this,
                        s -> (s.clockProvider.currentTimeMillis() - s.lastSentTime) / 1000.0)
                .tags(sessionTags)
                .description("Seconds since last message sent")
                .register(registry);

        Gauge.builder("omnibridge.heartbeat.interval.seconds", config, c -> c.getHeartbeatInterval())
                .tags(sessionTags)
                .description("Configured heartbeat interval")
                .register(registry);

        // Message counters
        messagesReceivedCounter = Counter.builder("omnibridge.messages.received.total")
                .tags(sessionTags).description("Messages received").register(registry);
        messagesSentCounter = Counter.builder("omnibridge.messages.sent.total")
                .tags(sessionTags).description("Messages sent").register(registry);
        messagesRejectedCounter = Counter.builder("omnibridge.messages.rejected.total")
                .tags(sessionTags).description("Session-level rejects").register(registry);
        businessRejectsCounter = Counter.builder("omnibridge.messages.business_rejected.total")
                .tags(sessionTags).description("Business rejects").register(registry);

        // Session lifecycle counters
        logonCounter = Counter.builder("omnibridge.session.logon.total")
                .tags(sessionTags).description("Cumulative logon count").register(registry);
        logoutCounter = Counter.builder("omnibridge.session.logout.total")
                .tags(sessionTags).description("Logout count").register(registry);
        disconnectCounter = Counter.builder("omnibridge.session.disconnect.total")
                .tags(sessionTags).description("Disconnect count").register(registry);
        connectFailedCounter = Counter.builder("omnibridge.session.connect_failed.total")
                .tags(sessionTags).description("Connection failures").register(registry);

        // Heartbeat counters
        heartbeatSentCounter = Counter.builder("omnibridge.heartbeat.sent.total")
                .tags(sessionTags).description("Heartbeats sent").register(registry);
        heartbeatReceivedCounter = Counter.builder("omnibridge.heartbeat.received.total")
                .tags(sessionTags).description("Heartbeats received").register(registry);
        testRequestSentCounter = Counter.builder("omnibridge.heartbeat.test_request.sent.total")
                .tags(sessionTags).description("Test requests sent").register(registry);
        testRequestReceivedCounter = Counter.builder("omnibridge.heartbeat.test_request.received.total")
                .tags(sessionTags).description("Test requests received").register(registry);
        heartbeatTimeoutCounter = Counter.builder("omnibridge.heartbeat.timeout.total")
                .tags(sessionTags).description("Heartbeat timeout disconnects").register(registry);

        // Sequence counters
        sequenceGapCounter = Counter.builder("omnibridge.sequence.gap.total")
                .tags(sessionTags).description("Sequence gaps detected").register(registry);
        sequenceResetCounter = Counter.builder("omnibridge.sequence.reset.total")
                .tags(sessionTags).description("Sequence resets").register(registry);
        resendRequestSentCounter = Counter.builder("omnibridge.resend.request.total")
                .tags(sessionTags).tag("direction", "sent").description("Resend requests sent").register(registry);
        resendRequestReceivedCounter = Counter.builder("omnibridge.resend.request.total")
                .tags(sessionTags).tag("direction", "received").description("Resend requests received").register(registry);

        // Ring buffer
        claimFailedCounter = Counter.builder("omnibridge.ringbuffer.claim_failed.total")
                .tags(sessionTags).description("Ring buffer claim failures").register(registry);

        // Network reads
        readCapHitCounter = Counter.builder("omnibridge.network.read_cap_reached.total")
                .tags(sessionTags).description("Reads that returned the full read cap").register(registry);

        // Latency
        processingTimeSummary = DistributionSummary.builder("omnibridge.message.processing.time")
                .tags(sessionTags)
                .description("Message processing time in nanoseconds")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99, 0.999)
                .register(registry);

        log.info("[{}] Metrics bound", config.getSessionId());
    }

    // ==================== Session Management ====================

    /**
     * Set the TCP channel for this session.
     */
    public void setChannel(TcpChannel channel) {
        this.channel = channel;
        if (channel != null) {
            channel.setOutgoingFrameHandler(this::onOutgoingFrame);
            registerOutgoingMessageListener(channel);
            registerFrameWrittenListener(channel);
        }
    }

    /**
     * Get the TCP channel.
     */
    public TcpChannel getChannel() {
        return channel;
    }

    public long getLocalMessagesSentCount() {
        return localMessagesSentCount;
    }

    public long getLocalMessagesReceivedCount() {
        return localMessagesReceivedCount;
    }

    public long getLastSentTime() {
        return lastSentTime;
    }

    public long getLastReceivedTime() {
        return lastReceivedTime;
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
        return readCapBytes;
    }

    /**
     * Reads on the current connection that returned the full read cap.
     */
    public long getReadCapHits() {
        return readCapHits;
    }

    /**
     * Most bytes this session takes from the socket in one read.
     */
    public int getReadCapBytes() {
        return readCapBytes;
    }

    private void onReadCapReached() {
        long hits = readCapHits + 1;
        readCapHits = hits;
        if (readCapHitCounter != null) {
            readCapHitCounter.increment();
        }
        // Once per connection: logging on the event loop per hit would cost more than the read
        if (hits == 1) {
            log.warn("[{}] Read cap reached: a read returned the full {} bytes, more data was waiting. "
                    + "Further hits are counted and summarised at Logout", config.getSessionId(), readCapBytes);
        }
    }

    private void reportReadCap() {
        if (!readCapSummaryLogged) {
            readCapSummaryLogged = true;
            timingLog.info("[{}] read cap reached {} times, cap {}", config.getSessionId(), readCapHits, readCapBytes);
        }
    }

    @Override
    public void onConnected(TcpChannel channel) {
        log.info("[{}] Connected to {}:{}", config.getSessionId(), config.getHost(), config.getPort());
        this.channel = channel;
        this.ioThread = Thread.currentThread();
        channel.setOutgoingFrameHandler(this::onOutgoingFrame);
        registerOutgoingMessageListener(channel);
        registerFrameWrittenListener(channel);
        setState(SessionState.CONNECTED);
        lastReceivedTime = clockProvider.currentTimeMillis();

        // Reset reader state for new connection
        // This is critical for reconnection scenarios where the reader may have
        // stale state (accumulated buffer data, expected message length) from
        // the previous connection
        reader.reset();
        readCapHits = 0;
        readCapSummaryLogged = false;

        // Initiator sends logon first
        if (config.isInitiator()) {
            sendLogon();
        }
    }

    @Override
    public int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length) {
        if (stageTiming != null) {
            recvRead = System.nanoTime();
        }
        lastReceivedTime = clockProvider.currentTimeMillis();
        if (length == readCapBytes) {
            onReadCapReached();
        }

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
            if (stageTiming != null) {
                recvFramed = reader.getLastFramedNanos();
                recvParsed = System.nanoTime();
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

        if (disconnectCounter != null) {
            disconnectCounter.increment();
        }
        reportReadCap();

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

        if (connectFailedCounter != null) {
            connectFailedCounter.increment();
        }

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
        long processingStart = System.nanoTime();
        String msgType = message.getMsgType();
        int seqNum = message.getSeqNum();

        localMessagesReceivedCount++;
        if (messagesReceivedCounter != null) {
            messagesReceivedCounter.increment();
        }

        if (log.isDebugEnabled()) {
            log.debug("[{}] Received: {} SeqNum={}", config.getSessionId(), msgType, seqNum);
            log.debug("[{}] IN  >>> {}", config.getSessionId(), formatRawMessage(message.getRawMessage()));
        }

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
                if (sequenceGapCounter != null) {
                    sequenceGapCounter.increment();
                }
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
        long dispatchNanos = stageTiming != null ? System.nanoTime() : 0;
        for (MessageListener listener : messageListeners) {
            try {
                listener.onMessage(this, message);
            } catch (Exception e) {
                log.error("[{}] Error notifying message listener", config.getSessionId(), e);
            }
        }
        if (stageTiming != null) {
            stageTiming.recordReceive(recvRead, recvFramed, recvParsed, dispatchNanos, System.nanoTime());
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

        // Record processing time
        if (processingTimeSummary != null) {
            processingTimeSummary.record(System.nanoTime() - processingStart);
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

    // Ring record types for outgoing messages (TcpChannel.MSG_TYPE_RAW is sent unchanged)
    private static final int FRAME_STAMP_SEQ_NUM = 2;
    private static final int FRAME_SET_NEXT_SEQ_NUM = 3;
    private static final long SEQ_NUM_CONTROL_WAIT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);

    private final java.util.concurrent.atomic.AtomicLong seqNumControlsIssued = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong seqNumControlsApplied = new java.util.concurrent.atomic.AtomicLong();
    // The event loop thread that drains this session's channel
    private volatile Thread ioThread;

    private TcpChannel claimedChannel(RingBufferOutgoingMessage msg) {
        Object owner = msg.getClaimOwner();
        return owner instanceof TcpChannel ch ? ch : channel;
    }

    /**
     * Event loop hook, called once per ring record in ring order before it is sent.
     * This is the only place outgoing MsgSeqNum values are assigned while connected.
     */
    private boolean onOutgoingFrame(int msgTypeId, MutableDirectBuffer buffer, int offset, int length) {
        if (msgTypeId == FRAME_STAMP_SEQ_NUM) {
            long drainNanos = stageTiming != null ? System.nanoTime() : 0;
            int seqNum = outgoingSeqNum.get();
            stampSeqNum(buffer, offset, length, seqNum);
            outgoingSeqNum.lazySet(seqNum + 1);
            if (config.isTraceMessages() && log.isInfoEnabled()) {
                log.info("[{}] OUT <<< {}", config.getSessionId(),
                        buffer.getStringWithoutLengthAscii(offset, length).replace('\u0001', '|'));
            }
            if (stageTiming != null) {
                int tail = offset - RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE + timingTailOffset();
                headClaim = buffer.getLong(tail);
                headCommit = buffer.getLong(tail + Long.BYTES);
                headPrepared = buffer.getLong(tail + 2 * Long.BYTES);
                headDrain = drainNanos;
                // Replaced after outbound persistence when a log store listener is registered
                headBeforeWrite = System.nanoTime();
                headTimed = true;
            }
            return true;
        }
        headTimed = false;
        if (msgTypeId == FRAME_SET_NEXT_SEQ_NUM) {
            outgoingSeqNum.set(buffer.getInt(offset));
            seqNumControlsApplied.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Write seqNum into the zero-filled 34= field and correct CheckSum (10=) for the changed digits.
     */
    static void stampSeqNum(MutableDirectBuffer buffer, int offset, int length, int seqNum) {
        int end = offset + length;
        int pos = offset;
        while (pos + 3 < end && !(buffer.getByte(pos) == 0x01 && buffer.getByte(pos + 1) == '3'
                && buffer.getByte(pos + 2) == '4' && buffer.getByte(pos + 3) == '=')) {
            pos++;
        }
        int valueStart = pos + 4;
        int valueEnd = valueStart;
        while (valueEnd < end && buffer.getByte(valueEnd) != 0x01) {
            valueEnd++;
        }

        int delta = 0;
        int value = seqNum;
        for (int i = valueEnd - 1; i >= valueStart; i--) {
            byte digit = (byte) ('0' + value % 10);
            value /= 10;
            delta += digit - buffer.getByte(i);
            buffer.putByte(i, digit);
        }

        // Trailer is 10=NNN<SOH>; its digits sit just before the final SOH
        int checksumPos = end - 4;
        int checksum = (buffer.getByte(checksumPos) - '0') * 100
                + (buffer.getByte(checksumPos + 1) - '0') * 10
                + (buffer.getByte(checksumPos + 2) - '0');
        checksum = ((checksum + delta) % 256 + 256) % 256;
        buffer.putByte(checksumPos, (byte) ('0' + checksum / 100));
        buffer.putByte(checksumPos + 1, (byte) ('0' + (checksum / 10) % 10));
        buffer.putByte(checksumPos + 2, (byte) ('0' + checksum % 10));
    }

    /**
     * Set the number the next sent message gets. While connected this travels through the
     * ring buffer, so messages committed before the call keep the old numbering; the call
     * returns once the event loop has applied it, so getOutgoingSeqNum() reflects it.
     */
    private void setNextOutgoingSeqNum(int next) {
        TcpChannel ch = channel;
        if (ch == null || ch.isClosed()) {
            outgoingSeqNum.set(next);
            return;
        }
        long issued = seqNumControlsIssued.incrementAndGet();
        if (!ch.writeControl(FRAME_SET_NEXT_SEQ_NUM, next)) {
            seqNumControlsIssued.decrementAndGet();
            outgoingSeqNum.set(next);
            return;
        }

        if (Thread.currentThread() == ioThread) {
            // We are the drainer: send what is queued ahead of the control record now
            try {
                ch.drainRingBufferToSocket();
            } catch (IOException e) {
                log.warn("[{}] Write failed while applying sequence number {}: {}",
                        config.getSessionId(), next, e.getMessage());
            }
        } else {
            long deadline = System.nanoTime() + SEQ_NUM_CONTROL_WAIT_NANOS;
            while (seqNumControlsApplied.get() < issued && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }
        if (seqNumControlsApplied.get() < issued) {
            log.warn("[{}] Next outgoing sequence number {} is queued behind unsent messages",
                    config.getSessionId(), next);
        }
    }

    /**
     * Register an outgoing message listener on the channel for persistence logging.
     * The listener fires during ring buffer drain on the event loop thread,
     * ensuring single-writer semantics for Chronicle Queue.
     */
    private void registerOutgoingMessageListener(TcpChannel channel) {
        if (logStore != null && config.isLogMessages()) {
            channel.setOutgoingMessageListener((buffer, offset, length) -> {
                byte[] logCopy = new byte[length];
                buffer.getBytes(offset, logCopy, 0, length);
                int seqNum = parseSeqNum(logCopy);
                LogEntry entry = LogEntry.builder()
                    .timestamp(Instant.now().toEpochMilli())
                    .sequenceNumber(seqNum)
                    .direction(LogEntry.Direction.OUTBOUND)
                    .streamName(config.getSessionId())
                    .rawMessage(logCopy)
                    .build();
                logStore.write(entry);
                if (headTimed) {
                    headBeforeWrite = System.nanoTime();
                }
            });
        }
    }

    /**
     * With stage timing on, record each timed message's send row once its last byte is written.
     */
    private void registerFrameWrittenListener(TcpChannel channel) {
        if (stageTiming != null) {
            channel.setFrameWrittenListener(this::onFrameWritten);
        }
    }

    private void onFrameWritten(int msgTypeId) {
        if (headTimed) {
            headTimed = false;
            stageTiming.recordSend(headClaim, headCommit, headPrepared, headDrain, headBeforeWrite,
                    System.nanoTime());
        }
    }

    /** Offset of the stage timing stamps from the start of a claimed record. */
    private int timingTailOffset() {
        return RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE + config.getMaxMessageLength();
    }

    /**
     * The stage timing of this session, or null when stage timing is off.
     */
    public StageTimingRecorder getStageTiming() {
        return stageTiming;
    }

    /**
     * Discard the stage timing recorded so far, here and on the other side: a TestRequest
     * with TestReqID {@link StageTimingRecorder#RESET_TEST_REQ_ID} resets a counterparty
     * that has stage timing on (others just answer it with a Heartbeat).
     *
     * @return false if stage timing is off (nothing is sent)
     */
    public boolean requestStageTimingReset() {
        if (stageTiming == null) {
            return false;
        }
        stageTiming.requestReset();
        sendAdminMessage(FixTags.MSG_TYPE_TEST_REQUEST, msg ->
                msg.setField(FixTags.TEST_REQ_ID, StageTimingRecorder.RESET_TEST_REQ_ID));
        return true;
    }

    private void reportStageTimingOnLogout() {
        if (stageTiming != null && !stageTiming.isReported()
                && (stageTiming.getSendCount() > 0 || stageTiming.getReceiveCount() > 0)) {
            timingLog.info("{}{}", System.lineSeparator(),
                    StageTimingReport.format(stageTiming, config.getSessionId()));
            stageTiming.markReported();
        }
    }

    /**
     * Parse MsgSeqNum (tag 34) from raw FIX message bytes.
     * Scans for SOH + "34=" pattern and parses the integer value inline.
     */
    private static int parseSeqNum(byte[] fixMessage) {
        for (int i = 0; i < fixMessage.length - 4; i++) {
            if (fixMessage[i] == 0x01 && fixMessage[i + 1] == '3' && fixMessage[i + 2] == '4' && fixMessage[i + 3] == '=') {
                int num = 0;
                for (int j = i + 4; j < fixMessage.length && fixMessage[j] != 0x01; j++) {
                    num = num * 10 + (fixMessage[j] - '0');
                }
                return num;
            }
        }
        return 0;
    }

    // ==================== Incoming Admin Message Handlers (for pooled messages) ====================

    private void processIncomingLogon(IncomingFixMessage message) {
        log.info("[{}] Logon received", config.getSessionId());

        int heartbeatInt = message.getInt(FixTags.HEARTBT_INT);
        boolean resetSeqNum = message.getBool(FixTags.RESET_SEQ_NUM_FLAG);

        // FIX 5.0+: Process DefaultApplVerID (tag 1137)
        if (fixVersion != null && fixVersion.usesFixt()) {
            CharSequence defaultApplVerIdCs = message.getCharSequence(FixTags.DefaultApplVerID);
            if (defaultApplVerIdCs != null && defaultApplVerIdCs.length() > 0) {
                try {
                    ApplVerID receivedApplVerID = ApplVerID.fromValue(defaultApplVerIdCs);
                    negotiatedApplVerID = receivedApplVerID;
                    log.info("[{}] Negotiated ApplVerID: {}", config.getSessionId(), negotiatedApplVerID);
                } catch (IllegalArgumentException e) {
                    log.warn("[{}] Invalid DefaultApplVerID received: {}",
                            config.getSessionId(), defaultApplVerIdCs);
                }
            } else {
                log.warn("[{}] FIX 5.0+ session but no DefaultApplVerID in Logon",
                        config.getSessionId());
            }
        }

        SessionState currentState = state.get();

        if (resetSeqNum || config.isResetOnLogon()) {
            log.info("[{}] Resetting sequence numbers", config.getSessionId());
            if (config.isInitiator()) {
                setNextOutgoingSeqNum(2);
            } else {
                setNextOutgoingSeqNum(1);
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

        if (logonCounter != null) {
            logonCounter.increment();
        }
    }

    private void processIncomingLogout(IncomingFixMessage message) {
        CharSequence textCs = message.getCharSequence(FixTags.TEXT);
        String text = textCs != null ? textCs.toString() : null;
        log.info("[{}] Logout received: {}", config.getSessionId(), text);
        reportStageTimingOnLogout();
        reportReadCap();

        SessionState currentState = state.get();

        if (currentState == SessionState.LOGGED_ON) {
            sendLogout("Logout acknowledged");
        }

        if (config.isResetOnLogout()) {
            resetSequenceNumbers();
        }

        if (logoutCounter != null) {
            logoutCounter.increment();
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

        if (heartbeatReceivedCounter != null) {
            heartbeatReceivedCounter.increment();
        }

        if (testRequestPending && testReqId != null) {
            testRequestPending = false;
        }
    }

    private void processIncomingTestRequest(IncomingFixMessage message) {
        CharSequence testReqIdCs = message.getCharSequence(FixTags.TEST_REQ_ID);
        String testReqId = testReqIdCs != null ? testReqIdCs.toString() : null;
        log.debug("[{}] TestRequest received, TestReqID={}", config.getSessionId(), testReqId);

        if (testRequestReceivedCounter != null) {
            testRequestReceivedCounter.increment();
        }

        if (stageTiming != null && StageTimingRecorder.RESET_TEST_REQ_ID.equals(testReqId)) {
            stageTiming.requestReset();
            timingLog.info("[{}] Stage timing reset by counterparty", config.getSessionId());
        }

        sendHeartbeat(testReqId);
    }

    private void processIncomingResendRequest(IncomingFixMessage message) {
        int beginSeqNo = message.getInt(FixTags.BEGIN_SEQ_NO);
        int endSeqNo = message.getInt(FixTags.END_SEQ_NO);

        log.info("[{}] ResendRequest received: {} to {}", config.getSessionId(), beginSeqNo, endSeqNo);

        if (resendRequestReceivedCounter != null) {
            resendRequestReceivedCounter.increment();
        }

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

        if (sequenceResetCounter != null) {
            sequenceResetCounter.increment();
        }

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

        if (messagesRejectedCounter != null) {
            messagesRejectedCounter.increment();
        }

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

        if (businessRejectsCounter != null) {
            businessRejectsCounter.increment();
        }

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

        long claimNanos = stageTiming != null ? System.nanoTime() : 0;

        // Calculate claim size: length prefix + max message length (+ stage timing stamps)
        int claimSize = RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE + config.getMaxMessageLength()
                + claimTailBytes;

        // Try to claim space in the ring buffer. MsgSeqNum is left as zeros and stamped by
        // onOutgoingFrame on the event loop, so wire order and number order always agree.
        int claimIndex = ch.tryClaim(FRAME_STAMP_SEQ_NUM, claimSize);
        if (claimIndex < 0) {
            // Ring buffer is full
            if (claimFailedCounter != null) {
                claimFailedCounter.increment();
            }
            return null;
        }

        // Get the buffer and wrap the thread-local message
        MutableDirectBuffer buffer = ch.buffer();
        RingBufferOutgoingMessage message = ringBufferMessageThreadLocal.get();
        message.wrap(buffer, claimIndex, claimSize - claimTailBytes, 0, claimIndex,
                config.getBeginString(), config.getSenderCompId(), config.getTargetCompId(), msgType);
        message.setClaimOwner(ch);
        if (stageTiming != null) {
            buffer.putLong(claimIndex + timingTailOffset(), claimNanos);
        }

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
        long commitNanos = stageTiming != null ? System.nanoTime() : 0;

        // Prepare the message
        long sendingTime = clockProvider.currentTimeMillis();
        int messageLength = msg.prepareForSend(sendingTime);

        // Outgoing message logging is handled by the OutgoingMessageListener
        // registered on the TcpChannel, which fires during ring buffer drain
        // on the event loop thread (ensuring single-writer for Chronicle Queue).

        // Commit to the ring buffer the message was claimed from
        TcpChannel ch = claimedChannel(msg);
        if (ch != null) {
            if (stageTiming != null) {
                // The stamps must be in the record before the commit makes it visible
                int tail = msg.getClaimIndex() + timingTailOffset();
                msg.getBuffer().putLong(tail + Long.BYTES, commitNanos);
                long preparedNanos = System.nanoTime();
                msg.getBuffer().putLong(tail + 2 * Long.BYTES, preparedNanos);
                ch.commit(msg.getClaimIndex());
                stageTiming.recordCommitCall(System.nanoTime() - preparedNanos);
            } else {
                ch.commit(msg.getClaimIndex());
            }
            lastSentTime = clockProvider.currentTimeMillis();

            localMessagesSentCount++;
            if (messagesSentCounter != null) {
                messagesSentCounter.increment();
            }

            log.debug("[{}] Committed to ring buffer: {}", config.getSessionId(), msg.getMsgType());
        }
    }

    /**
     * Abort a ring buffer message, discarding it.
     *
     * <p>Use this method to rollback a claim if encoding fails. No sequence
     * number is consumed: numbers are assigned only to messages that are sent.</p>
     *
     * @param msg the ring buffer message to abort
     */
    public void abortMessage(RingBufferOutgoingMessage msg) {
        TcpChannel ch = claimedChannel(msg);
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

        // Calculate claim size: length prefix + max message length (+ room commitMessage stamps into)
        int claimSize = RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE + config.getMaxMessageLength()
                + claimTailBytes;

        // Try to claim space in the ring buffer (raw: the explicit number is not re-stamped)
        int claimIndex = ch.tryClaim(TcpChannel.MSG_TYPE_RAW, claimSize);
        if (claimIndex < 0) {
            // Ring buffer is full
            return null;
        }

        // Get the buffer and wrap the thread-local message
        MutableDirectBuffer buffer = ch.buffer();
        RingBufferOutgoingMessage message = ringBufferMessageThreadLocal.get();
        message.wrap(buffer, claimIndex, claimSize - claimTailBytes, seqNum, claimIndex,
                config.getBeginString(), config.getSenderCompId(), config.getTargetCompId(), msgType);
        message.setClaimOwner(ch);

        return message;
    }

    private void sendLogon() {
        log.info("[{}] Sending Logon (version={})", config.getSessionId(),
                fixVersion != null ? fixVersion : "FIX.4.4");

        setState(SessionState.LOGON_SENT);

        sendAdminMessage(FixTags.MSG_TYPE_LOGON, msg -> {
            msg.setField(FixTags.ENCRYPT_METHOD, 0); // None
            msg.setField(FixTags.HEARTBT_INT, config.getHeartbeatInterval());
            if (config.isResetOnLogon()) {
                msg.setField(FixTags.RESET_SEQ_NUM_FLAG, "Y");
            }
            // FIX 5.0+: Include DefaultApplVerID (tag 1137)
            if (fixVersion != null && fixVersion.usesFixt()) {
                ApplVerID applVerID = config.getDefaultApplVerID();
                if (applVerID == null) {
                    applVerID = fixVersion.getDefaultApplVerID();
                }
                if (applVerID != null) {
                    msg.setField(FixTags.DefaultApplVerID, applVerID.getValue());
                    log.debug("[{}] Including DefaultApplVerID={} in Logon",
                            config.getSessionId(), applVerID);
                }
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
        if (heartbeatSentCounter != null) {
            heartbeatSentCounter.increment();
        }

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

        if (testRequestSentCounter != null) {
            testRequestSentCounter.increment();
        }

        sendAdminMessage(FixTags.MSG_TYPE_TEST_REQUEST, msg -> {
            msg.setField(FixTags.TEST_REQ_ID, testReqId);
        });
        testRequestPending = true;
        return testReqId;
    }

    private void sendResendRequest(int beginSeqNo, int endSeqNo) {
        log.info("[{}] Sending ResendRequest: {} to {}", config.getSessionId(), beginSeqNo, endSeqNo);

        if (resendRequestSentCounter != null) {
            resendRequestSentCounter.increment();
        }

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
        setNextOutgoingSeqNum(1);
        expectedIncomingSeqNum.set(1);
    }

    /**
     * Get the FIX protocol version for this session.
     *
     * @return the FIX version
     */
    public FixVersion getFixVersion() {
        return fixVersion;
    }

    /**
     * Get the negotiated ApplVerID for FIX 5.0+ sessions.
     * This is determined during logon.
     *
     * @return the negotiated ApplVerID, or null for FIX 4.x
     */
    public ApplVerID getNegotiatedApplVerID() {
        return negotiatedApplVerID;
    }

    /**
     * Check if this session uses FIXT.1.1 transport (FIX 5.0+).
     *
     * @return true if using FIXT.1.1
     */
    public boolean usesFixt() {
        return fixVersion != null && fixVersion.usesFixt();
    }

    /**
     * Whether execution reports on this session must carry ExecTransType (20):
     * required in FIX 4.0 to 4.2, removed from FIX 4.3 on.
     */
    public boolean requiresExecTransType() {
        String beginString = config.getBeginString();
        return "FIX.4.0".equals(beginString) || "FIX.4.1".equals(beginString) || "FIX.4.2".equals(beginString);
    }

    /**
     * Get the next outgoing sequence number: the one the next message to reach the wire gets.
     * Messages committed but not yet drained by the event loop are not counted.
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
        setNextOutgoingSeqNum(seqNum);
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
                if (heartbeatTimeoutCounter != null) {
                    heartbeatTimeoutCounter.increment();
                }
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
