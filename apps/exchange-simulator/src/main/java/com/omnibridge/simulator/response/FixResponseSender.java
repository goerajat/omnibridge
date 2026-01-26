package com.omnibridge.simulator.response;

import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.message.FixTags;
import com.omnibridge.fix.message.RingBufferOutgoingMessage;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.order.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Response sender for FIX protocol.
 */
public class FixResponseSender implements ResponseSender<FixSession> {

    private static final Logger log = LoggerFactory.getLogger(FixResponseSender.class);

    private final AtomicLong execIdCounter = new AtomicLong(1);

    @Override
    public boolean sendOrderAccepted(FixSession session, Order order) {
        try {
            RingBufferOutgoingMessage execReport = session.tryClaimMessage(FixTags.MsgTypes.ExecutionReport);
            if (execReport == null) {
                log.error("Ring buffer full - could not send order accepted for {}", order.getOrderId());
                return false;
            }

            execReport.setField(FixTags.OrderID, String.valueOf(order.getOrderId()));
            execReport.setField(FixTags.ClOrdID, order.getClientOrderId());
            execReport.setField(FixTags.ExecID, "EXEC" + execIdCounter.getAndIncrement());
            execReport.setField(FixTags.ExecType, FixTags.EXEC_TYPE_NEW);
            execReport.setField(FixTags.OrdStatus, FixTags.ORD_STATUS_NEW);
            execReport.setField(FixTags.Symbol, order.getSymbol());
            execReport.setField(FixTags.Side, order.getSide().getFixValue());
            execReport.setField(FixTags.OrderQty, (int) order.getOriginalQty());
            execReport.setField(FixTags.CumQty, 0);
            execReport.setField(FixTags.LeavesQty, (int) order.getOriginalQty());
            execReport.setField(FixTags.AvgPx, 0.0, 2);
            if (order.getPrice() > 0) {
                execReport.setField(FixTags.Price, order.getPrice(), 2);
            }

            session.commitMessage(execReport);
            return true;
        } catch (Exception e) {
            log.error("Error sending order accepted", e);
            return false;
        }
    }

