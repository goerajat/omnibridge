package com.fixengine.samples.initiator;

import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.MessageListener;
import com.fixengine.message.FixTags;
import com.fixengine.message.IncomingFixMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Message listener for the FIX initiator.
 * Handles execution reports and cancel rejects.
 * Supports latency tracking for performance testing.
 */
public class InitiatorMessageListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(InitiatorMessageListener.class);

    private final boolean latencyMode;
    private final AtomicLong messagesReceived = new AtomicLong(0);

    // Latency tracking (optional, for performance testing)
    private volatile LatencyTracker latencyTracker;

    /**
     * Create a message listener with default settings.
     */
    public InitiatorMessageListener() {
        this(false);
    }

    /**
     * Create a message listener.
     *
     * @param latencyMode if true, minimize logging for performance testing
     */
    public InitiatorMessageListener(boolean latencyMode) {
        this.latencyMode = latencyMode;
    }

    /**
     * Set a latency tracker for performance testing.
     *
     * @param tracker the latency tracker (may be null to disable tracking)
     */
    public void setLatencyTracker(LatencyTracker tracker) {
        this.latencyTracker = tracker;
    }

    @Override
    public void onMessage(FixSession session, IncomingFixMessage message) {
        long count = messagesReceived.incrementAndGet();
        if (count % 100 == 0) {
            log.error("Received {} messages", count);
        }

        long receiveTime = System.nanoTime();
        String msgType = message.getMsgType();

        if (FixTags.MsgTypes.ExecutionReport.equals(msgType)) {
            handleExecutionReport(message, receiveTime);
        } else if (FixTags.MsgTypes.OrderCancelReject.equals(msgType)) {
            handleCancelReject(message);
        } else if (!latencyMode) {
            log.info("Received message: {}", msgType);
        }
    }

    @Override
    public void onReject(FixSession session, int refSeqNum, String refMsgType, int rejectReason, String text) {
        log.warn("Message {} (type {}) rejected: {} - {}", refSeqNum, refMsgType, rejectReason, text);
    }

    private void handleExecutionReport(IncomingFixMessage execReport, long receiveTime) {
        String clOrdId = asString(execReport.getCharSequence(FixTags.ClOrdID));
        char execType = execReport.getChar(FixTags.ExecType);

        // Track latency for NEW acks (first response to our order)
        if (execType == FixTags.EXEC_TYPE_NEW) {
            LatencyTracker tracker = this.latencyTracker;
            if (tracker != null && clOrdId != null) {
                tracker.recordResponseTime(clOrdId, receiveTime);
            }
        }

        // Log details in non-latency mode
        if (!latencyMode) {
            String orderId = asString(execReport.getCharSequence(FixTags.OrderID));
            char ordStatus = execReport.getChar(FixTags.OrdStatus);
            String symbol = asString(execReport.getCharSequence(FixTags.Symbol));
            double cumQty = execReport.getDouble(FixTags.CumQty);
            double leavesQty = execReport.getDouble(FixTags.LeavesQty);
            double avgPx = execReport.getDouble(FixTags.AvgPx);
            String text = asString(execReport.getCharSequence(FixTags.Text));

            String execTypeStr = switch (execType) {
                case FixTags.EXEC_TYPE_NEW -> "NEW";
                case FixTags.EXEC_TYPE_PARTIAL_FILL -> "PARTIAL_FILL";
                case FixTags.EXEC_TYPE_FILL -> "FILL";
                case FixTags.EXEC_TYPE_CANCELED -> "CANCELED";
                case FixTags.EXEC_TYPE_REPLACED -> "REPLACED";
                case FixTags.EXEC_TYPE_REJECTED -> "REJECTED";
                default -> String.valueOf(execType);
            };

            log.info("ExecutionReport: ClOrdID={}, OrdID={}, ExecType={}, Status={}, " +
                            "Symbol={}, CumQty={}, LeavesQty={}, AvgPx={}, Text={}",
                    clOrdId, orderId, execTypeStr, ordStatus, symbol, cumQty, leavesQty, avgPx, text);
        }
    }

    private void handleCancelReject(IncomingFixMessage reject) {
        String clOrdId = asString(reject.getCharSequence(FixTags.ClOrdID));
        String orderId = asString(reject.getCharSequence(FixTags.OrderID));
        int rejectReason = reject.getInt(102); // CxlRejReason
        String text = asString(reject.getCharSequence(FixTags.Text));

        log.warn("OrderCancelReject: ClOrdID={}, OrdID={}, Reason={}, Text={}",
                clOrdId, orderId, rejectReason, text);
    }

    private static String asString(CharSequence cs) {
        return cs != null ? cs.toString() : null;
    }
}
