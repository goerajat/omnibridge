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

    OrderType(char fixValue) {
        this.fixValue = fixValue;
    }

    public char getFixValue() {
        return fixValue;
    }

    public static OrderType fromFixValue(char value) {
        for (OrderType type : values()) {
            if (type.fixValue == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown order type: " + value);
    }

    public static OrderType fromOuchType(char type) {
        return switch (type) {
            case 'Y' -> LIMIT;
            case 'N' -> MARKET;
            default -> LIMIT;
        };
    }
}
