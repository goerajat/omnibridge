package com.omnibridge.simulator.response;

import com.omnibridge.optiq.engine.OptiqSession;
import com.omnibridge.optiq.message.order.ExecutionReportMessage;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Response sender for Optiq protocol.
 */
public class OptiqResponseSender implements ResponseSender<OptiqSession> {

    private static final Logger log = LoggerFactory.getLogger(OptiqResponseSender.class);

    private static final byte ORD_STATUS_NEW = 0;
    private static final byte ORD_STATUS_PARTIAL = 1;
    private static final byte ORD_STATUS_FILLED = 2;
    private static final byte ORD_STATUS_CANCELED = 4;
    private static final byte ORD_STATUS_REJECTED = 8;

    @Override
    public boolean sendOrderAccepted(OptiqSession session, Order order) {
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReport for order {}", order.getOrderId());
            return false;
        }

        try {
            execReport.writeHeader();
            execReport.setMsgSeqNum(session.getOutboundSeqNum())
                     .setFirmId(session.getConfig().getFirmId())
                     .setClientOrderId(Long.parseLong(order.getClientOrderId()))
                     .setOrderId(order.getOrderId())
                     .setSymbolIndex(Integer.parseInt(order.getSymbol()))
                     .setOrderSide(order.getSide() == OrderSide.BUY ? (byte) 1 : (byte) 2)
                     .setOrderStatus(ORD_STATUS_NEW)
                     .setOrderPx(order.getPrice())
                     .setOrderQty((long) order.getOriginalQty())
                     .setLeavesQty((long) order.getLeavesQty())
                     .setCumQty((long) order.getFilledQty())
                     .setOegIn(System.nanoTime())
                     .setOegOut(System.nanoTime());

            session.commit(execReport);
            return true;
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReport (New)", e);
            return false;
        }
    }

    @Override
    public boolean sendFill(OptiqSession session, Order order, FillDecision decision, long execId) {
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReport for fill {}", order.getOrderId());
            return false;
        }

        try {
            byte ordStatus = order.getLeavesQty() <= 0 ? ORD_STATUS_FILLED : ORD_STATUS_PARTIAL;

            execReport.writeHeader();
            execReport.setMsgSeqNum(session.getOutboundSeqNum())
                     .setFirmId(session.getConfig().getFirmId())
                     .setClientOrderId(Long.parseLong(order.getClientOrderId()))
                     .setOrderId(order.getOrderId())
                     .setSymbolIndex(Integer.parseInt(order.getSymbol()))
                     .setOrderSide(order.getSide() == OrderSide.BUY ? (byte) 1 : (byte) 2)
                     .setOrderStatus(ordStatus)
                     .setOrderPx(order.getPrice())
                     .setOrderQty((long) order.getOriginalQty())
                     .setLeavesQty((long) order.getLeavesQty())
                     .setCumQty((long) order.getFilledQty())
                     .setOegIn(System.nanoTime())
                     .setOegOut(System.nanoTime());

            session.commit(execReport);
            return true;
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReport (Fill)", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderRejected(OptiqSession session, Order order, String reason) {
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReport for reject {}", order.getOrderId());
            return false;
        }

        try {
            execReport.writeHeader();
            execReport.setMsgSeqNum(session.getOutboundSeqNum())
                     .setFirmId(session.getConfig().getFirmId())
                     .setClientOrderId(Long.parseLong(order.getClientOrderId()))
                     .setOrderId(order.getOrderId())
                     .setOrderStatus(ORD_STATUS_REJECTED)
                     .setOegIn(System.nanoTime())
                     .setOegOut(System.nanoTime());

            session.commit(execReport);
            return true;
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReport (Reject)", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderCanceled(OptiqSession session, Order order, String clOrdId) {
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReport for cancel {}", order.getOrderId());
            return false;
        }

        try {
            execReport.writeHeader();
            execReport.setMsgSeqNum(session.getOutboundSeqNum())
                     .setFirmId(session.getConfig().getFirmId())
                     .setClientOrderId(Long.parseLong(order.getClientOrderId()))
                     .setOrderId(order.getOrderId())
                     .setOrderStatus(ORD_STATUS_CANCELED)
                     .setLeavesQty(0)
                     .setCumQty((long) order.getFilledQty())
                     .setOegIn(System.nanoTime())
                     .setOegOut(System.nanoTime());

            session.commit(execReport);
            return true;
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReport (Cancel)", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderReplaced(OptiqSession session, Order oldOrder, Order newOrder) {
        return sendOrderAccepted(session, newOrder);
    }

    @Override
    public String getProtocol() {
        return "OPTIQ";
    }
}
