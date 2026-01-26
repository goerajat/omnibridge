package com.omnibridge.simulator.core.order;

/**
 * Order side enum.
 */
public enum OrderSide {
    BUY('1'),
    SELL('2'),
    BUY_MINUS('3'),
    SELL_PLUS('4'),
    SELL_SHORT('5'),
    SELL_SHORT_EXEMPT('6');

    private final char fixValue;

    OrderSide(char fixValue) {
        this.fixValue = fixValue;
    }

    public char getFixValue() {
        return fixValue;
    }

    public static OrderSide fromFixValue(char value) {
        return switch (value) {
            case '1' -> BUY;
            case '2' -> SELL;
            case '3' -> BUY_MINUS;
            case '4' -> SELL_PLUS;
            case '5' -> SELL_SHORT;
            case '6' -> SELL_SHORT_EXEMPT;
            default -> throw new IllegalArgumentException("Unknown side: " + value);
        };
    }

    public static OrderSide fromOuchSide(char side) {
        return side == 'B' ? BUY : SELL;
    }

    public static OrderSide fromByte(byte side) {
        return side == 1 ? BUY : SELL;
    }
}