    @Override
    public boolean sendFill(FixSession session, Order order, FillDecision decision, long execId) {
        try {
            RingBufferOutgoingMessage execReport = session.tryClaimMessage(FixTags.MsgTypes.ExecutionReport);
            if (execReport == null) {
                log.error("Ring buffer full - could not send fill for {}", order.getOrderId());
                return false;
            }

            char execType = decision.isFullFill() ? FixTags.EXEC_TYPE_FILL : FixTags.EXEC_TYPE_PARTIAL_FILL;
            char ordStatus = order.getLeavesQty() <= 0 ? FixTags.ORD_STATUS_FILLED : FixTags.ORD_STATUS_PARTIALLY_FILLED;

            execReport.setField(FixTags.OrderID, String.valueOf(order.getOrderId()));
            execReport.setField(FixTags.ClOrdID, order.getClientOrderId());
            execReport.setField(FixTags.ExecID, "EXEC" + execId);
            execReport.setField(FixTags.ExecType, execType);
            execReport.setField(FixTags.OrdStatus, ordStatus);
            execReport.setField(FixTags.Symbol, order.getSymbol());
            execReport.setField(FixTags.Side, order.getSide().getFixValue());
            execReport.setField(FixTags.OrderQty, (int) order.getOriginalQty());
            execReport.setField(FixTags.CumQty, (int) order.getFilledQty());
            execReport.setField(FixTags.LeavesQty, (int) order.getLeavesQty());
            execReport.setField(FixTags.AvgPx, order.getAvgFillPrice(), 2);
            execReport.setField(FixTags.LastQty, (int) decision.getFillQty());
            execReport.setField(FixTags.LastPx, decision.getFillPrice(), 2);
            if (order.getPrice() > 0) {
                execReport.setField(FixTags.Price, order.getPrice(), 2);
            }

            session.commitMessage(execReport);
            return true;
        } catch (Exception e) {
            log.error("Error sending fill", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderRejected(FixSession session, Order order, String reason) {
        try {
            RingBufferOutgoingMessage execReport = session.tryClaimMessage(FixTags.MsgTypes.ExecutionReport);
            if (execReport == null) {
                log.error("Ring buffer full - could not send order rejected for {}", order.getOrderId());
                return false;
            }

            execReport.setField(FixTags.OrderID, String.valueOf(order.getOrderId()));
            execReport.setField(FixTags.ClOrdID, order.getClientOrderId());
            execReport.setField(FixTags.ExecID, "EXEC" + execIdCounter.getAndIncrement());
            execReport.setField(FixTags.ExecType, FixTags.EXEC_TYPE_REJECTED);
            execReport.setField(FixTags.OrdStatus, FixTags.ORD_STATUS_REJECTED);
            execReport.setField(FixTags.Symbol, order.getSymbol());
            execReport.setField(FixTags.Side, order.getSide().getFixValue());
            execReport.setField(FixTags.OrderQty, (int) order.getOriginalQty());
            execReport.setField(FixTags.CumQty, 0);
            execReport.setField(FixTags.LeavesQty, 0);
            execReport.setField(FixTags.AvgPx, 0.0, 2);
            if (reason != null) {
                execReport.setField(FixTags.Text, reason);
            }

            session.commitMessage(execReport);
            return true;
        } catch (Exception e) {
            log.error("Error sending order rejected", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderCanceled(FixSession session, Order order, String clOrdId) {
        try {
            RingBufferOutgoingMessage execReport = session.tryClaimMessage(FixTags.MsgTypes.ExecutionReport);
            if (execReport == null) {
                log.error("Ring buffer full - could not send order canceled for {}", order.getOrderId());
                return false;
            }

            execReport.setField(FixTags.OrderID, String.valueOf(order.getOrderId()));
            execReport.setField(FixTags.ClOrdID, clOrdId);
            execReport.setField(37, order.getClientOrderId()); // OrigClOrdID
            execReport.setField(FixTags.ExecID, "EXEC" + execIdCounter.getAndIncrement());
            execReport.setField(FixTags.ExecType, FixTags.EXEC_TYPE_CANCELED);
            execReport.setField(FixTags.OrdStatus, FixTags.ORD_STATUS_CANCELED);
            execReport.setField(FixTags.Symbol, order.getSymbol());
            execReport.setField(FixTags.Side, order.getSide().getFixValue());
            execReport.setField(FixTags.OrderQty, (int) order.getOriginalQty());
            execReport.setField(FixTags.CumQty, (int) order.getFilledQty());
            execReport.setField(FixTags.LeavesQty, 0);
            execReport.setField(FixTags.AvgPx, order.getAvgFillPrice(), 2);

            session.commitMessage(execReport);
            return true;
        } catch (Exception e) {
            log.error("Error sending order canceled", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderReplaced(FixSession session, Order oldOrder, Order newOrder) {
        try {
            RingBufferOutgoingMessage execReport = session.tryClaimMessage(FixTags.MsgTypes.ExecutionReport);
            if (execReport == null) {
                log.error("Ring buffer full - could not send order replaced for {}", newOrder.getOrderId());
                return false;
            }

            execReport.setField(FixTags.OrderID, String.valueOf(newOrder.getOrderId()));
            execReport.setField(FixTags.ClOrdID, newOrder.getClientOrderId());
            execReport.setField(41, oldOrder.getClientOrderId()); // OrigClOrdID
            execReport.setField(FixTags.ExecID, "EXEC" + execIdCounter.getAndIncrement());
            execReport.setField(FixTags.ExecType, FixTags.EXEC_TYPE_REPLACED);
            execReport.setField(FixTags.OrdStatus, FixTags.ORD_STATUS_REPLACED);
            execReport.setField(FixTags.Symbol, newOrder.getSymbol());
            execReport.setField(FixTags.Side, newOrder.getSide().getFixValue());
            execReport.setField(FixTags.OrderQty, (int) newOrder.getOriginalQty());
            execReport.setField(FixTags.CumQty, (int) oldOrder.getFilledQty());
            execReport.setField(FixTags.LeavesQty, (int) newOrder.getLeavesQty());
            execReport.setField(FixTags.AvgPx, oldOrder.getAvgFillPrice(), 2);
            if (newOrder.getPrice() > 0) {
                execReport.setField(FixTags.Price, newOrder.getPrice(), 2);
            }

            session.commitMessage(execReport);
            return true;
        } catch (Exception e) {
            log.error("Error sending order replaced", e);
            return false;
        }
    }

    @Override
    public String getProtocol() {
        return "FIX";
    }
}
