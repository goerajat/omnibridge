package com.omnibridge.simulator.core.order;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Protocol-agnostic order representation.
 * <p>
 * This class is thread-safe and maintains all state needed to track an order
 * across its lifecycle. The order can be modified via fill(), cancel(), and
 * replace() methods.
 */
public class Order {

    private final long orderId;
    private final String clientOrderId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType orderType;
    private final double originalQty;
    private final double price;
    private final String sessionId;
    private final String protocol;
    private final long createTime;

    // Mutable state
    private final AtomicReference<OrderState> state;
    private volatile double filledQty;
    private volatile double leavesQty;
    private volatile double avgFillPrice;
    private volatile long lastUpdateTime;
    private volatile String rejectReason;

    private Order(Builder builder) {
        this.orderId = builder.orderId;
        this.clientOrderId = builder.clientOrderId;
        this.symbol = builder.symbol;
        this.side = builder.side;
        this.orderType = builder.orderType;
        this.originalQty = builder.qty;
        this.price = builder.price;
        this.sessionId = builder.sessionId;
        this.protocol = builder.protocol;
        this.createTime = System.currentTimeMillis();
        this.state = new AtomicReference<>(OrderState.PENDING);
        this.filledQty = 0;
        this.leavesQty = builder.qty;
        this.avgFillPrice = 0;
        this.lastUpdateTime = this.createTime;
    }

    // ==================== Getters ====================

    public long getOrderId() {
        return orderId;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public double getOriginalQty() {
        return originalQty;
    }

    public double getPrice() {
        return price;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProtocol() {
        return protocol;
    }

    public long getCreateTime() {
        return createTime;
    }

    public OrderState getState() {
        return state.get();
    }

    public double getFilledQty() {
        return filledQty;
    }

    public double getLeavesQty() {
        return leavesQty;
    }

    public double getAvgFillPrice() {
        return avgFillPrice;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    // ==================== State Modifiers ====================

    /**
     * Mark the order as accepted (NEW state).
     *
     * @return true if state changed, false if order was not in PENDING state
     */
    public boolean accept() {
        boolean changed = state.compareAndSet(OrderState.PENDING, OrderState.NEW);
        if (changed) {
            lastUpdateTime = System.currentTimeMillis();
        }
        return changed;
    }

    /**
     * Apply a fill to this order.
     *
     * @param fillQty the quantity filled
     * @param fillPrice the fill price
     * @return true if fill applied, false if order not in fillable state
     */
    public synchronized boolean fill(double fillQty, double fillPrice) {
        OrderState current = state.get();
        if (current != OrderState.NEW && current != OrderState.PARTIALLY_FILLED) {
            return false;
        }

        // Calculate new average price
        double totalValue = (avgFillPrice * filledQty) + (fillPrice * fillQty);
        filledQty += fillQty;
        avgFillPrice = totalValue / filledQty;
        leavesQty = originalQty - filledQty;
        lastUpdateTime = System.currentTimeMillis();

        // Update state
        if (leavesQty <= 0) {
            leavesQty = 0;
            state.set(OrderState.FILLED);
        } else {
            state.set(OrderState.PARTIALLY_FILLED);
        }

        return true;
    }

    /**
     * Cancel this order.
     *
     * @return true if canceled, false if order not in cancelable state
     */
    public boolean cancel() {
        OrderState current = state.get();
        if (current == OrderState.NEW || current == OrderState.PARTIALLY_FILLED) {
            state.set(OrderState.CANCELED);
            lastUpdateTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    /**
     * Reject this order.
     *
     * @param reason the rejection reason
     * @return true if rejected, false if order not in rejectable state
     */
    public boolean reject(String reason) {
        OrderState current = state.get();
        if (current == OrderState.PENDING) {
            this.rejectReason = reason;
            state.set(OrderState.REJECTED);
            lastUpdateTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    /**
     * Mark this order as replaced.
     *
     * @return true if marked as replaced, false if order not in replaceable state
     */
    public boolean markReplaced() {
        OrderState current = state.get();
        if (current == OrderState.NEW || current == OrderState.PARTIALLY_FILLED) {
            state.set(OrderState.REPLACED);
            lastUpdateTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long orderId;
        private String clientOrderId;
        private String symbol;
        private OrderSide side = OrderSide.BUY;
        private OrderType orderType = OrderType.LIMIT;
        private double qty;
        private double price;
        private String sessionId;
        private String protocol;

        public Builder orderId(long orderId) {
            this.orderId = orderId;
            return this;
        }

        public Builder clientOrderId(String clientOrderId) {
            this.clientOrderId = clientOrderId;
            return this;
        }

        public Builder symbol(String symbol) {
            this.symbol = symbol;
            return this;
        }

        public Builder side(OrderSide side) {
            this.side = side;
            return this;
        }

        public Builder orderType(OrderType orderType) {
            this.orderType = orderType;
            return this;
        }

        public Builder qty(double qty) {
            this.qty = qty;
            return this;
        }

        public Builder price(double price) {
            this.price = price;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Order build() {
            return new Order(this);
        }
    }

    @Override
    public String toString() {
        return String.format("Order[id=%d, clOrdId=%s, symbol=%s, side=%s, qty=%.0f, price=%.2f, state=%s, filled=%.0f]",
                orderId, clientOrderId, symbol, side, originalQty, price, state.get(), filledQty);
    }
}
