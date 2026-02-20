package com.omnibridge.ilink3.engine;

import com.omnibridge.ilink3.engine.config.ILink3SessionConfig;
import com.omnibridge.ilink3.message.*;
import com.omnibridge.ilink3.message.order.*;
import com.omnibridge.ilink3.message.session.*;
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

import java.util.concurrent.atomic.AtomicLong;

/**
 * CME iLink 3 session implementation.
 * <p>
 * Manages the session lifecycle with the CME Globex system using the
 * Negotiate/Establish session model.
 * <p>
 * State transitions:
 * <pre>
 * CREATED -> CONNECTING -> CONNECTED -> NEGOTIATING -> ESTABLISHING -> ESTABLISHED
 *                                            |                |
 *                                   DISCONNECTED       DISCONNECTED
 * </pre>
 */
public class ILink3Session extends SbeSession<ILink3SessionConfig> {

    private static final Logger log = LoggerFactory.getLogger(ILink3Session.class);

    private static final ILink3MessageFactory MESSAGE_FACTORY = new ILink3MessageFactory();

    /** UUID for this session (assigned by CME or generated) */
    private final AtomicLong uuid;

    /** Previous UUID (for session recovery) */
    private volatile long previousUuid;

    /** Keep alive interval in milliseconds */
    private volatile int keepAliveInterval;

    /** Last time a message was sent */
    private volatile long lastSentTime;

    /** Last time a message was received */
    private volatile long lastReceivedTime;

    // Protocol-specific metrics
    private Counter negotiationCounter;
    private Counter establishmentCounter;
    private Counter terminationCounter;
    private Counter heartbeatSentCounter;
    private Counter heartbeatReceivedCounter;

    /**
     * Creates a new iLink 3 session.
     *
     * @param config the session configuration
     * @param logStore the log store for message persistence (may be null)
     */
    public ILink3Session(ILink3SessionConfig config, LogStore logStore) {
        super(config, logStore);
        this.uuid = new AtomicLong(config.getUuid());
        this.keepAliveInterval = config.getKeepAliveInterval();
    }

    @Override
    protected String getProtocolName() {
        return "iLink3";
    }

    @Override
    public void bindMetrics(MeterRegistry registry) {
        super.bindMetrics(registry);
        if (registry == null) return;

        Tags sessionTags = Tags.of(
                "session_id", sessionId,
                "protocol", "iLink3",
                "role", isInitiator ? "initiator" : "acceptor"
        );

        negotiationCounter = Counter.builder("omnibridge.ilink3.negotiation.total")
                .tags(sessionTags).description("Negotiation attempts").register(registry);
        establishmentCounter = Counter.builder("omnibridge.ilink3.establishment.total")
                .tags(sessionTags).description("Establishment attempts").register(registry);
        terminationCounter = Counter.builder("omnibridge.ilink3.termination.total")
                .tags(sessionTags).description("Termination count").register(registry);
        heartbeatSentCounter = Counter.builder("omnibridge.heartbeat.sent.total")
                .tags(sessionTags).description("Heartbeats sent (Sequence)").register(registry);
        heartbeatReceivedCounter = Counter.builder("omnibridge.heartbeat.received.total")
                .tags(sessionTags).description("Heartbeats received (Sequence)").register(registry);

        Gauge.builder("omnibridge.heartbeat.interval.seconds", this,
                        s -> s.keepAliveInterval / 1000.0)
                .tags(sessionTags).description("Keep alive interval in seconds").register(registry);
    }

    @Override
    public SbeMessageFactory getMessageFactory() {
        return MESSAGE_FACTORY;
    }

    @Override
    protected int getExpectedMessageLength(DirectBuffer buffer, int offset, int available) {
        if (available < ILink3Message.FRAMING_HEADER_SIZE) {
            return -1;
        }

        // Read message length from framing header
        int messageLength = buffer.getShort(offset, SbeMessage.BYTE_ORDER) & 0xFFFF;
        int totalLength = ILink3Message.FRAMING_HEADER_SIZE + messageLength;

        if (available < totalLength) {
            return -1;
        }

        return totalLength;
    }

    @Override
    protected void initiateHandshake() {
        log.info("[{}] Initiating iLink 3 negotiation", sessionId);
        forceState(SbeSessionState.NEGOTIATING);
        sendNegotiate();
    }

