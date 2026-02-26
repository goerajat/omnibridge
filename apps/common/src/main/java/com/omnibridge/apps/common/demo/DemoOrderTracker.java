package com.omnibridge.apps.common.demo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Thread-safe bounded order tracker for demo UIs.
 * When disabled, all methods are no-ops for zero overhead in latency tests.
 */
public class DemoOrderTracker {

    private final boolean enabled;
    private final int maxOrders;
    private final ConcurrentHashMap<String, TrackedOrder> orders;
    private final ConcurrentLinkedDeque<String> insertionOrder;

    public DemoOrderTracker(boolean enabled, int maxOrders) {
        this.enabled = enabled;
        this.maxOrders = maxOrders;
        this.orders = new ConcurrentHashMap<>();
        this.insertionOrder = new ConcurrentLinkedDeque<>();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void trackOrder(TrackedOrder order) {
        if (!enabled) return;

        orders.put(order.getClOrdId(), order);
        insertionOrder.addFirst(order.getClOrdId());

        // Evict oldest if over capacity
        while (insertionOrder.size() > maxOrders) {
            String oldest = insertionOrder.pollLast();
            if (oldest != null) {
                orders.remove(oldest);
            }
        }
    }

    public void addMessage(String clOrdId, MessageEvent event) {
        if (!enabled) return;

        TrackedOrder order = orders.get(clOrdId);
        if (order != null) {
            order.addMessage(event);
        }
    }

    public void updateOrder(String clOrdId, String state, double filledQty,
                            double leavesQty, double avgFillPrice) {
        if (!enabled) return;

        TrackedOrder order = orders.get(clOrdId);
        if (order != null) {
            order.update(state, filledQty, leavesQty, avgFillPrice);
        }
    }

    public TrackedOrder getOrder(String clOrdId) {
        if (!enabled) return null;
        return orders.get(clOrdId);
    }

    public List<TrackedOrder> getAllOrders() {
        if (!enabled) return Collections.emptyList();

        // Return in newest-first order
        List<TrackedOrder> result = new ArrayList<>();
        for (String clOrdId : insertionOrder) {
            TrackedOrder order = orders.get(clOrdId);
            if (order != null) {
                result.add(order);
            }
        }
        return result;
    }

    public List<TrackedOrder> getOrdersBySession(String sessionId) {
        if (!enabled) return Collections.emptyList();

        return getAllOrders().stream()
                .filter(o -> sessionId.equals(o.getSessionId()))
                .collect(Collectors.toList());
    }

    public Map<String, Object> getStats() {
        if (!enabled) return Collections.emptyMap();

        Map<String, Long> byState = new HashMap<>();
        for (TrackedOrder order : orders.values()) {
            byState.merge(order.getState(), 1L, Long::sum);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", orders.size());
        stats.put("byState", byState);
        return stats;
    }
}
