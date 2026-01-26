package com.omnibridge.simulator.response;

import com.omnibridge.simulator.core.fill.FillDecision;
import com.omnibridge.simulator.core.order.Order;

/**
 * Interface for sending responses back to clients via protocol-specific mechanisms.
 *
 * @param <S> the session type
 */
public interface ResponseSender<S> {

    /**
     * Send an order accepted/new acknowledgment.
     *
     * @param session the session to send on
     * @param order the order that was accepted
     * @return true if sent successfully
     */
    boolean sendOrderAccepted(S session, Order order);

    /**
     * Send a fill/execution report.
     *
     * @param session the session to send on
     * @param order the order that was filled
     * @param decision the fill decision with qty and price
     * @param execId the execution ID
     * @return true if sent successfully
     */
    boolean sendFill(S session, Order order, FillDecision decision, long execId);

    /**
     * Send an order rejected response.
     *
     * @param session the session to send on
     * @param order the order that was rejected
     * @param reason the rejection reason
     * @return true if sent successfully
     */
    boolean sendOrderRejected(S session, Order order, String reason);

    /**
     * Send an order canceled response.
     *
     * @param session the session to send on
     * @param order the order that was canceled
     * @param clOrdId the cancel request's client order ID
     * @return true if sent successfully
     */
    boolean sendOrderCanceled(S session, Order order, String clOrdId);

    /**
     * Send an order replaced response.
     *
     * @param session the session to send on
     * @param oldOrder the order that was replaced
     * @param newOrder the new replacement order
     * @return true if sent successfully
     */
    boolean sendOrderReplaced(S session, Order oldOrder, Order newOrder);

    /**
     * Get the protocol name for this sender.
     */
    String getProtocol();
}
