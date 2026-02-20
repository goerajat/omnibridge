package com.omnibridge.pillar.engine;

import com.omnibridge.pillar.engine.config.PillarSessionConfig;
import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageFactory;
import com.omnibridge.pillar.message.PillarMessageType;
import com.omnibridge.pillar.message.session.*;
import com.omnibridge.pillar.message.order.*;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.sbe.engine.session.SbeSession;
import com.omnibridge.sbe.engine.session.SbeSessionState;
import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.sbe.message.SbeMessageFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NYSE Pillar session implementation.
 * <p>
 * Implements the Pillar stream protocol session lifecycle:
 * <ol>
 *   <li>Connect to gateway</li>
 *   <li>Send Login message</li>
 *   <li>Receive LoginResponse</li>
 *   <li>Receive StreamAvail messages</li>
 *   <li>Send Open message for TG stream</li>
 *   <li>Receive OpenResponse</li>
 *   <li>Session established - can send orders</li>
 *   <li>Send/receive Heartbeat messages every second</li>
 *   <li>Send Close message to disconnect</li>
 * </ol>
 */
public class PillarSession extends SbeSession<PillarSessionConfig> {

    private static final Logger log = LoggerFactory.getLogger(PillarSession.class);

    private static final PillarMessageFactory MESSAGE_FACTORY = new PillarMessageFactory();

    private long pillarSessionId;
    private long tgStreamId;
    private long gtStreamId;
    private long tgNextSeqNum = 1;
    private long gtNextSeqNum = 1;
    private long lastHeartbeatSent;
    private long lastHeartbeatReceived;

    // Session state
    private boolean loggedIn;
    private boolean tgStreamOpen;
    private boolean gtStreamOpen;

    // Protocol-specific metrics
    private Counter loginCounter;
    private Counter streamOpenCounter;
    private Counter heartbeatSentCounter;
    private Counter heartbeatReceivedCounter;
    private Counter orderRejectCounter;

    /**
     * Creates a new Pillar session.
     *
     * @param config the session configuration
     * @param logStore the log store for message persistence (may be null)
     */
    public PillarSession(PillarSessionConfig config, LogStore logStore) {
        super(config, logStore);
    }

    @Override
    protected String getProtocolName() {
        return "Pillar";
    }

    @Override
    public void bindMetrics(MeterRegistry registry) {
        super.bindMetrics(registry);
        if (registry == null) return;

        Tags sessionTags = Tags.of(
                "session_id", sessionId,
                "protocol", "Pillar",
                "role", isInitiator ? "initiator" : "acceptor"
        );

        loginCounter = Counter.builder("omnibridge.session.logon.total")
                .tags(sessionTags).description("Login count").register(registry);
        streamOpenCounter = Counter.builder("omnibridge.pillar.stream.open.total")
                .tags(sessionTags).description("Stream open count").register(registry);
        heartbeatSentCounter = Counter.builder("omnibridge.heartbeat.sent.total")
                .tags(sessionTags).description("Heartbeats sent").register(registry);
        heartbeatReceivedCounter = Counter.builder("omnibridge.heartbeat.received.total")
                .tags(sessionTags).description("Heartbeats received").register(registry);
        orderRejectCounter = Counter.builder("omnibridge.messages.rejected.total")
                .tags(sessionTags).description("Order/cancel/replace rejects").register(registry);

        Gauge.builder("omnibridge.heartbeat.interval.seconds", this,
                        s -> s.config.getHeartbeatIntervalNanos() / 1_000_000_000.0)
                .tags(sessionTags).description("Heartbeat interval in seconds").register(registry);
    }

    @Override
    public SbeMessageFactory getMessageFactory() {
        return MESSAGE_FACTORY;
    }

    @Override
    protected int getExpectedMessageLength(DirectBuffer buffer, int offset, int available) {
        if (available < PillarMessage.MSG_HEADER_SIZE) {
            return -1;
        }

        // Read message length from header (includes header size)
        int msgLength = buffer.getShort(offset + PillarMessage.MSG_LENGTH_OFFSET, PillarMessage.BYTE_ORDER) & 0xFFFF;

        if (available < msgLength) {
            return -1;
        }

        return msgLength;
    }

    @Override
    protected void initiateHandshake() {
        log.info("[{}] Initiating Pillar login", sessionId);
        sendLogin();
    }

