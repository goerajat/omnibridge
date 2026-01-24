package com.fixengine.ouch.message;

/**
 * Order side indicator for OUCH protocol.
 */
public enum Side {
    /** Buy order */
    BUY('B'),
    /** Sell order */
    SELL('S'),
    /** Sell short */
    SELL_SHORT('T'),
    /** Sell short exempt */
    SELL_SHORT_EXEMPT('E');

    private final char code;

    Side(char code) {
        this.code = code;
    }

    public char getCode() {
        return code;
    }

    public byte getCodeByte() {
        return (byte) code;
    }

    public static Side fromCode(char code) {
        return switch (code) {
            case 'B' -> BUY;
            case 'S' -> SELL;
            case 'T' -> SELL_SHORT;
            case 'E' -> SELL_SHORT_EXEMPT;
            default -> null;
        };
    }

    public static Side fromCode(byte code) {
        return fromCode((char) (code & 0xFF));
    }
}
