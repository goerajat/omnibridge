package com.omnibridge.simulator.adapter;

import com.omnibridge.simulator.core.order.Order;

/**
 * Adapter interface for converting protocol-specific messages to Order objects.
 *
 * @param <M> the protocol message type
 * @param <S> the session type
 */
public interface OrderAdapter<M, S> {

    /**
     * Convert a protocol message to an Order.
     *
     * @param message the protocol message
     * @param session the session that received the message
     * @param orderId the exchange-assigned order ID
     * @return the Order
     */
    Order adapt(M message, S session, long orderId);

    /**
     * Get the protocol name for this adapter.
     */
    String getProtocol();
}
