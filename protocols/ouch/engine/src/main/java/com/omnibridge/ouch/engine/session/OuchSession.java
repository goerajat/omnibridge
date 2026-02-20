package com.omnibridge.ouch.engine.session;

import com.omnibridge.network.NetworkHandler;
import com.omnibridge.network.TcpChannel;
import com.omnibridge.ouch.message.*;
import com.omnibridge.ouch.message.factory.OuchMessageFactory;
import com.omnibridge.ouch.message.v42.V42MessageFactory;
import com.omnibridge.ouch.message.v50.V50MessageFactory;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import io.micrometer.core.instrument.*;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OUCH protocol session implementation.
 *
 * <p>Manages an OUCH session over TCP (typically using SoupBinTCP framing).
 * Handles order submission, execution reports, and session lifecycle.</p>
 *
 * <p>Thread Safety:</p>
 * <ul>
 *   <li>Message sending uses atomic operations for thread-safe order submission</li>
 *   <li>State transitions are atomic</li>
 *   <li>Listener notifications are thread-safe via CopyOnWriteArrayList</li>
 *   <li>tryClaim/commit/abort pattern provides thread-safe multi-threaded sending</li>
 * </ul>
 *
 * <p>Usage with tryClaim pattern:</p>
 * <pre>{@code
 * EnterOrderMessage msg = session.tryClaim(EnterOrderMessage.class);
 * if (msg != null) {
 *     msg.setOrderToken("ORDER001")
 *        .setSide(Side.BUY)
 *        .setShares(100)
 *        .setSymbol("AAPL")
 *        .setPrice(150.0);
 *     session.commit(msg);
 * }
 * }</pre>
 */
public class OuchSession implements NetworkHandler {

    private static final Logger log = LoggerFactory.getLogger(OuchSession.class);

    // Session identity
    private final String sessionId;
    private final String username;
    private final String password;
    private final String sessionName;

    // Protocol version
    private final OuchVersion protocolVersion;
    private final OuchMessageFactory messageFactory;

    // Network
    private TcpChannel channel;
    private final String host;
    private final int port;
    private final boolean isInitiator;

