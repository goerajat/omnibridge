package com.omnibridge.simulator.core.order;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe default implementation of OrderBook using ConcurrentHashMap.
 *
 * <p>Orders are added on the event loop and stay in the book after they fill. A
 * ConcurrentHashMap that grows copies all its entries inside the put that crosses the
 * threshold (tens of ms at 100k orders), so the maps are allocated for the expected number
 * of orders when the book is created.</p>
 */
public class DefaultOrderBook implements OrderBook {

    /** Orders the book holds before any of its maps has to grow. */
    public static final int DEFAULT_EXPECTED_ORDERS = 1_000_000;

    // Primary index by order ID
    private final Map<Long, Order> ordersByOrderId;

    // Index by client order ID alone, for the demo REST routes (which have no session);
    // null unless enabled. The last order wins when sessions reuse a ClOrdID.
    private final Map<String, Order> ordersByClientId;

    // Composite key index for client order ID + session ID (cancel/replace lookups)
    private final Map<String, Order> ordersByClientIdAndSession;

    /**
     * A book for {@link #DEFAULT_EXPECTED_ORDERS} orders, with the ClOrdID-only index.
     */
    public DefaultOrderBook() {
        this(DEFAULT_EXPECTED_ORDERS, true);
    }

    /**
     * @param expectedOrders orders the book holds before a map has to grow
     * @param indexByClientIdAlone keep the ClOrdID-only index used by
     *        {@link #getOrderByClientId(String)}; without it that lookup scans the book
     */
    public DefaultOrderBook(int expectedOrders, boolean indexByClientIdAlone) {
        this.ordersByOrderId = allocated(expectedOrders, -1L);
        this.ordersByClientId = indexByClientIdAlone ? allocated(expectedOrders, "") : null;
        this.ordersByClientIdAndSession = allocated(expectedOrders, "");
    }

    /**
     * A map sized for the given entries whose table already exists: ConcurrentHashMap
     * allocates it on the first put, which would be the first order, on the event loop.
     */
    private static <K> Map<K, Order> allocated(int entries, K probe) {
        ConcurrentHashMap<K, Order> map = new ConcurrentHashMap<>(entries);
        map.put(probe, PROBE);
        map.remove(probe);
        return map;
    }

    private static final Order PROBE = Order.builder().orderId(-1L).clientOrderId("").build();

    @Override
    public boolean addOrder(Order order) {
        if (ordersByOrderId.containsKey(order.getOrderId())) {
            return false;
        }

        ordersByOrderId.put(order.getOrderId(), order);
        if (ordersByClientId != null) {
            ordersByClientId.put(order.getClientOrderId(), order);
        }

        String compositeKey = makeCompositeKey(order.getClientOrderId(), order.getSessionId());
        ordersByClientIdAndSession.put(compositeKey, order);

        return true;
    }

    @Override
    public Optional<Order> getOrder(long orderId) {
        return Optional.ofNullable(ordersByOrderId.get(orderId));
    }

    @Override
    public Optional<Order> getOrderByClientId(String clientOrderId) {
        if (ordersByClientId != null) {
            return Optional.ofNullable(ordersByClientId.get(clientOrderId));
        }
        // No index outside demo mode: scan (admin use only, never on the order path)
        Order found = null;
        for (Order o : ordersByOrderId.values()) {
            if (clientOrderId.equals(o.getClientOrderId())) {
                found = o;
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public Optional<Order> getOrderByClientId(String clientOrderId, String sessionId) {
        String compositeKey = makeCompositeKey(clientOrderId, sessionId);
        return Optional.ofNullable(ordersByClientIdAndSession.get(compositeKey));
    }

    @Override
    public Optional<Order> removeOrder(long orderId) {
        Order order = ordersByOrderId.remove(orderId);
        if (order != null) {
            if (ordersByClientId != null) {
                ordersByClientId.remove(order.getClientOrderId(), order);
            }
            String compositeKey = makeCompositeKey(order.getClientOrderId(), order.getSessionId());
            ordersByClientIdAndSession.remove(compositeKey);
        }
        return Optional.ofNullable(order);
    }

    @Override
    public Collection<Order> getAllOrders() {
        return ordersByOrderId.values();
    }

    @Override
    public Collection<Order> getOrdersBySession(String sessionId) {
        return ordersByOrderId.values().stream()
                .filter(o -> sessionId.equals(o.getSessionId()))
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Order> getOrdersBySymbol(String symbol) {
        return ordersByOrderId.values().stream()
                .filter(o -> symbol.equals(o.getSymbol()))
                .collect(Collectors.toList());
    }

    @Override
    public int getOrderCount() {
        return ordersByOrderId.size();
    }

    @Override
    public int getActiveOrderCount() {
        return (int) ordersByOrderId.values().stream()
                .filter(o -> o.getState() == OrderState.NEW || o.getState() == OrderState.PARTIALLY_FILLED)
                .count();
    }

    @Override
    public void clear() {
        ordersByOrderId.clear();
        if (ordersByClientId != null) {
            ordersByClientId.clear();
        }
        ordersByClientIdAndSession.clear();
    }

    private String makeCompositeKey(String clientOrderId, String sessionId) {
        return sessionId + ":" + clientOrderId;
    }
}