    @Override
    protected void processIncomingMessage(SbeMessage message) {
        lastHeartbeatReceived = System.nanoTime();

        if (!(message instanceof PillarMessage pillarMessage)) {
            log.warn("[{}] Received non-Pillar message: {}", sessionId, message);
            return;
        }

        PillarMessageType msgType = pillarMessage.getMessageType();
        log.debug("[{}] Processing {}", sessionId, msgType);

        switch (msgType) {
            case LOGIN_RESPONSE -> handleLoginResponse((LoginResponseMessage) pillarMessage);
            case STREAM_AVAIL -> handleStreamAvail((StreamAvailMessage) pillarMessage);
            case OPEN_RESPONSE -> handleOpenResponse((OpenResponseMessage) pillarMessage);
            case CLOSE_RESPONSE -> handleCloseResponse((CloseResponseMessage) pillarMessage);
            case HEARTBEAT -> handleHeartbeat((HeartbeatMessage) pillarMessage);
            case ORDER_ACK -> handleOrderAck((OrderAckMessage) pillarMessage);
            case ORDER_REJECT -> handleOrderReject((OrderRejectMessage) pillarMessage);
            case EXECUTION_REPORT -> handleExecutionReport((ExecutionReportMessage) pillarMessage);
            case CANCEL_ACK -> handleCancelAck((CancelAckMessage) pillarMessage);
            case CANCEL_REJECT -> handleCancelReject((CancelRejectMessage) pillarMessage);
            case REPLACE_ACK -> handleReplaceAck((ReplaceAckMessage) pillarMessage);
            case REPLACE_REJECT -> handleReplaceReject((ReplaceRejectMessage) pillarMessage);
            default -> {
                log.debug("[{}] Received application message: {}", sessionId, msgType);
                notifyMessage(pillarMessage);
            }
        }
    }

    // ==================== Message Handlers ====================

    private void handleLoginResponse(LoginResponseMessage response) {
        if (response.isSuccess()) {
            pillarSessionId = response.getSessionId();
            loggedIn = true;
            if (loginCounter != null) {
                loginCounter.increment();
            }
            log.info("[{}] Login successful, sessionId={}", sessionId, pillarSessionId);
            forceState(SbeSessionState.CONNECTED);
        } else {
            log.error("[{}] Login failed: {}", sessionId, response.getStatusDescription());
            disconnect();
        }
    }

    private void handleStreamAvail(StreamAvailMessage streamAvail) {
        long streamId = streamAvail.getStreamId();

        if (streamAvail.isTGStream()) {
            tgStreamId = streamId;
            if (loggedIn && !tgStreamOpen) {
                sendOpen(tgStreamId, StreamAvailMessage.ACCESS_WRITE);
            }
        } else if (streamAvail.isGTStream()) {
            gtStreamId = streamId;
            gtNextSeqNum = streamAvail.getNextSequenceNumber();
            if (loggedIn && !gtStreamOpen) {
                sendOpen(gtStreamId, StreamAvailMessage.ACCESS_READ);
            }
        }
    }

    private void handleOpenResponse(OpenResponseMessage response) {
        if (response.isSuccess()) {
            long streamId = response.getStreamId();
            if (streamOpenCounter != null) {
                streamOpenCounter.increment();
            }
            if (streamId == tgStreamId) {
                tgStreamOpen = true;
                log.info("[{}] TG stream opened", sessionId);
            } else if (streamId == gtStreamId) {
                gtStreamOpen = true;
                log.info("[{}] GT stream opened", sessionId);
            }

            // Session is established when both streams are open
            if (tgStreamOpen && gtStreamOpen) {
                forceState(SbeSessionState.ESTABLISHED);
                log.info("[{}] Session established", sessionId);
            }
        } else {
            log.error("[{}] Stream open failed: {}", sessionId, response.getStatusDescription());
        }
    }

    private void handleCloseResponse(CloseResponseMessage response) {
        log.info("[{}] Stream {} closed", sessionId, response.getStreamId());
    }

    private void handleHeartbeat(HeartbeatMessage heartbeat) {
        if (heartbeatReceivedCounter != null) {
            heartbeatReceivedCounter.increment();
        }
        log.trace("[{}] Heartbeat received", sessionId);
    }

    private void handleOrderAck(OrderAckMessage ack) {
        log.debug("[{}] Order acknowledged: clOrdId={}", sessionId, ack.getClOrdId());
        notifyMessage(ack);
    }

    private void handleOrderReject(OrderRejectMessage reject) {
        log.warn("[{}] Order rejected: clOrdId={}", sessionId, reject.getClOrdId());
        if (orderRejectCounter != null) {
            orderRejectCounter.increment();
        }
        notifyMessage(reject);
    }

    private void handleExecutionReport(ExecutionReportMessage execReport) {
        log.debug("[{}] Execution report: clOrdId={}, execId={}",
                sessionId, execReport.getClOrdId(), execReport.getExecId());
        notifyMessage(execReport);
    }

    private void handleCancelAck(CancelAckMessage ack) {
        log.debug("[{}] Cancel acknowledged: clOrdId={}", sessionId, ack.getClOrdId());
        notifyMessage(ack);
    }

    private void handleCancelReject(CancelRejectMessage reject) {
        log.warn("[{}] Cancel rejected: clOrdId={}", sessionId, reject.getClOrdId());
        if (orderRejectCounter != null) {
            orderRejectCounter.increment();
        }
        notifyMessage(reject);
    }

    private void handleReplaceAck(ReplaceAckMessage ack) {
        log.debug("[{}] Replace acknowledged: clOrdId={}", sessionId, ack.getClOrdId());
        notifyMessage(ack);
    }

