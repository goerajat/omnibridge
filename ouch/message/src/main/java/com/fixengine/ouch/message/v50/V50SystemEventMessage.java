package com.fixengine.ouch.message.v50;

import com.fixengine.ouch.message.OuchMessageType;

/**
 * OUCH 5.0 System Event message - notification of market-wide events.
 *
 * <p>Message format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('S')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       1       Event Code
 * Total: 10 bytes
 * </pre>
 */
public class V50SystemEventMessage extends V50OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int EVENT_CODE_OFFSET = 9;

    public static final int BASE_MESSAGE_LENGTH = 10;

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
    public int getBaseMessageLength() {
        return BASE_MESSAGE_LENGTH;
    }

    @Override
    public int getAppendageCountOffset() {
        return -1; // System event doesn't support appendages
    }

    @Override
    public long getUserRefNum() {
        return 0; // System events don't have user ref nums
    }

    @Override
    public V50SystemEventMessage setUserRefNum(long userRefNum) {
        return this; // No-op for system events
    }

    // =====================================================
    // Field getters (for receiving/reading)
    // =====================================================

    public long getTimestamp() {
        return getLong(TIMESTAMP_OFFSET);
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

    public V50SystemEventMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    public V50SystemEventMessage setEventCode(char eventCode) {
        putChar(EVENT_CODE_OFFSET, eventCode);
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50SystemEvent{event=%c (%s), timestamp=%d}",
                getEventCode(), getEventDescription(), getTimestamp());
    }
}
