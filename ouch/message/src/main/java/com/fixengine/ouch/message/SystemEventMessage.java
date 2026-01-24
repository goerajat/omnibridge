package com.fixengine.ouch.message;

/**
 * System Event message - notification of market-wide events.
 *
 * <p>OUCH System Event message format (based on OUCH 4.2):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('S')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       1       Event Code
 * Total: 10 bytes
 * </pre>
 */
public class SystemEventMessage extends OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int EVENT_CODE_OFFSET = 9;

    public static final int MESSAGE_LENGTH = 10;

    // Event Code values
    public static final char EVENT_START_OF_DAY = 'S';
    public static final char EVENT_START_OF_SYSTEM_HOURS = 'C';
    public static final char EVENT_START_OF_MARKET_HOURS = 'Q';
    public static final char EVENT_END_OF_MARKET_HOURS = 'M';
    public static final char EVENT_END_OF_SYSTEM_HOURS = 'E';
    public static final char EVENT_END_OF_DAY = 'D';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.SYSTEM_EVENT;
    }

    @Override
    public int getMessageLength() {
        return MESSAGE_LENGTH;
    }

    // =====================================================
    // Field getters (for receiving/reading)
    // =====================================================

    public long getTimestamp() {
        return getLong(TIMESTAMP_OFFSET);
    }

    public String getOrderToken() {
        return null; // System events don't have order tokens
    }

    public char getEventCode() {
        return getChar(EVENT_CODE_OFFSET);
    }

    public String getEventDescription() {
        return switch (getEventCode()) {
            case EVENT_START_OF_DAY -> "Start of Day";
            case EVENT_START_OF_SYSTEM_HOURS -> "Start of System Hours";
            case EVENT_START_OF_MARKET_HOURS -> "Start of Market Hours";
            case EVENT_END_OF_MARKET_HOURS -> "End of Market Hours";
            case EVENT_END_OF_SYSTEM_HOURS -> "End of System Hours";
            case EVENT_END_OF_DAY -> "End of Day";
            default -> "Unknown(" + getEventCode() + ")";
        };
    }

    public boolean isStartOfDay() {
        return getEventCode() == EVENT_START_OF_DAY;
    }

    public boolean isEndOfDay() {
        return getEventCode() == EVENT_END_OF_DAY;
    }

    public boolean isStartOfMarketHours() {
        return getEventCode() == EVENT_START_OF_MARKET_HOURS;
    }

    public boolean isEndOfMarketHours() {
        return getEventCode() == EVENT_END_OF_MARKET_HOURS;
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public SystemEventMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    public SystemEventMessage setEventCode(char eventCode) {
        putChar(EVENT_CODE_OFFSET, eventCode);
        return this;
    }

    @Override
    public String toString() {
        return String.format("SystemEvent{event=%c (%s), timestamp=%d}",
                getEventCode(), getEventDescription(), getTimestamp());
    }
}
