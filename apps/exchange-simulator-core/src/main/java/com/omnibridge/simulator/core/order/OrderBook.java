package com.omnibridge.simulator.core.order;

import java.util.Collection;
import java.util.Optional;

/**
 * Interface for order storage and lookup.
 */
public interface OrderBook {

    /**
     * Add an order to the book.
     *
     * @param order the order to add
     * @return true if added, false if an order with same ID already exists
     */
    boolean addOrder(Order order);

    /**
     * Get an order by its exchange-assigned order ID.
     *
     * @param orderId the order ID
     * @return the order if found
     */
    Optional<Order> getOrder(long orderId);

    /**
     * Get an order by its client order ID.
     *
     * @param clientOrderId the client order ID
     * @return the order if found
     */
    Optional<Order> getOrderByClientId(String clientOrderId);

    /**
     * Get an order by its client order ID and session ID.
     *
     * @param clientOrderId the client order ID
     * @param sessionId the session ID
     * @return the order if found
     */
    Optional<Order> getOrderByClientId(String clientOrderId, String sessionId);

    /**
     * Remove an order from the book.
     *
     * @param orderId the order ID
     * @return the removed order if found
     */
    Optional<Order> removeOrder(long orderId);

    /**
     * Get all orders.
     *
     * @return collection of all orders
     */
    Collection<Order> getAllOrders();

    /**
     * Get all orders for a specific session.
     *
     * @param sessionId the session ID
     * @return collection of orders for the session
     */
    Collection<Order> getOrdersBySession(String sessionId);

    /**
     * Get all orders for a specific symbol.
     *
     * @param symbol the symbol
     * @return collection of orders for the symbol
     */
    Collection<Order> getOrdersBySymbol(String symbol);

    /**
     * Get count of all orders.
     *
     * @return order count
     */
    int getOrderCount();

    /**
     * Get count of active orders (NEW or PARTIALLY_FILLED).
     *
     * @return active order count
     */
    int getActiveOrderCount();

    /**
     * Clear all orders from the book.
     */
    void clear();
}