    // State
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CREATED);
    private final AtomicLong sequenceNumber = new AtomicLong(1);
    private final AtomicInteger orderTokenCounter = new AtomicInteger(1);
    private final AtomicLong userRefNumCounter = new AtomicLong(1); // For V50

    // Buffers for zero-copy message handling
    private final MutableDirectBuffer sendBuffer;
    private final MutableDirectBuffer receiveBuffer;
    private int receivePosition = 0;

    // Thread-safe send buffer management for tryClaim pattern
    private final Lock sendLock = new ReentrantLock();
    private final AtomicInteger claimCounter = new AtomicInteger(0);
    private volatile int claimedOffset = 0;
    private volatile int claimedLength = 0;

    // Message pool for thread-local message reuse
    private final OuchMessagePool messagePool = new OuchMessagePool();

    // Persistence
    private final LogStore logStore;
    private final String streamName;

    // Listeners
    private final List<SessionStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<MessageListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<OuchMessageListener<OuchSession>> typedMessageListeners = new CopyOnWriteArrayList<>();

    // Configuration
    private static final int SEND_BUFFER_SIZE = 65536;
    private static final int RECEIVE_BUFFER_SIZE = 65536;

    // Metrics (pre-resolved for hot path)
    private Counter messagesReceivedCounter;
    private Counter messagesSentCounter;
    private Counter disconnectCounter;
    private Counter connectFailedCounter;
    private Counter ordersReceivedCounter;
    private Counter ordersSentCounter;
    private DistributionSummary processingTimeSummary;

    /**
     * Create a new OUCH session with default V42 protocol version.
     */
    public OuchSession(String sessionId, String username, String password,
                       String host, int port, boolean isInitiator,
                       LogStore logStore) {
        this(sessionId, username, password, host, port, isInitiator, logStore, OuchVersion.V42);
    }

    /**
     * Create a new OUCH session with specified protocol version.
     */
    public OuchSession(String sessionId, String username, String password,
                       String host, int port, boolean isInitiator,
                       LogStore logStore, OuchVersion protocolVersion) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
        this.sessionName = sessionId;
        this.host = host;
        this.port = port;
        this.isInitiator = isInitiator;
        this.logStore = logStore;
        this.streamName = sessionId;
        this.protocolVersion = protocolVersion != null ? protocolVersion : OuchVersion.V42;
        this.messageFactory = createMessageFactory(this.protocolVersion);

        this.sendBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(SEND_BUFFER_SIZE));
        this.receiveBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(RECEIVE_BUFFER_SIZE));
    }

    private OuchMessageFactory createMessageFactory(OuchVersion version) {
        return switch (version) {
            case V42 -> new V42MessageFactory();
            case V50 -> new V50MessageFactory();
        };
    }

    // =====================================================
    // Metrics Binding
    // =====================================================

    /**
     * Bind Micrometer metrics for this session.
     *
     * @param registry the meter registry (null to disable metrics)
     */
    public void bindMetrics(MeterRegistry registry) {
        if (registry == null) {
            return;
        }

        Tags sessionTags = Tags.of(
                "session_id", sessionId,
                "protocol", "OUCH",
                "role", isInitiator ? "initiator" : "acceptor"
        );

        Gauge.builder("omnibridge.session.state", state, s -> s.get().ordinal())
                .tags(sessionTags).description("Session state ordinal").register(registry);

        Gauge.builder("omnibridge.session.logged_on", state,
                        s -> s.get() == SessionState.LOGGED_IN ? 1.0 : 0.0)
                .tags(sessionTags).description("Fully authenticated (0/1)").register(registry);

        Gauge.builder("omnibridge.session.connected", state,
                        s -> s.get().canSendOrders() ? 1.0 : 0.0)
                .tags(sessionTags).description("TCP connected (0/1)").register(registry);

        messagesReceivedCounter = Counter.builder("omnibridge.messages.received.total")
                .tags(sessionTags).description("Messages received").register(registry);
        messagesSentCounter = Counter.builder("omnibridge.messages.sent.total")
                .tags(sessionTags).description("Messages sent").register(registry);
        disconnectCounter = Counter.builder("omnibridge.session.disconnect.total")
                .tags(sessionTags).description("Disconnect count").register(registry);
        connectFailedCounter = Counter.builder("omnibridge.session.connect_failed.total")
                .tags(sessionTags).description("Connection failures").register(registry);
        ordersReceivedCounter = Counter.builder("omnibridge.orders.new.total")
                .tags(sessionTags).description("Orders received").register(registry);
        ordersSentCounter = Counter.builder("omnibridge.orders.new.total")
                .tags(sessionTags).tag("direction", "sent").description("Orders sent").register(registry);

        processingTimeSummary = DistributionSummary.builder("omnibridge.message.processing.time")
                .tags(sessionTags)
                .description("Message processing time in nanoseconds")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99, 0.999)
                .register(registry);

        log.info("[{}] Metrics bound", sessionId);
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

    /**
     * Get the OUCH protocol version for this session.
     */
    public OuchVersion getProtocolVersion() {
        return protocolVersion;
    }

    /**
     * Get the message factory for this session's protocol version.
     */
    public OuchMessageFactory getMessageFactory() {
        return messageFactory;
    }

    /**
     * Check if this session uses OUCH 5.0 protocol.
     */
    public boolean isV50() {
        return protocolVersion == OuchVersion.V50;
    }

    /**
     * Check if this session uses OUCH 4.2 protocol.
     */
    public boolean isV42() {
        return protocolVersion == OuchVersion.V42;
    }

    // =====================================================
    // State Management
    // =====================================================

    public SessionState getState() {
        return state.get();
    }

    private boolean setState(SessionState expected, SessionState newState) {
        if (state.compareAndSet(expected, newState)) {
            log.info("[{}] State change: {} -> {}", sessionId, expected, newState);
            notifyStateChange(expected, newState);
            return true;
        }
        return false;
    }

    private void forceState(SessionState newState) {
        SessionState old = state.getAndSet(newState);
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
        SessionState currentState = state.get();
        if (currentState == SessionState.CONNECTING || currentState == SessionState.CREATED) {
            forceState(SessionState.CONNECTED);

            if (isInitiator) {
                sendLogin();
            }
        }
    }

    @Override
    public int onDataReceived(TcpChannel channel, DirectBuffer buffer, int offset, int length) {
        // For acceptors, auto-transition to LOGGED_IN when first data is received
        // This is a simplification - a full implementation would parse the SoupBinTCP login
        if (!isInitiator && state.get() == SessionState.CONNECTED) {
            log.info("[{}] Acceptor received data, auto-transitioning to LOGGED_IN", sessionId);
            forceState(SessionState.LOGGED_IN);
        }

        // Copy to receive buffer for processing
        receiveBuffer.putBytes(receivePosition, buffer, offset, length);
        receivePosition += length;

        // Process complete messages
        int consumed = processReceivedData();
        return length; // We consumed all bytes from the network buffer
    }

    @Override
    public void onDisconnected(TcpChannel channel, Throwable reason) {
        SessionState oldState = state.get();
        if (!oldState.isTerminal()) {
            if (reason != null) {
                log.warn("[{}] Disconnected: {}", sessionId, reason.getMessage());
            }
            if (disconnectCounter != null) {
                disconnectCounter.increment();
            }
            forceState(SessionState.DISCONNECTED);
        }
    }

    @Override
    public void onConnectFailed(String remoteAddress, Throwable reason) {
        log.error("[{}] Connect failed to {}: {}", sessionId, remoteAddress,
                reason != null ? reason.getMessage() : "unknown");
        if (connectFailedCounter != null) {
            connectFailedCounter.increment();
        }
        forceState(SessionState.DISCONNECTED);
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
     *
     * <p>This method is thread-safe and can be called from multiple threads.
     * If successful, returns a message instance wrapped over the claimed buffer region.
     * The caller must call {@link #commit(OuchMessage)} or {@link #abort(OuchMessage)}
     * after populating the message.</p>
     *
     * @param messageClass the message class to create
     * @param <T> the message type
     * @return the message instance, or null if claim failed (session not ready or buffer full)
     */
    @SuppressWarnings("unchecked")
    public <T extends OuchMessage> T tryClaim(Class<T> messageClass) {
        if (!state.get().canSendOrders()) {
            return null;
        }

        // Get a message instance from the factory (version-specific)
        T message = messageFactory.getMessage(messageClass);
        int messageLength = message.getMessageLength();

        // Try to claim buffer space
        if (!sendLock.tryLock()) {
            return null;
        }

        try {
            int claimIndex = claimCounter.incrementAndGet();
            claimedOffset = 0;
            claimedLength = messageLength;

            // Wrap the message for writing
            message.wrapForWriting(sendBuffer, claimedOffset, messageLength, claimIndex);
            return message;
        } finally {
            // Note: lock is NOT released here - it's released in commit() or abort()
            // This ensures only one sender at a time for the simple implementation
        }
    }

    /**
     * Commit a claimed message and send it.
     *
     * @param message the message to commit (must have been returned by tryClaim)
     * @return true if the message was sent successfully
     */
    public boolean commit(OuchMessage message) {
        if (!message.isClaimed()) {
            log.warn("[{}] Cannot commit unclaimed message", sessionId);
            return false;
        }

        try {
            int length = message.complete();
            boolean sent = sendMessage(sendBuffer, message.offset(), length);

            if (sent) {
                log.debug("[{}] Committed message: {}", sessionId, message.getMessageType());
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
     * @param message the message to abort (must have been returned by tryClaim)
     */
    public void abort(OuchMessage message) {
        if (!message.isClaimed()) {
            log.warn("[{}] Cannot abort unclaimed message", sessionId);
            return;
        }

        try {
            log.debug("[{}] Aborted message: {}", sessionId, message.getMessageType());
        } finally {
            message.reset();
            sendLock.unlock();
        }
    }

    // =====================================================
    // Message Processing
    // =====================================================

    private int processReceivedData() {
        int processed = 0;

        while (processed < receivePosition) {
            int available = receivePosition - processed;

            // Peek at message type to determine expected length
            if (available < 1) {
                break;
            }

            OuchMessageType type = messageFactory.peekType(receiveBuffer, processed);
            int expectedLength = messageFactory.getExpectedLength(type);

            if (expectedLength < 0 || available < expectedLength) {
                // Not enough data for complete message
                break;
            }

            // Parse and process the message
            OuchMessage msg = readMessage(type, receiveBuffer, processed, expectedLength);
            if (msg != null) {
                processIncomingMessage(msg);

                // Log to persistence
                if (logStore != null) {
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

    private OuchMessage readMessage(OuchMessageType type, DirectBuffer buffer, int offset, int length) {
        return messageFactory.readMessage(buffer, offset, length);
    }

    private void processIncomingMessage(OuchMessage msg) {
        if (messagesReceivedCounter != null) {
            messagesReceivedCounter.increment();
        }

        log.debug("[{}] Received: {}", sessionId, msg.getMessageType());

        // Check if V50 message for version-specific dispatch
        boolean isV50Message = msg.getVersion() == OuchVersion.V50;

        // Dispatch to typed listeners
        switch (msg.getMessageType()) {
            case ORDER_ACCEPTED -> {
                if (isV50Message) {
                    var m = (com.omnibridge.ouch.message.v50.V50OrderAcceptedMessage) msg;
                    handleOrderAcceptedV50(m);
                    notifyTypedListeners(listener -> listener.onOrderAcceptedV50(this, m));
                } else {
                    OrderAcceptedMessage m = (OrderAcceptedMessage) msg;
                    handleOrderAccepted(m);
                    notifyTypedListeners(listener -> listener.onOrderAccepted(this, m));
                }
            }
            case ORDER_EXECUTED -> {
                if (isV50Message) {
                    var m = (com.omnibridge.ouch.message.v50.V50OrderExecutedMessage) msg;
                    handleOrderExecutedV50(m);
                    notifyTypedListeners(listener -> listener.onOrderExecutedV50(this, m));
                } else {
                    OrderExecutedMessage m = (OrderExecutedMessage) msg;
                    handleOrderExecuted(m);
                    notifyTypedListeners(listener -> listener.onOrderExecuted(this, m));
                }
            }
            case ORDER_CANCELED -> {
                if (isV50Message) {
                    var m = (com.omnibridge.ouch.message.v50.V50OrderCanceledMessage) msg;
                    notifyTypedListeners(listener -> listener.onOrderCanceledV50(this, m));
                } else {
                    OrderCanceledMessage m = (OrderCanceledMessage) msg;
                    handleOrderCanceled(m);
                    notifyTypedListeners(listener -> listener.onOrderCanceled(this, m));
                }
            }
            case ORDER_REJECTED -> {
                if (isV50Message) {
                    var m = (com.omnibridge.ouch.message.v50.V50OrderRejectedMessage) msg;
                    notifyTypedListeners(listener -> listener.onOrderRejectedV50(this, m));
                } else {
                    OrderRejectedMessage m = (OrderRejectedMessage) msg;
                    handleOrderRejected(m);
                    notifyTypedListeners(listener -> listener.onOrderRejected(this, m));
                }
            }
            case ORDER_REPLACED -> {
                if (isV50Message) {
                    var m = (com.omnibridge.ouch.message.v50.V50OrderReplacedMessage) msg;
                    notifyTypedListeners(listener -> listener.onOrderReplacedV50(this, m));
                } else {
                    OrderReplacedMessage m = (OrderReplacedMessage) msg;
                    handleOrderReplaced(m);
                    notifyTypedListeners(listener -> listener.onOrderReplaced(this, m));
                }
            }
            case SYSTEM_EVENT -> {
                if (isV50Message) {
                    var m = (com.omnibridge.ouch.message.v50.V50SystemEventMessage) msg;
                    notifyTypedListeners(listener -> listener.onSystemEventV50(this, m));
                } else {
                    SystemEventMessage m = (SystemEventMessage) msg;
                    handleSystemEvent(m);
                    notifyTypedListeners(listener -> listener.onSystemEvent(this, m));
                }
            }
            case ENTER_ORDER -> {
                if (isV50Message) {
                    var m = (com.omnibridge.ouch.message.v50.V50EnterOrderMessage) msg;
                    notifyTypedListeners(listener -> listener.onEnterOrderV50(this, m));
                } else {
                    EnterOrderMessage m = (EnterOrderMessage) msg;
                    notifyTypedListeners(listener -> listener.onEnterOrder(this, m));
                }
            }
            case CANCEL_ORDER -> {
                if (isV50Message) {
                    var m = (com.omnibridge.ouch.message.v50.V50CancelOrderMessage) msg;
                    notifyTypedListeners(listener -> listener.onCancelOrderV50(this, m));
                } else {
                    CancelOrderMessage m = (CancelOrderMessage) msg;
                    notifyTypedListeners(listener -> listener.onCancelOrder(this, m));
                }
            }
            case REPLACE_ORDER -> {
                if (isV50Message) {
                    var m = (com.omnibridge.ouch.message.v50.V50ReplaceOrderMessage) msg;
                    notifyTypedListeners(listener -> listener.onReplaceOrderV50(this, m));
                } else {
                    ReplaceOrderMessage m = (ReplaceOrderMessage) msg;
                    notifyTypedListeners(listener -> listener.onReplaceOrder(this, m));
                }
            }
            default -> {
                log.warn("[{}] Unhandled message type: {}", sessionId, msg.getMessageType());
                notifyTypedListeners(listener -> listener.onUnknownMessage(this, msg.getMessageType(),
                        msg.buffer(), msg.offset(), msg.length()));
            }
        }

        // Notify generic onMessage callback
        notifyTypedListeners(listener -> listener.onMessage(this, msg));
    }

    private void handleOrderAccepted(OrderAcceptedMessage msg) {
        log.info("[{}] Order accepted: token={}, symbol={}, side={}, qty={}, price={}",
                sessionId, msg.getOrderToken(), msg.getSymbol(), msg.getSideCode(),
                msg.getShares(), msg.getPriceAsDouble());
        notifyMessage(msg);
    }

    private void handleOrderExecuted(OrderExecutedMessage msg) {
        log.info("[{}] Order executed: token={}, qty={}, price={}, match={}",
                sessionId, msg.getOrderToken(), msg.getExecutedShares(),
                msg.getExecutionPriceAsDouble(), msg.getMatchNumber());
        notifyMessage(msg);
    }

    private void handleOrderCanceled(OrderCanceledMessage msg) {
        log.info("[{}] Order canceled: token={}, decrement={}, reason={}",
                sessionId, msg.getOrderToken(), msg.getDecrementShares(), msg.getReason());
        notifyMessage(msg);
    }

    private void handleOrderRejected(OrderRejectedMessage msg) {
        log.warn("[{}] Order rejected: token={}, reason={}",
                sessionId, msg.getOrderToken(), msg.getRejectReasonDescription());
        notifyMessage(msg);
    }

    private void handleOrderReplaced(OrderReplacedMessage msg) {
        log.info("[{}] Order replaced: newToken={}, prevToken={}, qty={}, price={}",
                sessionId, msg.getReplacementOrderToken(), msg.getPreviousOrderToken(),
                msg.getShares(), msg.getPriceAsDouble());
        notifyMessage(msg);
    }

    private void handleSystemEvent(SystemEventMessage msg) {
        log.info("[{}] System event: {}", sessionId, msg.getEventDescription());
        notifyMessage(msg);
    }

    // V50-specific handlers
    private void handleOrderAcceptedV50(com.omnibridge.ouch.message.v50.V50OrderAcceptedMessage msg) {
        log.info("[{}] Order accepted (V50): userRef={}, symbol={}, side={}, qty={}, price={}",
                sessionId, msg.getUserRefNum(), msg.getSymbol(), msg.getSideCode(),
                msg.getQuantity(), msg.getPriceAsDouble());
        notifyMessage(msg);
    }

    private void handleOrderExecutedV50(com.omnibridge.ouch.message.v50.V50OrderExecutedMessage msg) {
        log.info("[{}] Order executed (V50): userRef={}, qty={}, price={}, match={}",
                sessionId, msg.getUserRefNum(), msg.getExecutedQuantity(),
                msg.getExecutionPriceAsDouble(), msg.getMatchNumber());
        notifyMessage(msg);
    }

    // =====================================================
    // Message Sending
    // =====================================================

    /**
     * Send a login request (SoupBinTCP).
     */
    private void sendLogin() {
        setState(SessionState.CONNECTED, SessionState.LOGIN_SENT);
        // In production, this would send a SoupBinTCP Login Request
        // For now, assume login succeeds immediately
        forceState(SessionState.LOGGED_IN);
    }

    /**
     * Send an Enter Order message.
     *
     * @return the order token (V4.2) or UserRefNum as string (V5.0), or null if send failed
     */
    public String sendEnterOrder(Side side, String symbol, int shares, double price) {
        if (isV50()) {
            return sendEnterOrderV50(side, symbol, shares, price);
        } else {
            return sendEnterOrderV42(side, symbol, shares, price);
        }
    }

    private String sendEnterOrderV42(Side side, String symbol, int shares, double price) {
        EnterOrderMessage msg = tryClaim(EnterOrderMessage.class);
        if (msg == null) {
            log.warn("[{}] Cannot send order - session not logged in or claim failed", sessionId);
            return null;
        }

        String token = generateOrderToken();
        msg.setOrderToken(token)
           .setSide(side)
           .setSymbol(symbol)
           .setShares(shares)
           .setPrice(price)
           .setDefaults()
           .setTimeInForce(99999); // Day order

        if (commit(msg)) {
            return token;
        }
        return null;
    }

    private String sendEnterOrderV50(Side side, String symbol, int shares, double price) {
        com.omnibridge.ouch.message.v50.V50EnterOrderMessage msg =
            tryClaim(com.omnibridge.ouch.message.v50.V50EnterOrderMessage.class);
        if (msg == null) {
            log.warn("[{}] Cannot send order - session not logged in or claim failed", sessionId);
            return null;
        }

        long userRefNum = generateUserRefNum();
        msg.setUserRefNum(userRefNum)
           .setSide(side)
           .setSymbol(symbol)
           .setQuantity(shares)
           .setPrice(price)
           .setDefaults()
           .setTimeInForce(99999); // Day order

        if (commit(msg)) {
            return String.valueOf(userRefNum);
        }
        return null;
    }

    /**
     * Send a Cancel Order message.
     *
     * @return true if sent successfully
     */
    public boolean sendCancelOrder(String orderToken) {
        CancelOrderMessage msg = tryClaim(CancelOrderMessage.class);
        if (msg == null) {
            log.warn("[{}] Cannot send cancel - session not logged in or claim failed", sessionId);
            return false;
        }

        msg.setOrderToken(orderToken).cancelAll();
        return commit(msg);
    }

    /**
     * Send a Replace Order message.
     *
     * @return the new order token, or null if send failed
     */
    public String sendReplaceOrder(String existingToken, int newShares, double newPrice) {
        ReplaceOrderMessage msg = tryClaim(ReplaceOrderMessage.class);
        if (msg == null) {
            log.warn("[{}] Cannot send replace - session not logged in or claim failed", sessionId);
            return null;
        }

        String newToken = generateOrderToken();
        msg.setExistingOrderToken(existingToken)
           .setReplacementOrderToken(newToken)
           .setShares(newShares)
           .setPrice(newPrice)
           .setDisplay(EnterOrderMessage.DISPLAY_VISIBLE)
           .setIntermarketSweepEligibility(EnterOrderMessage.ISO_NOT_ELIGIBLE)
           .setMinimumQuantity(0);

        if (commit(msg)) {
            return newToken;
        }
        return null;
    }

    // =====================================================
    // Response Messages (for acceptors)
    // =====================================================

    /**
     * Send an Order Accepted message (for acceptors).
     *
     * @return true if sent successfully
     */
    public boolean sendOrderAccepted(String orderToken, Side side, String symbol, int shares, double price, long orderRefNum) {
        if (isV50()) {
            return sendOrderAcceptedV50(orderToken, side, symbol, shares, price, orderRefNum);
        } else {
            return sendOrderAcceptedV42(orderToken, side, symbol, shares, price, orderRefNum);
        }
    }

    private boolean sendOrderAcceptedV42(String orderToken, Side side, String symbol, int shares, double price, long orderRefNum) {
        OrderAcceptedMessage msg = tryClaim(OrderAcceptedMessage.class);
        if (msg == null) {
            log.warn("[{}] Cannot send order accepted - claim failed", sessionId);
            return false;
        }

        msg.setOrderToken(orderToken)
           .setSide(side)
           .setSymbol(symbol)
           .setShares(shares)
           .setPrice(price)
           .setOrderReferenceNumber(orderRefNum)
           .setOrderState('L');  // Live

        return commit(msg);
    }

    private boolean sendOrderAcceptedV50(String orderToken, Side side, String symbol, int shares, double price, long orderRefNum) {
        com.omnibridge.ouch.message.v50.V50OrderAcceptedMessage msg =
            tryClaim(com.omnibridge.ouch.message.v50.V50OrderAcceptedMessage.class);
        if (msg == null) {
            log.warn("[{}] Cannot send order accepted - claim failed", sessionId);
            return false;
        }

        // Parse orderToken as UserRefNum (it's passed as a string for API consistency)
        long userRefNum = 0;
        try {
            userRefNum = Long.parseLong(orderToken.trim());
        } catch (NumberFormatException e) {
            log.warn("[{}] Invalid UserRefNum in V50 order token: {}", sessionId, orderToken);
        }

        msg.setTimestamp(System.nanoTime())
           .setUserRefNum(userRefNum)
           .setSide(side)
           .setSymbol(symbol)
           .setQuantity(shares)
           .setPrice(price)
           .setOrderReferenceNumber(orderRefNum)
           .setOrderState('L');  // Live

        return commit(msg);
    }

    /**
     * Send an Order Executed message (for acceptors).
     *
     * @return true if sent successfully
     */
    public boolean sendOrderExecuted(String orderToken, int executedShares, double executionPrice, long matchNumber) {
        if (isV50()) {
            return sendOrderExecutedV50(orderToken, executedShares, executionPrice, matchNumber);
        } else {
            return sendOrderExecutedV42(orderToken, executedShares, executionPrice, matchNumber);
        }
    }

    private boolean sendOrderExecutedV42(String orderToken, int executedShares, double executionPrice, long matchNumber) {
        OrderExecutedMessage msg = tryClaim(OrderExecutedMessage.class);
        if (msg == null) {
            log.warn("[{}] Cannot send order executed - claim failed", sessionId);
            return false;
        }

        msg.setOrderToken(orderToken)
           .setExecutedShares(executedShares)
           .setExecutionPrice(executionPrice)
           .setMatchNumber(matchNumber)
           .setLiquidityFlag('A');  // Added

        return commit(msg);
    }

    private boolean sendOrderExecutedV50(String orderToken, int executedShares, double executionPrice, long matchNumber) {
        com.omnibridge.ouch.message.v50.V50OrderExecutedMessage msg =
            tryClaim(com.omnibridge.ouch.message.v50.V50OrderExecutedMessage.class);
        if (msg == null) {
            log.warn("[{}] Cannot send order executed - claim failed", sessionId);
            return false;
        }

        // Parse orderToken as UserRefNum
        long userRefNum = 0;
        try {
            userRefNum = Long.parseLong(orderToken.trim());
        } catch (NumberFormatException e) {
            log.warn("[{}] Invalid UserRefNum in V50 order token: {}", sessionId, orderToken);
        }

        msg.setTimestamp(System.nanoTime())
           .setUserRefNum(userRefNum)
           .setExecutedQuantity(executedShares)
           .setExecutionPrice(executionPrice)
           .setMatchNumber(matchNumber)
           .setLiquidityFlag('A');  // Added

        return commit(msg);
    }

    private boolean sendMessage(DirectBuffer buffer, int offset, int length) {
        if (channel == null || !channel.isConnected()) {
            return false;
        }

        if (messagesSentCounter != null) {
            messagesSentCounter.increment();
        }

        // Log to persistence
        if (logStore != null) {
            logMessage(buffer, offset, length, LogEntry.Direction.OUTBOUND);
        }

        // Copy to byte array for writeRaw
        byte[] data = new byte[length];
        buffer.getBytes(offset, data, 0, length);
        return channel.writeRaw(data, 0, length) > 0;
    }

    private void logMessage(DirectBuffer buffer, int offset, int length, LogEntry.Direction direction) {
        try {
            byte[] msgBytes = new byte[length];
            buffer.getBytes(offset, msgBytes, 0, length);

            long seqNum = sequenceNumber.getAndIncrement();
            LogEntry entry = LogEntry.create(
                    System.currentTimeMillis(),
                    direction,
                    (int) seqNum,
                    streamName,
                    null, // No metadata
                    msgBytes
            );
            logStore.write(entry);
        } catch (Exception e) {
            log.warn("[{}] Failed to log message: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Generate a unique order token (for V42).
     */
    public String generateOrderToken() {
        int num = orderTokenCounter.getAndIncrement();
        // Format: SSSS-NNNNNNNNN (session prefix + sequence)
        String prefix = sessionId.length() > 4 ? sessionId.substring(0, 4) : sessionId;
        return String.format("%-4s%010d", prefix, num).substring(0, 14);
    }

    /**
     * Generate a unique UserRefNum (for V50).
     * Returns a 4-byte unsigned integer value.
     */
    public long generateUserRefNum() {
        return userRefNumCounter.getAndIncrement() & 0xFFFFFFFFL;
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

    /**
     * Add a typed message listener for type-safe callbacks.
     */
    public void addMessageListener(OuchMessageListener<OuchSession> listener) {
        typedMessageListeners.add(listener);
    }

    /**
     * Remove a typed message listener.
     */
    public void removeMessageListener(OuchMessageListener<OuchSession> listener) {
        typedMessageListeners.remove(listener);
    }

    private void notifyStateChange(SessionState oldState, SessionState newState) {
        for (SessionStateListener listener : stateListeners) {
            try {
                listener.onStateChange(this, oldState, newState);
            } catch (Exception e) {
                log.error("[{}] Error in state listener: {}", sessionId, e.getMessage());
            }
        }
    }

    private void notifyMessage(OuchMessage msg) {
        for (MessageListener listener : messageListeners) {
            try {
                listener.onMessage(this, msg);
            } catch (Exception e) {
                log.error("[{}] Error in message listener: {}", sessionId, e.getMessage());
            }
        }
    }

    private void notifyTypedListeners(java.util.function.Consumer<OuchMessageListener<OuchSession>> callback) {
        for (OuchMessageListener<OuchSession> listener : typedMessageListeners) {
            try {
                callback.accept(listener);
            } catch (Exception e) {
                log.error("[{}] Error in typed message listener: {}", sessionId, e.getMessage());
            }
        }
    }

    // =====================================================
    // Lifecycle
    // =====================================================

    public void disconnect() {
        if (channel != null && channel.isConnected()) {
            channel.close();
        }
        forceState(SessionState.DISCONNECTED);
    }

    public void stop() {
        disconnect();
        forceState(SessionState.STOPPED);
    }

    /**
     * Listener interface for session state changes.
     */
    public interface SessionStateListener {
        void onStateChange(OuchSession session, SessionState oldState, SessionState newState);
    }

    /**
     * Listener interface for incoming messages.
     * @deprecated Use {@link OuchMessageListener} for type-safe callbacks
     */
    @Deprecated
    public interface MessageListener {
        void onMessage(OuchSession session, OuchMessage message);
    }
}
