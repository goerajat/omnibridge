package com.omnibridge.sbe.engine.session;

import com.omnibridge.network.NetworkHandler;
import com.omnibridge.network.TcpChannel;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.sbe.engine.config.SbeSessionConfig;
import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.sbe.message.SbeMessageFactory;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Abstract base class for SBE protocol sessions.
 * <p>
 * Manages an SBE session over TCP. Handles message sending/receiving,
 * session lifecycle, and state management.
 * <p>
 * Thread Safety:
 * <ul>
 *   <li>Message sending uses lock-based synchronization for thread-safe operations</li>
 *   <li>State transitions are atomic</li>
 *   <li>Listener notifications are thread-safe via CopyOnWriteArrayList</li>
 *   <li>tryClaim/commit/abort pattern provides thread-safe multi-threaded sending</li>
 * </ul>
 * <p>
 * Protocol-specific implementations (iLink 3, Optiq) must:
 * <ul>
 *   <li>Implement {@link #getMessageFactory()} to provide the message factory</li>
 *   <li>Implement {@link #processIncomingMessage(SbeMessage)} for message handling</li>
 *   <li>Implement {@link #getExpectedMessageLength(DirectBuffer, int, int)} for framing</li>
 *   <li>Override {@link #onConnected(TcpChannel)} for protocol-specific handshake</li>
 * </ul>
 *
 * @param <C> the session configuration type
 */
public abstract class SbeSession<C extends SbeSessionConfig> implements NetworkHandler {

    private static final Logger log = LoggerFactory.getLogger(SbeSession.class);

    // Configuration
    protected final C config;
    protected final String sessionId;
    protected final String sessionName;
    protected final String host;
    protected final int port;
    protected final boolean isInitiator;

    // Network
    protected TcpChannel channel;

    // State
    protected final AtomicReference<SbeSessionState> state = new AtomicReference<>(SbeSessionState.CREATED);
    protected final AtomicLong outboundSeqNum = new AtomicLong(1);
    protected final AtomicLong inboundSeqNum = new AtomicLong(1);

    // Buffers for zero-copy message handling
    protected final MutableDirectBuffer sendBuffer;
    protected final MutableDirectBuffer receiveBuffer;
    protected int receivePosition = 0;

    // Thread-safe send buffer management for tryClaim pattern
    protected final Lock sendLock = new ReentrantLock();
    protected volatile int claimedOffset = 0;
    protected volatile int claimedLength = 0;

    // Persistence
    protected final LogStore logStore;
    protected final String streamName;

    // Listeners
    protected final List<SessionStateListener> stateListeners = new CopyOnWriteArrayList<>();
    protected final List<MessageListener> messageListeners = new CopyOnWriteArrayList<>();

    // Metrics (pre-resolved for zero-allocation on hot path)
    protected Counter messagesReceivedCounter;
    protected Counter messagesSentCounter;
    protected Counter disconnectCounter;
    protected Counter connectFailedCounter;
    protected DistributionSummary processingTimeSummary;

    // Configuration
    protected static final int DEFAULT_SEND_BUFFER_SIZE = 65536;
    protected static final int DEFAULT_RECEIVE_BUFFER_SIZE = 65536;

    /**
     * Creates a new SBE session.
     *
     * @param config the session configuration
     * @param logStore the log store for message persistence (may be null)
     */
    protected SbeSession(C config, LogStore logStore) {
        this(config, logStore, DEFAULT_SEND_BUFFER_SIZE, DEFAULT_RECEIVE_BUFFER_SIZE);
    }

    /**
     * Creates a new SBE session with custom buffer sizes.
     *
     * @param config the session configuration
     * @param logStore the log store for message persistence (may be null)
     * @param sendBufferSize size of the send buffer
     * @param receiveBufferSize size of the receive buffer
     */
    protected SbeSession(C config, LogStore logStore, int sendBufferSize, int receiveBufferSize) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.sessionId = config.getSessionId();
        this.sessionName = config.getSessionName() != null ? config.getSessionName() : sessionId;
        this.host = config.getHost();
        this.port = config.getPort();
        this.isInitiator = config.isInitiator();
        this.logStore = logStore;
        this.streamName = sessionId;

        this.sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(sendBufferSize));
        this.receiveBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(receiveBufferSize));
    }

    // =====================================================
    // Abstract Methods - Protocol-Specific Implementation
    // =====================================================

    /**
     * Gets the message factory for this session's protocol.
     *
     * @return the message factory
     */
    public abstract SbeMessageFactory getMessageFactory();

    /**
     * Processes an incoming message.
     * Protocol-specific implementations handle message dispatch here.
     *
     * @param message the incoming message
     */
    protected abstract void processIncomingMessage(SbeMessage message);

    /**
     * Gets the expected length of a message from the buffer.
     * Used for message framing.
     *
     * @param buffer the buffer containing message data
     * @param offset the offset of the message
     * @param available the available bytes
     * @return the expected message length, or -1 if more data is needed
     */
    protected abstract int getExpectedMessageLength(DirectBuffer buffer, int offset, int available);

    /**
     * Called when the session should initiate the protocol handshake.
     * Typically sends Negotiate or similar message.
     */
    protected abstract void initiateHandshake();

    /**
     * Gets the protocol name for metric tagging.
     *
     * @return the protocol name (e.g., "iLink3", "Optiq", "Pillar")
     */
    protected abstract String getProtocolName();

    // =====================================================
    // Metrics
    // =====================================================

    /**
     * Binds Micrometer metrics to this session.
     * <p>
     * Pre-resolves all meters during initialization so that hot-path
     * instrumentation is zero-allocation (only {@code counter.increment()}
     * and {@code summary.record()} calls on the critical path).
     * <p>
     * Subclasses should override this method to add protocol-specific metrics,
     * calling {@code super.bindMetrics(registry)} first.
     *
     * @param registry the meter registry (may be null to disable metrics)
     */
    public void bindMetrics(MeterRegistry registry) {
        if (registry == null) {
            return;
        }

        Tags sessionTags = Tags.of(
                "session_id", sessionId,
                "protocol", getProtocolName(),
                "role", isInitiator ? "initiator" : "acceptor"
        );

        // Session state gauges
        Gauge.builder("omnibridge.session.state", state, s -> s.get().ordinal())
                .tags(sessionTags)
                .description("Session state ordinal")
                .register(registry);

        Gauge.builder("omnibridge.session.connected", state, s -> s.get().isConnected() ? 1.0 : 0.0)
                .tags(sessionTags)
                .description("TCP connected (0/1)")
                .register(registry);

        Gauge.builder("omnibridge.session.logged_on", state, s -> s.get().isEstablished() ? 1.0 : 0.0)
                .tags(sessionTags)
                .description("Session established (0/1)")
                .register(registry);

        // Sequence number gauges
        Gauge.builder("omnibridge.sequence.outgoing", outboundSeqNum, AtomicLong::doubleValue)
                .tags(sessionTags)
                .description("Current outbound sequence number")
                .register(registry);

        Gauge.builder("omnibridge.sequence.incoming_expected", inboundSeqNum, AtomicLong::doubleValue)
                .tags(sessionTags)
                .description("Current inbound sequence number")
                .register(registry);

        // Pre-resolved counters
        messagesReceivedCounter = Counter.builder("omnibridge.messages.received.total")
                .tags(sessionTags)
                .description("Messages received")
                .register(registry);

        messagesSentCounter = Counter.builder("omnibridge.messages.sent.total")
                .tags(sessionTags)
                .description("Messages sent")
                .register(registry);

        disconnectCounter = Counter.builder("omnibridge.session.disconnect.total")
                .tags(sessionTags)
                .description("Disconnect count")
                .register(registry);

        connectFailedCounter = Counter.builder("omnibridge.session.connect_failed.total")
                .tags(sessionTags)
                .description("Connection failure count")
                .register(registry);

        // Latency distribution
        processingTimeSummary = DistributionSummary.builder("omnibridge.message.processing.time")
                .tags(sessionTags)
                .description("Message processing time in nanoseconds")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99, 0.999)
                .register(registry);

        log.info("[{}] Metrics bound (protocol={})", sessionId, getProtocolName());
    }

    // =====================================================
    // Session Identity
    // =====================================================

    public String getSessionId() {
        return sessionId;
    }

    public String getSessionName() {
        return sessionName;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isInitiator() {
        return isInitiator;
    }

    public C getConfig() {
        return config;
    }

    // =====================================================
    // State Management
    // =====================================================

    public SbeSessionState getSessionState() {
        return state.get();
    }

    /**
     * Attempts to transition state atomically.
     *
     * @param expected the expected current state
     * @param newState the new state
     * @return true if transition succeeded
     */
    protected boolean setState(SbeSessionState expected, SbeSessionState newState) {
        if (state.compareAndSet(expected, newState)) {
            log.info("[{}] State change: {} -> {}", sessionId, expected, newState);
            notifyStateChange(expected, newState);
            return true;
        }
        return false;
    }

    /**
     * Forces a state transition regardless of current state.
     *
     * @param newState the new state
     */
    protected void forceState(SbeSessionState newState) {
        SbeSessionState old = state.getAndSet(newState);
        if (old != newState) {
            log.info("[{}] State change: {} -> {}", sessionId, old, newState);
            notifyStateChange(old, newState);
        }
    }

    // =====================================================
    // Connection Management
    // =====================================================

    public void setChannel(TcpChannel channel) {
        this.channel = channel;
    }

    public TcpChannel getChannel() {
        return channel;
    }

    @Override
    public void onConnected(TcpChannel channel) {
        this.channel = channel;
        SbeSessionState currentState = state.get();
        if (currentState == SbeSessionState.CONNECTING || currentState == SbeSessionState.CREATED) {
            forceState(SbeSessionState.CONNECTED);

            if (isInitiator) {
                initiateHandshake();
            }
        }
    }

    @Override
    public int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length) {
        // Copy to receive buffer for processing
        receiveBuffer.putBytes(receivePosition, buffer, offset, length);
        receivePosition += length;

        // Process complete messages
        int consumed = processReceivedData();
        return length; // We consumed all bytes from the network buffer
    }

    @Override
    public void onDisconnected(TcpChannel channel, Throwable reason) {
        SbeSessionState oldState = state.get();
        if (!oldState.isTerminal()) {
            if (reason != null) {
                log.warn("[{}] Disconnected: {}", sessionId, reason.getMessage());
            }
            if (disconnectCounter != null) {
                disconnectCounter.increment();
            }
            forceState(SbeSessionState.DISCONNECTED);
        }
    }

    @Override
    public void onConnectFailed(String remoteAddress, Throwable reason) {
        log.error("[{}] Connect failed to {}: {}", sessionId, remoteAddress,
                reason != null ? reason.getMessage() : "unknown");
        if (connectFailedCounter != null) {
            connectFailedCounter.increment();
        }
        forceState(SbeSessionState.DISCONNECTED);
    }

    @Override
    public void onAcceptFailed(Throwable reason) {
        log.error("[{}] Accept failed: {}", sessionId,
                reason != null ? reason.getMessage() : "unknown");
    }

    // =====================================================
    // TryClaim Pattern for Thread-Safe Message Sending
    // =====================================================

    /**
     * Try to claim a buffer region for writing a message.
     * <p>
     * This method is thread-safe and can be called from multiple threads.
     * If successful, returns a message instance wrapped over the claimed buffer region.
     * The caller must call {@link #commit(SbeMessage)} or {@link #abort(SbeMessage)}
     * after populating the message.
     *
     * @param messageClass the message class to create
     * @param <T> the message type
     * @return the message instance, or null if claim failed
     */
    @SuppressWarnings("unchecked")
    public <T extends SbeMessage> T tryClaim(Class<T> messageClass) {
        if (!state.get().canSendMessages()) {
            return null;
        }

        SbeMessageFactory factory = getMessageFactory();
        T message = factory.getMessage(messageClass);
        int messageLength = message.getBlockLength() + SbeMessage.HEADER_SIZE;

        if (!sendLock.tryLock()) {
            return null;
        }

        try {
            claimedOffset = 0;
            claimedLength = messageLength;

            message.wrapForWriting(sendBuffer, claimedOffset, claimedLength, -1);
            return message;
        } catch (Exception e) {
            sendLock.unlock();
            throw e;
        }
    }

    /**
     * Commit a claimed message and send it.
     *
     * @param message the message to commit
     * @return true if the message was sent successfully
     */
    public boolean commit(SbeMessage message) {
        try {
            int length = message.complete();
            boolean sent = sendMessage(sendBuffer, message.getOffset(), length);

            if (sent) {
                log.debug("[{}] Sent message: templateId={}", sessionId, message.getTemplateId());
                outboundSeqNum.incrementAndGet();
                if (messagesSentCounter != null) {
                    messagesSentCounter.increment();
                }
            }

            return sent;
        } finally {
            message.reset();
            sendLock.unlock();
        }
    }

    /**
     * Abort a claimed message without sending.
     *
     * @param message the message to abort
     */
    public void abort(SbeMessage message) {
        try {
            log.debug("[{}] Aborted message: templateId={}", sessionId, message.getTemplateId());
        } finally {
            message.reset();
            sendLock.unlock();
        }
    }

    // =====================================================
    // Message Processing
    // =====================================================

    /**
     * Processes received data and extracts complete messages.
     *
     * @return the number of bytes processed
     */
    protected int processReceivedData() {
        int processed = 0;

        while (processed < receivePosition) {
            int available = receivePosition - processed;

            if (available < SbeMessage.HEADER_SIZE) {
                break;
            }

            int expectedLength = getExpectedMessageLength(receiveBuffer, processed, available);

            if (expectedLength < 0 || available < expectedLength) {
                break;
            }

            // Read and process the message
            SbeMessage msg = getMessageFactory().readMessage(receiveBuffer, processed, expectedLength);
            if (msg != null) {
                long processingStart = processingTimeSummary != null ? System.nanoTime() : 0;

                inboundSeqNum.incrementAndGet();
                if (messagesReceivedCounter != null) {
                    messagesReceivedCounter.increment();
                }

                processIncomingMessage(msg);

                if (processingTimeSummary != null) {
                    processingTimeSummary.record(System.nanoTime() - processingStart);
                }

                // Log to persistence
                if (logStore != null && config.isPersistMessages()) {
                    logMessage(receiveBuffer, processed, expectedLength, LogEntry.Direction.INBOUND);
                }
            }

            processed += expectedLength;
        }

        // Compact buffer
        if (processed > 0) {
            if (processed < receivePosition) {
                receiveBuffer.putBytes(0, receiveBuffer, processed, receivePosition - processed);
            }
            receivePosition -= processed;
        }

        return processed;
    }

    /**
     * Sends a message from the buffer.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message
     * @param length the length of the message
     * @return true if sent successfully
     */
    protected boolean sendMessage(DirectBuffer buffer, int offset, int length) {
        if (channel == null || !channel.isConnected()) {
            return false;
        }

        // Log to persistence
        if (logStore != null && config.isPersistMessages()) {
            logMessage(buffer, offset, length, LogEntry.Direction.OUTBOUND);
        }

        // Copy to byte array for writeRaw
        byte[] data = new byte[length];
        buffer.getBytes(offset, data, 0, length);
        return channel.writeRaw(data, 0, length) > 0;
    }

    /**
     * Logs a message to the persistence store.
     */
    protected void logMessage(DirectBuffer buffer, int offset, int length, LogEntry.Direction direction) {
        try {
            byte[] msgBytes = new byte[length];
            buffer.getBytes(offset, msgBytes, 0, length);

            long seqNum = direction == LogEntry.Direction.OUTBOUND
                    ? outboundSeqNum.get()
                    : inboundSeqNum.get();

            LogEntry entry = LogEntry.create(
                    System.currentTimeMillis(),
                    direction,
                    (int) seqNum,
                    streamName,
                    null,
                    msgBytes
            );
            logStore.write(entry);
        } catch (Exception e) {
            log.warn("[{}] Failed to log message: {}", sessionId, e.getMessage());
        }
    }

    // =====================================================
    // Sequence Number Management
    // =====================================================

    public long getOutboundSeqNum() {
        return outboundSeqNum.get();
    }

    public long getInboundSeqNum() {
        return inboundSeqNum.get();
    }

    public void setOutboundSeqNum(long seqNum) {
        outboundSeqNum.set(seqNum);
    }

    public void setInboundSeqNum(long seqNum) {
        inboundSeqNum.set(seqNum);
    }

    // =====================================================
    // Listener Management
    // =====================================================

    public void addStateListener(SessionStateListener listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(SessionStateListener listener) {
        stateListeners.remove(listener);
    }

    public void addMessageListener(MessageListener listener) {
        messageListeners.add(listener);
    }

    public void removeMessageListener(MessageListener listener) {
        messageListeners.remove(listener);
    }

    protected void notifyStateChange(SbeSessionState oldState, SbeSessionState newState) {
        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onStateChange(this, oldState, newState);
            } catch (Exception e) {
                log.error("[{}] Error in state listener: {}", sessionId, e.getMessage());
            }
        }
    }

    protected void notifyMessage(SbeMessage msg) {
        for (MessageListener listener : messageListeners) {
            try {
                listener.onMessage(this, msg);
            } catch (Exception e) {
                log.error("[{}] Error in message listener: {}", sessionId, e.getMessage());
            }
        }
    }

    // =====================================================
    // Lifecycle
    // =====================================================

    /**
     * Initiates connection to the configured host/port.
     * For acceptors, this is a no-op.
     */
    public void connect() {
        if (!isInitiator) {
            log.debug("[{}] Acceptor session, not initiating connection", sessionId);
            return;
        }
        forceState(SbeSessionState.CONNECTING);
    }

    /**
     * Disconnects the session.
     */
    public void disconnect() {
        if (channel != null && channel.isConnected()) {
            channel.close();
        }
        forceState(SbeSessionState.DISCONNECTED);
    }

    /**
     * Stops the session permanently.
     */
    public void stop() {
        disconnect();
        forceState(SbeSessionState.STOPPED);
    }

    /**
     * Resets the session state for a new connection.
     */
    public void reset() {
        receivePosition = 0;
        outboundSeqNum.set(1);
        inboundSeqNum.set(1);
    }

    // =====================================================
    // Listener Interfaces
    // =====================================================

    /**
     * Listener interface for session state changes.
     */
    public interface SessionStateListener {
        void onStateChange(SbeSession<?> session, SbeSessionState oldState, SbeSessionState newState);
    }

    /**
     * Listener interface for incoming messages.
     */
    public interface MessageListener {
        void onMessage(SbeSession<?> session, SbeMessage message);
    }
}
