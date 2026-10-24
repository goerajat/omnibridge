package com.omnibridge.simulator.core.order;

/**
 * Order type enum.
 */
public enum OrderType {
    MARKET('1'),
    LIMIT('2'),
    STOP('3'),
    STOP_LIMIT('4'),
    MARKET_ON_CLOSE('5'),
    WITH_OR_WITHOUT('6'),
    LIMIT_OR_BETTER('7'),
    LIMIT_WITH_OR_WITHOUT('8'),
    ON_BASIS('9'),
    ON_CLOSE('A'),
    LIMIT_ON_CLOSE('B'),
    FOREX_MARKET('C'),
    PREVIOUSLY_QUOTED('D'),
    PREVIOUSLY_INDICATED('E'),
    PEGGED('P');

    private final char fixValue;

    // values() clones the array on every call; the order path looks up by char instead
    private static final OrderType[] BY_FIX_VALUE = new OrderType[128];

    static {
        for (OrderType type : values()) {
            BY_FIX_VALUE[type.fixValue] = type;
        }
    }

    OrderType(char fixValue) {
        this.fixValue = fixValue;
    }

    public char getFixValue() {
        return fixValue;
    }

    public static OrderType fromFixValue(char value) {
        OrderType type = value < BY_FIX_VALUE.length ? BY_FIX_VALUE[value] : null;
        if (type == null) {
            throw new IllegalArgumentException("Unknown order type: " + value);
        }
        return type;
    }

    public static OrderType fromOuchType(char type) {
        return switch (type) {
            case 'Y' -> LIMIT;
            case 'N' -> MARKET;
            default -> LIMIT;
        };
    }
}