    @Override
    protected void processIncomingMessage(SbeMessage message) {
        lastReceivedTime = System.currentTimeMillis();

        if (!(message instanceof ILink3Message)) {
            log.warn("[{}] Received non-iLink3 message: {}", sessionId, message);
            return;
        }

        ILink3MessageType msgType = ((ILink3Message) message).getMessageType();
        log.debug("[{}] Processing {}", sessionId, msgType);

        switch (msgType) {
            case NEGOTIATION_RESPONSE -> handleNegotiationResponse((NegotiationResponseMessage) message);
            case NEGOTIATION_REJECT -> handleNegotiationReject(message);
            case ESTABLISHMENT_ACK -> handleEstablishmentAck((EstablishmentAckMessage) message);
            case ESTABLISHMENT_REJECT -> handleEstablishmentReject(message);
            case TERMINATE -> handleTerminate((TerminateMessage) message);
            case SEQUENCE -> handleSequence((SequenceMessage) message);
            case EXECUTION_REPORT_NEW -> handleExecutionReport((ExecutionReportNewMessage) message);
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
     * Sends a Negotiate message to initiate the session.
     */
    private void sendNegotiate() {
        NegotiateMessage msg = tryClaim(NegotiateMessage.class);
        if (msg == null) {
            log.warn("[{}] Failed to claim Negotiate message", sessionId);
            return;
        }

        try {
            msg.writeHeader();
            msg.setUuid(uuid.get())
               .setRequestTimestamp(System.nanoTime())
               .setSession(config.getSession() != null ? config.getSession() : "")
               .setFirm(config.getFirmId() != null ? config.getFirmId() : "")
               .setAccessKeyId(config.getAccessKeyId() != null ? config.getAccessKeyId() : "");

            // HMAC signature would be computed here if useHmac is enabled

            if (commit(msg)) {
                log.info("[{}] Sent Negotiate: uuid={}", sessionId, uuid.get());
            }
        } catch (Exception e) {
            abort(msg);
            log.error("[{}] Error sending Negotiate", sessionId, e);
        }
    }

    /**
     * Handles a NegotiationResponse message.
     */
    private void handleNegotiationResponse(NegotiationResponseMessage msg) {
        if (state.get() != SbeSessionState.NEGOTIATING) {
            log.warn("[{}] Unexpected NegotiationResponse in state {}", sessionId, state.get());
            return;
        }

        long responseUuid = msg.getUuid();
        this.previousUuid = msg.getPreviousUuid();
        long previousSeqNo = msg.getPreviousSeqNo();

        log.info("[{}] Received NegotiationResponse: uuid={}, previousSeqNo={}",
                sessionId, responseUuid, previousSeqNo);

        // Update UUID if needed
        if (responseUuid != uuid.get()) {
            uuid.set(responseUuid);
        }

        // Proceed to establishment
        if (negotiationCounter != null) {
            negotiationCounter.increment();
        }
        forceState(SbeSessionState.ESTABLISHING);
        sendEstablish(previousSeqNo);
    }

    /**
     * Handles a NegotiationReject message.
     */
    private void handleNegotiationReject(SbeMessage msg) {
        log.error("[{}] Negotiation rejected", sessionId);
        forceState(SbeSessionState.DISCONNECTED);
    }

    /**
     * Sends an Establish message.
     */
    private void sendEstablish(long nextSeqNo) {
        EstablishMessage msg = tryClaim(EstablishMessage.class);
        if (msg == null) {
            log.warn("[{}] Failed to claim Establish message", sessionId);
            return;
        }

        try {
            msg.writeHeader();
            msg.setUuid(uuid.get())
               .setRequestTimestamp(System.nanoTime())
               .setNextSeqNo(nextSeqNo + 1)
               .setSession(config.getSession() != null ? config.getSession() : "")
               .setFirm(config.getFirmId() != null ? config.getFirmId() : "")
               .setAccessKeyId(config.getAccessKeyId() != null ? config.getAccessKeyId() : "")
               .setKeepAliveInterval(keepAliveInterval);

            if (commit(msg)) {
                log.info("[{}] Sent Establish: uuid={}, nextSeqNo={}", sessionId, uuid.get(), nextSeqNo + 1);
            }
        } catch (Exception e) {
            abort(msg);
            log.error("[{}] Error sending Establish", sessionId, e);
        }
    }

    /**
     * Handles an EstablishmentAck message.
     */
    private void handleEstablishmentAck(EstablishmentAckMessage msg) {
        if (state.get() != SbeSessionState.ESTABLISHING) {
            log.warn("[{}] Unexpected EstablishmentAck in state {}", sessionId, state.get());
            return;
        }

        long nextSeqNo = msg.getNextSeqNo();
        long ackPreviousSeqNo = msg.getPreviousSeqNo();
        int ackKeepAlive = msg.getKeepAliveInterval();

        log.info("[{}] Received EstablishmentAck: nextSeqNo={}, keepAlive={}",
                sessionId, nextSeqNo, ackKeepAlive);

        this.keepAliveInterval = ackKeepAlive;
        setInboundSeqNum(nextSeqNo);

        if (establishmentCounter != null) {
            establishmentCounter.increment();
        }
        forceState(SbeSessionState.ESTABLISHED);
        log.info("[{}] Session established", sessionId);
    }

    /**
     * Handles an EstablishmentReject message.
     */
    private void handleEstablishmentReject(SbeMessage msg) {
        log.error("[{}] Establishment rejected", sessionId);
        forceState(SbeSessionState.DISCONNECTED);
    }

    /**
     * Handles a Terminate message.
     */
    private void handleTerminate(TerminateMessage msg) {
        int errorCode = msg.getErrorCodes();
        String reason = msg.getReason();

        log.info("[{}] Received Terminate: errorCode={}, reason={}", sessionId, errorCode, reason);

        if (terminationCounter != null) {
            terminationCounter.increment();
        }
        forceState(SbeSessionState.TERMINATED);
    }

    /**
     * Handles a Sequence message (heartbeat).
     */
    private void handleSequence(SequenceMessage msg) {
        long msgUuid = msg.getUuid();
        long nextSeqNo = msg.getNextSeqNo();

        log.debug("[{}] Received Sequence: nextSeqNo={}", sessionId, nextSeqNo);
        if (heartbeatReceivedCounter != null) {
            heartbeatReceivedCounter.increment();
        }

        // Respond with Sequence if needed
        if (state.get() == SbeSessionState.ESTABLISHED) {
            sendSequence();
        }
    }

    /**
     * Sends a Sequence message (heartbeat).
     */
    public void sendSequence() {
        SequenceMessage msg = tryClaim(SequenceMessage.class);
        if (msg == null) {
            return;
        }

        try {
            msg.writeHeader();
            msg.setUuid(uuid.get())
               .setNextSeqNo(getOutboundSeqNum() + 1);

            if (commit(msg)) {
                lastSentTime = System.currentTimeMillis();
                if (heartbeatSentCounter != null) {
                    heartbeatSentCounter.increment();
                }
                log.debug("[{}] Sent Sequence", sessionId);
            }
        } catch (Exception e) {
            abort(msg);
        }
    }

    /**
     * Sends a Terminate message to gracefully close the session.
     */
    public void sendTerminate(String reason) {
        TerminateMessage msg = tryClaim(TerminateMessage.class);
        if (msg == null) {
            return;
        }

        try {
            msg.writeHeader();
            msg.setUuid(uuid.get())
               .setRequestTimestamp(System.nanoTime())
               .setErrorCodes(0)
               .setReason(reason != null ? reason : "Normal termination");

            if (commit(msg)) {
                log.info("[{}] Sent Terminate", sessionId);
                forceState(SbeSessionState.TERMINATING);
            }
        } catch (Exception e) {
            abort(msg);
        }
    }

    // =====================================================
    // Execution Report Handling
    // =====================================================

    private void handleExecutionReport(ExecutionReportNewMessage msg) {
        String clOrdId = msg.getClOrdId();
        long orderId = msg.getOrderId();

        log.debug("[{}] Received ExecutionReport: clOrdId={}, orderId={}",
                sessionId, clOrdId, orderId);

        notifyMessage(msg);
    }

    // =====================================================
    // Accessors
    // =====================================================

    /**
     * Gets the session UUID.
     *
     * @return the UUID
     */
    public long getUuid() {
        return uuid.get();
    }

    /**
     * Sets the session UUID.
     *
     * @param uuid the UUID
     */
    public void setUuid(long uuid) {
        this.uuid.set(uuid);
    }

    /**
     * Gets the keep alive interval in milliseconds.
     *
     * @return the interval
     */
    public int getKeepAliveInterval() {
        return keepAliveInterval;
    }

    /**
     * Checks if a heartbeat should be sent.
     *
     * @return true if a heartbeat is due
     */
    public boolean isHeartbeatDue() {
        return (System.currentTimeMillis() - lastSentTime) > (keepAliveInterval / 2);
    }

    @Override
    public void disconnect() {
        if (state.get() == SbeSessionState.ESTABLISHED) {
            sendTerminate("Client disconnect");
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
