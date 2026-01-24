package com.fixengine.apps.fix.acceptor;

import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.MessageListener;
import com.fixengine.message.FixTags;
import com.fixengine.message.IncomingFixMessage;
import com.fixengine.message.RingBufferOutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Message listener for the FIX acceptor (exchange simulator).
 * Handles incoming orders and sends execution reports.
 */
public class AcceptorMessageListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(AcceptorMessageListener.class);

    private final AtomicLong execIdCounter = new AtomicLong(1);
    private final AtomicLong orderIdCounter = new AtomicLong(1);
    private final double fillRate;
    private final boolean latencyMode;

    // Statistics counters
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong ordersReceived = new AtomicLong(0);
    private final AtomicLong cancelRequestsReceived = new AtomicLong(0);
    private final AtomicLong replaceRequestsReceived = new AtomicLong(0);
    private final AtomicLong executionReportsSent = new AtomicLong(0);

    /**
     * Create a message listener with default settings.
     */
    public AcceptorMessageListener() {
        this(0.8, false);
    }

    /**
     * Create a message listener with custom settings.
     *
     * @param fillRate probability of immediate fill (0.0-1.0)
     * @param latencyMode if true, minimize logging and skip fill simulation
     */
    public AcceptorMessageListener(double fillRate, boolean latencyMode) {
        this.fillRate = fillRate;
        this.latencyMode = latencyMode;
    }

    @Override
    public void onMessage(FixSession session, IncomingFixMessage message) {
        long count = messagesReceived.incrementAndGet();
        if (count % 100 == 0) {
            log.error("Received {} messages", count);
        }

        String msgType = message.getMsgType();
        if (!latencyMode) {
            log.info("Received message: {} from {}", msgType, session.getConfig().getSessionId());
        }

        switch (msgType) {
            case FixTags.MsgTypes.NewOrderSingle:
                handleNewOrder(session, message);
                break;
            case FixTags.MsgTypes.OrderCancelRequest:
                handleOrderCancel(session, message);
                break;
            case FixTags.MsgTypes.OrderCancelReplaceRequest:
                handleOrderReplace(session, message);
                break;
            default:
                log.warn("Unknown message type: {}", msgType);
        }
    }

    @Override
    public void onReject(FixSession session, int refSeqNum, String refMsgType, int rejectReason, String text) {
        log.warn("Message {} (type {}) rejected: {} - {}", refSeqNum, refMsgType, rejectReason, text);
    }

    private void handleNewOrder(FixSession session, IncomingFixMessage order) {
        ordersReceived.incrementAndGet();
        CharSequence clOrdId = order.getCharSequence(FixTags.ClOrdID);
        CharSequence symbol = order.getCharSequence(FixTags.Symbol);
        char side = order.getChar(FixTags.Side);
        int orderQty = order.getInt(FixTags.OrderQty);
        char ordType = order.getChar(FixTags.OrdType);
        double price = order.getDouble(FixTags.Price);

        if (!latencyMode) {
            log.info("New Order: ClOrdID={}, Symbol={}, Side={}, Qty={}, Type={}, Price={}",
                    clOrdId, symbol, side, orderQty, ordType, price);
        }

        String orderId = "ORD" + orderIdCounter.getAndIncrement();

        // Send acknowledgment (New)
        sendExecutionReport(session, clOrdId, orderId, symbol, side,
                FixTags.EXEC_TYPE_NEW, FixTags.ORD_STATUS_NEW,
                orderQty, 0, 0, price, 0, latencyMode ? null : "Order accepted");

        // In latency mode, skip fill simulation to minimize latency
        if (!latencyMode && Math.random() < fillRate) {
            double fillPrice = price > 0 ? price : 100.0;
            sendExecutionReport(session, clOrdId, orderId, symbol, side,
                    FixTags.EXEC_TYPE_FILL, FixTags.ORD_STATUS_FILLED,
                    orderQty, orderQty, 0, fillPrice, fillPrice, "Order filled");
        }
    }

    private void handleOrderCancel(FixSession session, IncomingFixMessage cancel) {
        cancelRequestsReceived.incrementAndGet();
        String clOrdId = asString(cancel.getCharSequence(FixTags.ClOrdID));
        String origClOrdId = asString(cancel.getCharSequence(41));
        String symbol = asString(cancel.getCharSequence(FixTags.Symbol));
        char side = cancel.getChar(FixTags.Side);
        int orderQty = cancel.getInt(FixTags.OrderQty);

        if (!latencyMode) {
            log.info("Cancel Request: ClOrdID={}, OrigClOrdID={}", clOrdId, origClOrdId);
        }

        String orderId = "ORD" + orderIdCounter.getAndIncrement();
        sendExecutionReport(session, clOrdId, orderId, symbol, side,
                FixTags.EXEC_TYPE_CANCELED, FixTags.ORD_STATUS_CANCELED,
                orderQty, 0, 0, 0, 0, "Order canceled");
    }

    private void handleOrderReplace(FixSession session, IncomingFixMessage replace) {
        replaceRequestsReceived.incrementAndGet();
        String clOrdId = asString(replace.getCharSequence(FixTags.ClOrdID));
        String origClOrdId = asString(replace.getCharSequence(41));
        String symbol = asString(replace.getCharSequence(FixTags.Symbol));
        char side = replace.getChar(FixTags.Side);
        int orderQty = replace.getInt(FixTags.OrderQty);
        double price = replace.getDouble(FixTags.Price);

        if (!latencyMode) {
            log.info("Replace Request: ClOrdID={}, OrigClOrdID={}, NewQty={}, NewPrice={}",
                    clOrdId, origClOrdId, orderQty, price);
        }

        String orderId = "ORD" + orderIdCounter.getAndIncrement();
        sendExecutionReport(session, clOrdId, orderId, symbol, side,
                FixTags.EXEC_TYPE_REPLACED, FixTags.ORD_STATUS_REPLACED,
                orderQty, 0, orderQty, price, 0, "Order replaced");
    }

    private void sendExecutionReport(FixSession session, CharSequence clOrdId, String orderId,
                                     CharSequence symbol, char side, char execType, char ordStatus,
                                     double orderQty, double cumQty, double leavesQty,
                                     double price, double avgPx, String text) {
        try {
            RingBufferOutgoingMessage execReport = session.tryClaimMessage(FixTags.MsgTypes.ExecutionReport);
            if (execReport == null) {
                log.error("Ring buffer full - could not send execution report for order {}", orderId);
                return;
            }

            execReport.setField(FixTags.OrderID, orderId);
            execReport.setField(FixTags.ClOrdID, clOrdId);
            execReport.setField(FixTags.ExecID, "EXEC" + execIdCounter.getAndIncrement());
            execReport.setField(FixTags.ExecType, execType);
            execReport.setField(FixTags.OrdStatus, ordStatus);
            execReport.setField(FixTags.Symbol, symbol);
            execReport.setField(FixTags.Side, side);
            execReport.setField(FixTags.OrderQty, (int) orderQty);
            execReport.setField(FixTags.CumQty, (int) cumQty);
            execReport.setField(FixTags.LeavesQty, (int) leavesQty);
            execReport.setField(FixTags.AvgPx, avgPx, 2);

            if (price > 0) {
                execReport.setField(FixTags.Price, price, 2);
            }

            if (cumQty > 0) {
                execReport.setField(FixTags.LastQty, (int) cumQty);
                execReport.setField(FixTags.LastPx, avgPx, 2);
            }

            if (text != null) {
                execReport.setField(FixTags.Text, text);
            }

            session.commitMessage(execReport);
            executionReportsSent.incrementAndGet();
            if (!latencyMode) {
                log.info("Sent ExecutionReport: OrdID={}, ExecType={}, OrdStatus={}",
                        orderId, execType, ordStatus);
            }
        } catch (Exception e) {
            log.error("Error sending execution report", e);
        }
    }

    private static String asString(CharSequence cs) {
        return cs != null ? cs.toString() : null;
    }

    // ==================== Statistics Getters ====================

    public long getOrdersReceived() {
        return ordersReceived.get();
    }

    public long getCancelRequestsReceived() {
        return cancelRequestsReceived.get();
    }

    public long getReplaceRequestsReceived() {
        return replaceRequestsReceived.get();
    }

    public long getExecutionReportsSent() {
        return executionReportsSent.get();
    }

    public long getTotalMessagesReceived() {
        return ordersReceived.get() + cancelRequestsReceived.get() + replaceRequestsReceived.get();
    }
}
