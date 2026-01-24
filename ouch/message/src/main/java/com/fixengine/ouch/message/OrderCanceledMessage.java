package com.fixengine.ouch.message;

/**
 * Order Canceled message - confirmation of order cancellation.
 *
 * <p>OUCH Order Canceled message format (based on OUCH 4.2):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('C')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       14      Order Token (alpha)
 * 23      4       Decrement Shares (unsigned int)
 * 27      1       Reason
 * Total: 28 bytes
 * </pre>
 */
public class OrderCanceledMessage extends OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int ORDER_TOKEN_OFFSET = 9;
    public static final int ORDER_TOKEN_LENGTH = 14;
    public static final int DECREMENT_SHARES_OFFSET = 23;
    public static final int REASON_OFFSET = 27;

    public static final int MESSAGE_LENGTH = 28;

    // Cancel Reason values
    public static final char REASON_USER_REQUESTED = 'U';
    public static final char REASON_IMMEDIATE_OR_CANCEL = 'I';
    public static final char REASON_TIMEOUT = 'T';
    public static final char REASON_SUPERVISORY = 'S';
    public static final char REASON_REGULATORY_RESTRICTION = 'D';
    public static final char REASON_SELF_MATCH_PREVENTION = 'Q';
    public static final char REASON_SYSTEM_CANCEL = 'Z';
    public static final char REASON_CROSS_CANCELLED = 'C';
    public static final char REASON_HALTED = 'H';
    public static final char REASON_OPEN_PROTECTION = 'K';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ORDER_CANCELED;
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
        return getAlpha(ORDER_TOKEN_OFFSET, ORDER_TOKEN_LENGTH);
    }

    public int getDecrementShares() {
        return getInt(DECREMENT_SHARES_OFFSET);
    }

    public long getDecrementSharesUnsigned() {
        return getUnsignedInt(DECREMENT_SHARES_OFFSET);
    }

    public char getReason() {
        return getChar(REASON_OFFSET);
    }

    public String getReasonDescription() {
        return switch (getReason()) {
            case REASON_USER_REQUESTED -> "User Requested";
            case REASON_IMMEDIATE_OR_CANCEL -> "Immediate or Cancel";
            case REASON_TIMEOUT -> "Timeout";
            case REASON_SUPERVISORY -> "Supervisory";
            case REASON_REGULATORY_RESTRICTION -> "Regulatory Restriction";
            case REASON_SELF_MATCH_PREVENTION -> "Self Match Prevention";
            case REASON_SYSTEM_CANCEL -> "System Cancel";
            case REASON_CROSS_CANCELLED -> "Cross Cancelled";
            case REASON_HALTED -> "Halted";
            case REASON_OPEN_PROTECTION -> "Open Protection";
            default -> "Unknown(" + getReason() + ")";
        };
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public OrderCanceledMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    public OrderCanceledMessage setOrderToken(String token) {
        putAlpha(ORDER_TOKEN_OFFSET, token, ORDER_TOKEN_LENGTH);
        return this;
    }

    public OrderCanceledMessage setDecrementShares(int shares) {
        putInt(DECREMENT_SHARES_OFFSET, shares);
        return this;
    }

    public OrderCanceledMessage setReason(char reason) {
        putChar(REASON_OFFSET, reason);
        return this;
    }

    @Override
    public String toString() {
        return String.format("OrderCanceled{token=%s, decrement=%d, reason=%c (%s)}",
                getOrderToken(), getDecrementShares(), getReason(), getReasonDescription());
    }
}
