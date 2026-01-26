package com.omnibridge.simulator.core.fill;

/**
 * Result of fill engine evaluation for an order.
 */
public class FillDecision {

    public enum Type {
        NO_FILL,
        FULL_FILL,
        PARTIAL_FILL
    }

    private final Type type;
    private final double fillQty;
    private final double fillPrice;
    private final String reason;

    private FillDecision(Type type, double fillQty, double fillPrice, String reason) {
        this.type = type;
        this.fillQty = fillQty;
        this.fillPrice = fillPrice;
        this.reason = reason;
    }

    public Type getType() {
        return type;
    }

    public double getFillQty() {
        return fillQty;
    }

    public double getFillPrice() {
        return fillPrice;
    }

    public String getReason() {
        return reason;
    }

    public boolean shouldFill() {
        return type != Type.NO_FILL;
    }

    public boolean isFullFill() {
        return type == Type.FULL_FILL;
    }

    public boolean isPartialFill() {
        return type == Type.PARTIAL_FILL;
    }

    // Factory methods

    public static FillDecision noFill() {
        return new FillDecision(Type.NO_FILL, 0, 0, null);
    }

    public static FillDecision noFill(String reason) {
        return new FillDecision(Type.NO_FILL, 0, 0, reason);
    }

    public static FillDecision fullFill(double qty, double price) {
        return new FillDecision(Type.FULL_FILL, qty, price, null);
    }

    public static FillDecision partialFill(double fillQty, double price) {
        return new FillDecision(Type.PARTIAL_FILL, fillQty, price, null);
    }

    @Override
    public String toString() {
        return String.format("FillDecision[type=%s, qty=%.0f, price=%.2f]", type, fillQty, fillPrice);
    }
}
