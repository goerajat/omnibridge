package com.omnibridge.simulator.response;

import com.omnibridge.ilink3.engine.ILink3Session;
import com.omnibridge.ilink3.message.order.ExecutionReportNewMessage;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.order.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Response sender for iLink3 protocol.
 */
public class ILink3ResponseSender implements ResponseSender<ILink3Session> {

    private static final Logger log = LoggerFactory.getLogger(ILink3ResponseSender.class);

    @Override
    public boolean sendOrderAccepted(ILink3Session session, Order order) {
        ExecutionReportNewMessage execReport = session.tryClaim(ExecutionReportNewMessage.class);
        if (execReport == null) {
            log.error("Could not claim ExecutionReportNew for order {}", order.getOrderId());
            return false;
        }

        try {
            execReport.writeHeader();
            execReport.setSeqNum((int) session.getOutboundSeqNum())
                     .setUuid(session.getUuid())
                     .setClOrdId(order.getClientOrderId())
                     .setOrderId(order.getOrderId())
                     .setPrice(order.getPrice())
                     .setOrderQty((int) order.getOriginalQty())
                     .setLeavesQty((int) order.getLeavesQty())
                     .setCumQty((int) order.getFilledQty())
                     .setTransactTime(System.nanoTime())
                     .setSendingTimeEpoch(System.nanoTime());

            session.commit(execReport);
            return true;
        } catch (Exception e) {
            session.abort(execReport);
            log.error("Error sending ExecutionReportNew", e);
            return false;
        }
    }

    @Override
    public boolean sendFill(ILink3Session session, Order order, FillDecision decision, long execId) {
        // For simplicity, we reuse ExecutionReportNew with updated cumQty/leavesQty
        // A full implementation would use a separate ExecutionReportFill message
        return sendOrderAccepted(session, order);
    }

    @Override
    public boolean sendOrderRejected(ILink3Session session, Order order, String reason) {
        log.warn("iLink3 order rejected not fully implemented: {}", reason);
        return false;
    }

    @Override
    public boolean sendOrderCanceled(ILink3Session session, Order order, String clOrdId) {
        // Simplified - a full implementation would use ExecutionReportCancel
        log.debug("iLink3 cancel confirmation for order: {}", clOrdId);
        return true;
    }

    @Override
    public boolean sendOrderReplaced(ILink3Session session, Order oldOrder, Order newOrder) {
        return sendOrderAccepted(session, newOrder);
    }

    @Override
    public String getProtocol() {
        return "ILINK3";
    }
}