    private void handleReplaceReject(ReplaceRejectMessage reject) {
        log.warn("[{}] Replace rejected: clOrdId={}", sessionId, reject.getClOrdId());
        if (orderRejectCounter != null) {
            orderRejectCounter.increment();
        }
        notifyMessage(reject);
    }

    // ==================== Message Sending ====================

    private void sendLogin() {
        LoginMessage login = tryClaim(LoginMessage.class);
        if (login == null) {
            log.warn("[{}] Failed to claim Login message", sessionId);
            return;
        }

        try {
            login.writeHeader();
            login.setUsername(config.getUsername())
                 .setPassword(config.getPassword())
                 .setProtocolVersionMajor(config.getProtocolVersionMajor())
                 .setProtocolVersionMinor(config.getProtocolVersionMinor());

            if (commit(login)) {
                log.info("[{}] Sent Login", sessionId);
            }
        } catch (Exception e) {
            abort(login);
            log.error("[{}] Error sending Login", sessionId, e);
        }
    }

    private void sendOpen(long streamId, byte accessFlags) {
        OpenMessage open = tryClaim(OpenMessage.class);
        if (open == null) {
            log.warn("[{}] Failed to claim Open message", sessionId);
            return;
        }

        try {
            open.writeHeader();
            open.setStreamId(streamId)
                .setStartSequenceNumber(1)
                .setAccessFlags(accessFlags)
                .setThrottlePreference(config.getThrottlePreference());

            if (commit(open)) {
                log.info("[{}] Sent Open for stream {}", sessionId, streamId);
            }
        } catch (Exception e) {
            abort(open);
            log.error("[{}] Error sending Open", sessionId, e);
        }
    }

    /**
     * Sends a heartbeat message.
     */
    public void sendHeartbeat() {
        HeartbeatMessage heartbeat = tryClaim(HeartbeatMessage.class);
        if (heartbeat == null) {
            return;
        }

        try {
            heartbeat.writeHeader();
            heartbeat.setCurrentTimestamp();

            if (commit(heartbeat)) {
                lastHeartbeatSent = System.nanoTime();
                if (heartbeatSentCounter != null) {
                    heartbeatSentCounter.increment();
                }
                log.trace("[{}] Sent Heartbeat", sessionId);
            }
        } catch (Exception e) {
            abort(heartbeat);
        }
    }

    /**
     * Sends a close message for the specified stream.
     *
     * @param streamId the stream ID to close
     */
    public void sendClose(long streamId) {
        CloseMessage close = tryClaim(CloseMessage.class);
        if (close == null) {
            return;
        }

        try {
            close.writeHeader();
            close.setStreamId(streamId);

            if (commit(close)) {
                log.info("[{}] Sent Close for stream {}", sessionId, streamId);
            }
        } catch (Exception e) {
            abort(close);
        }
    }

    // ==================== Public API ====================

    /**
     * Checks if the session is established and ready for trading.
     *
     * @return true if established
     */
    public boolean isEstablished() {
        return getSessionState() == SbeSessionState.ESTABLISHED;
    }

    /**
     * Checks if logged in to the gateway.
     *
     * @return true if logged in
     */
    public boolean isLoggedIn() {
        return loggedIn;
    }

    /**
     * Gets the Pillar session ID.
     *
     * @return the session ID
     */
    public long getPillarSessionId() {
        return pillarSessionId;
    }

    /**
     * Gets the TG (Trader to Gateway) stream ID.
     *
     * @return the TG stream ID
     */
    public long getTgStreamId() {
        return tgStreamId;
    }

    /**
     * Gets the GT (Gateway to Trader) stream ID.
     *
     * @return the GT stream ID
     */
    public long getGtStreamId() {
        return gtStreamId;
    }

    /**
     * Gets the next sequence number for TG stream.
     *
     * @return the next sequence number
     */
    public long getNextTgSeqNum() {
        return tgNextSeqNum++;
    }

    /**
     * Gets the next sequence number for GT stream.
     *
     * @return the next sequence number
     */
    public long getGtNextSeqNum() {
        return gtNextSeqNum;
    }

    /**
     * Checks if a heartbeat should be sent.
     *
     * @return true if heartbeat is due
     */
    public boolean isHeartbeatDue() {
        long heartbeatIntervalNanos = config.getHeartbeatIntervalNanos();
        return (System.nanoTime() - lastHeartbeatSent) > (heartbeatIntervalNanos / 2);
    }

    @Override
    public void disconnect() {
        if (getSessionState() == SbeSessionState.ESTABLISHED) {
            if (tgStreamOpen) {
                sendClose(tgStreamId);
            }
            if (gtStreamOpen) {
                sendClose(gtStreamId);
            }
        }
        super.disconnect();
    }

    @Override
    public void reset() {
        super.reset();
        loggedIn = false;
        tgStreamOpen = false;
        gtStreamOpen = false;
        tgNextSeqNum = 1;
        gtNextSeqNum = 1;
        lastHeartbeatSent = 0;
        lastHeartbeatReceived = 0;
    }
}
