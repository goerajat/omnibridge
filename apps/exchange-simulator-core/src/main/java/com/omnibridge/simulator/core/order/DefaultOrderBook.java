package com.omnibridge.simulator.core.order;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe default implementation of OrderBook using ConcurrentHashMap.
 */
public class DefaultOrderBook implements OrderBook {

    // Primary index by order ID
    private final Map<Long, Order> ordersByOrderId = new ConcurrentHashMap<>();

    // Secondary index by client order ID (may have duplicates across sessions)
    private final Map<String, Order> ordersByClientId = new ConcurrentHashMap<>();

    // Composite key index for client order ID + session ID
    private final Map<String, Order> ordersByClientIdAndSession = new ConcurrentHashMap<>();

    @Override
    public boolean addOrder(Order order) {
        if (ordersByOrderId.containsKey(order.getOrderId())) {
            return false;
        }

        ordersByOrderId.put(order.getOrderId(), order);
        ordersByClientId.put(order.getClientOrderId(), order);

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
        return Optional.ofNullable(ordersByClientId.get(clientOrderId));
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
            ordersByClientId.remove(order.getClientOrderId());
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
        ordersByClientId.clear();
        ordersByClientIdAndSession.clear();
    }

    private String makeCompositeKey(String clientOrderId, String sessionId) {
        return sessionId + ":" + clientOrderId;
    }
}
