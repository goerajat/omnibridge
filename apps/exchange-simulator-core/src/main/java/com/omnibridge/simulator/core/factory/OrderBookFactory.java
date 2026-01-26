package com.omnibridge.simulator.core.factory;

import com.omnibridge.simulator.core.order.DefaultOrderBook;
import com.omnibridge.simulator.core.order.OrderBook;

/**
 * Factory for creating OrderBook instances.
 */
public class OrderBookFactory {

    /**
     * Create a new default OrderBook.
     */
    public OrderBook create() {
        return new DefaultOrderBook();
    }

    /**
     * Singleton instance for convenience.
     */
    private static final OrderBookFactory INSTANCE = new OrderBookFactory();

    public static OrderBookFactory getInstance() {
        return INSTANCE;
    }
}
