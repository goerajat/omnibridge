package com.omnibridge.apps.ouch.initiator;

import com.omnibridge.apps.common.LatencyTracker;
import com.omnibridge.ouch.engine.session.OuchSession;
import com.omnibridge.ouch.message.*;
import com.omnibridge.ouch.message.v50.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Message handler for the OUCH initiator (trading client).
 *
 * <p>Handles incoming messages using the type-safe OuchMessageListener interface.
 * Works with both OUCH 4.2 and 5.0 protocols - the message handling is
 * version-agnostic since OuchMessageListener provides a unified interface.</p>
 */
public class InitiatorMessageHandler implements OuchMessageListener<OuchSession> {

    private static final Logger log = LoggerFactory.getLogger(InitiatorMessageHandler.class);

    private final boolean latencyMode;

    // Latency tracking (optional)
    private volatile LatencyTracker latencyTracker;
    private final AtomicInteger receivedCount = new AtomicInteger(0);

    // Statistics
    private final AtomicLong acceptedCount = new AtomicLong(0);
    private final AtomicLong executedCount = new AtomicLong(0);
    private final AtomicLong canceledCount = new AtomicLong(0);
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final AtomicLong replacedCount = new AtomicLong(0);

    public InitiatorMessageHandler(boolean latencyMode) {
        this.latencyMode = latencyMode;
    }

    /**
     * Set a latency tracker for performance testing.
     * Resets the received count to align indices with the new tracker.
     */
    public void setLatencyTracker(LatencyTracker tracker) {
        if (tracker != null) {
            // Reset received count to align with new tracker's indices
            receivedCount.set(0);
        }
        this.latencyTracker = tracker;
    }

    @Override
    public void onOrderAccepted(OuchSession session, OrderAcceptedMessage msg) {
        acceptedCount.incrementAndGet();
        int idx = receivedCount.getAndIncrement();

        // Record latency if tracking
        LatencyTracker tracker = this.latencyTracker;
        if (tracker != null) {
            tracker.recordReceive(idx);
        }

        if (!latencyMode) {
            log.info("[{}] Order Accepted: token={}, symbol={}, side={}, qty={}, price={}",
                    session.getSessionId(),
                    msg.getOrderToken(),
                    msg.getSymbol(),
                    msg.getSideCode(),
                    msg.getShares(),
                    msg.getPriceAsDouble());
        }
    }

    @Override
    public void onOrderExecuted(OuchSession session, OrderExecutedMessage msg) {
        executedCount.incrementAndGet();

        if (!latencyMode) {
            log.info("[{}] Order Executed: token={}, execQty={}, execPrice={}, match={}",
                    session.getSessionId(),
                    msg.getOrderToken(),
                    msg.getExecutedShares(),
                    msg.getExecutionPriceAsDouble(),
                    msg.getMatchNumber());
        }
    }

    @Override
    public void onOrderCanceled(OuchSession session, OrderCanceledMessage msg) {
        canceledCount.incrementAndGet();

        if (!latencyMode) {
            log.info("[{}] Order Canceled: token={}, decrement={}, reason={}",
                    session.getSessionId(),
                    msg.getOrderToken(),
                    msg.getDecrementShares(),
                    msg.getReason());
        }
    }

    @Override
    public void onOrderRejected(OuchSession session, OrderRejectedMessage msg) {
        rejectedCount.incrementAndGet();

        // Always log rejections
        log.warn("[{}] Order Rejected: token={}, reason={}",
                session.getSessionId(),
                msg.getOrderToken(),
                msg.getRejectReasonDescription());
    }

    @Override
    public void onOrderReplaced(OuchSession session, OrderReplacedMessage msg) {
        replacedCount.incrementAndGet();

        if (!latencyMode) {
            log.info("[{}] Order Replaced: newToken={}, prevToken={}, qty={}, price={}",
                    session.getSessionId(),
                    msg.getReplacementOrderToken(),
                    msg.getPreviousOrderToken(),
                    msg.getShares(),
                    msg.getPriceAsDouble());
        }
    }

    @Override
    public void onSystemEvent(OuchSession session, SystemEventMessage msg) {
        log.info("[{}] System Event: {}", session.getSessionId(), msg.getEventDescription());
    }

    @Override
    public void onMessage(OuchSession session, OuchMessage message) {
        // Generic handler for any message type not specifically handled above
        if (!latencyMode) {
            log.debug("[{}] Received message: {}", session.getSessionId(), message.getMessageType());
        }
    }

    // =====================================================
    // V50 callbacks
    // =====================================================

    @Override
    public void onOrderAcceptedV50(OuchSession session, V50OrderAcceptedMessage msg) {
        acceptedCount.incrementAndGet();
        int idx = receivedCount.getAndIncrement();

        // Record latency if tracking
        LatencyTracker tracker = this.latencyTracker;
        if (tracker != null) {
            tracker.recordReceive(idx);
        }

        if (!latencyMode) {
            log.info("[{}] Order Accepted (V50): userRef={}, symbol={}, side={}, qty={}, price={}",
                    session.getSessionId(),
                    msg.getUserRefNum(),
                    msg.getSymbol(),
                    msg.getSideCode(),
                    msg.getQuantity(),
                    msg.getPriceAsDouble());
        }
    }

    @Override
    public void onOrderExecutedV50(OuchSession session, V50OrderExecutedMessage msg) {
        executedCount.incrementAndGet();

        if (!latencyMode) {
            log.info("[{}] Order Executed (V50): userRef={}, execQty={}, execPrice={}, match={}",
                    session.getSessionId(),
                    msg.getUserRefNum(),
                    msg.getExecutedQuantity(),
                    msg.getExecutionPriceAsDouble(),
                    msg.getMatchNumber());
        }
    }

    @Override
    public void onOrderCanceledV50(OuchSession session, V50OrderCanceledMessage msg) {
        canceledCount.incrementAndGet();

        if (!latencyMode) {
            log.info("[{}] Order Canceled (V50): userRef={}, decrement={}, reason={}",
                    session.getSessionId(),
                    msg.getUserRefNum(),
                    msg.getDecrementQuantity(),
                    msg.getReason());
        }
    }

    @Override
    public void onOrderRejectedV50(OuchSession session, V50OrderRejectedMessage msg) {
        rejectedCount.incrementAndGet();

        // Always log rejections
        log.warn("[{}] Order Rejected (V50): userRef={}, reason={}",
                session.getSessionId(),
                msg.getUserRefNum(),
                msg.getRejectReason());
    }

    @Override
    public void onOrderReplacedV50(OuchSession session, V50OrderReplacedMessage msg) {
        replacedCount.incrementAndGet();

        if (!latencyMode) {
            log.info("[{}] Order Replaced (V50): userRef={}, qty={}, price={}",
                    session.getSessionId(),
                    msg.getUserRefNum(),
                    msg.getQuantity(),
                    msg.getPriceAsDouble());
        }
    }

    // Statistics getters

    public long getAcceptedCount() {
        return acceptedCount.get();
    }

    public long getExecutedCount() {
        return executedCount.get();
    }

    public long getCanceledCount() {
        return canceledCount.get();
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }

    public long getReplacedCount() {
        return replacedCount.get();
    }

    public int getReceivedCount() {
        return receivedCount.get();
    }
}
