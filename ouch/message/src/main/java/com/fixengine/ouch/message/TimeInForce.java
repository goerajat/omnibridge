package com.fixengine.ouch.message;

/**
 * Time in force indicator for OUCH protocol.
 */
public enum TimeInForce {
    /** Immediate or cancel */
    IOC('0'),
    /** Market hours only */
    MARKET_HOURS('0'),
    /** Good till canceled (system hours) */
    SYSTEM_HOURS('S'),
    /** Good till market close */
    MARKET_CLOSE('M'),
    /** Good till extended hours */
    EXTENDED_HOURS('E'),
    /** Day order */
    DAY('D'),
    /** Good till date */
    GTD('G'),
    /** At the open */
    AT_OPEN('O'),
    /** At the close */
    AT_CLOSE('C');

    private final char code;

    TimeInForce(char code) {
        this.code = code;
    }

    public char getCode() {
        return code;
    }

    public byte getCodeByte() {
        return (byte) code;
    }

    public static TimeInForce fromCode(char code) {
        return switch (code) {
            case '0' -> IOC;
            case 'S' -> SYSTEM_HOURS;
            case 'M' -> MARKET_CLOSE;
            case 'E' -> EXTENDED_HOURS;
            case 'D' -> DAY;
            case 'G' -> GTD;
            case 'O' -> AT_OPEN;
            case 'C' -> AT_CLOSE;
            default -> null;
        };
    }

    public static TimeInForce fromCode(byte code) {
        return fromCode((char) (code & 0xFF));
    }
}
