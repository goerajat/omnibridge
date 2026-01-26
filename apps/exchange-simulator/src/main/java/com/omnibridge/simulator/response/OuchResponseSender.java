package com.omnibridge.simulator.response;

import com.omnibridge.ouch.engine.session.OuchSession;
import com.omnibridge.ouch.message.Side;
import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.order.Order;
import com.omnibridge.simulator.core.order.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Response sender for OUCH protocol.
 * Handles both OUCH 4.2 and 5.0 using the session API.
 */
public class OuchResponseSender implements ResponseSender<OuchSession> {

    private static final Logger log = LoggerFactory.getLogger(OuchResponseSender.class);

    @Override
    public boolean sendOrderAccepted(OuchSession session, Order order) {
        try {
            Side side = order.getSide() == OrderSide.BUY ? Side.BUY : Side.SELL;
            return session.sendOrderAccepted(
                    order.getClientOrderId(),
                    side,
                    order.getSymbol(),
                    (int) order.getOriginalQty(),
                    order.getPrice(),
                    order.getOrderId()
            );
        } catch (Exception e) {
            log.error("Error sending order accepted", e);
            return false;
        }
    }

    @Override
    public boolean sendFill(OuchSession session, Order order, FillDecision decision, long execId) {
        try {
            return session.sendOrderExecuted(
                    order.getClientOrderId(),
                    (int) decision.getFillQty(),
                    decision.getFillPrice(),
                    execId
            );
        } catch (Exception e) {
            log.error("Error sending fill", e);
            return false;
        }
    }

    @Override
    public boolean sendOrderRejected(OuchSession session, Order order, String reason) {
        // OUCH doesn't have a standard reject message for orders
        log.warn("OUCH order rejected not implemented: {}", reason);
        return false;
    }

    @Override
    public boolean sendOrderCanceled(OuchSession session, Order order, String clOrdId) {
        // Note: OUCH cancel confirmations are typically sent via OrderCanceled message
        // The existing session API doesn't expose a direct sendOrderCanceled method
        // In a real implementation, we would claim and send an OrderCanceledMessage
        log.debug("OUCH cancel confirmation for order: {}", clOrdId);
        return true; // Simplified - actual implementation would send OrderCanceledMessage
    }

    @Override
    public boolean sendOrderReplaced(OuchSession session, Order oldOrder, Order newOrder) {
        // OUCH typically just sends a new Order Accepted for the replacement
        return sendOrderAccepted(session, newOrder);
    }

    @Override
    public String getProtocol() {
        return "OUCH";
    }
}
