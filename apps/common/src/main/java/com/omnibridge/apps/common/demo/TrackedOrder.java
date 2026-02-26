package com.omnibridge.apps.common.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks an order's identity, state, fill progress, and FIX message history.
 */
public class TrackedOrder {

    private final String clOrdId;
    private final long orderId;
    private final String symbol;
    private final String side;
    private final String orderType;
    private final double qty;
    private final double price;
    private final String sessionId;
    private final long createTime;
    private final List<MessageEvent> messages;

    private volatile String state;
    private volatile double filledQty;
    private volatile double leavesQty;
    private volatile double avgFillPrice;

    public TrackedOrder(String clOrdId, long orderId, String symbol, String side,
                        String orderType, double qty, double price, String state,
                        String sessionId) {
        this.clOrdId = clOrdId;
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.orderType = orderType;
        this.qty = qty;
        this.price = price;
        this.state = state;
        this.sessionId = sessionId;
        this.createTime = System.currentTimeMillis();
        this.filledQty = 0;
        this.leavesQty = qty;
        this.avgFillPrice = 0;
        this.messages = Collections.synchronizedList(new ArrayList<>());
    }

    public void update(String state, double filledQty, double leavesQty, double avgFillPrice) {
        this.state = state;
        this.filledQty = filledQty;
        this.leavesQty = leavesQty;
        this.avgFillPrice = avgFillPrice;
    }

    public void addMessage(MessageEvent event) {
        messages.add(event);
    }

    // Getters

    public String getClOrdId() {
        return clOrdId;
    }

    public long getOrderId() {
        return orderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getSide() {
        return side;
    }

    public String getOrderType() {
        return orderType;
    }

    public double getQty() {
        return qty;
    }

    public double getPrice() {
        return price;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getCreateTime() {
        return createTime;
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

    public List<MessageEvent> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }
}
