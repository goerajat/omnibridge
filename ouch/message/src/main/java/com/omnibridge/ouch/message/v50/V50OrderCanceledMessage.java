package com.omnibridge.ouch.message.v50;

import com.omnibridge.ouch.message.OuchMessageType;

/**
 * OUCH 5.0 Order Canceled message - confirmation of order cancellation.
 *
 * <p>Message format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('C')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       4       UserRefNum (unsigned int)
 * 13      4       Decrement Quantity (unsigned int)
 * 17      1       Reason
 * Total: 18 bytes
 * </pre>
 */
public class V50OrderCanceledMessage extends V50OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int USER_REF_NUM_OFFSET = 9;
    public static final int DECREMENT_QUANTITY_OFFSET = 13;
    public static final int REASON_OFFSET = 17;

    public static final int BASE_MESSAGE_LENGTH = 18;

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
    public static final char REASON_MASS_CANCEL = 'M';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ORDER_CANCELED;
    }

    @Override
    public int getBaseMessageLength() {
        return BASE_MESSAGE_LENGTH;
    }

    @Override
    public int getAppendageCountOffset() {
        return -1; // Canceled message doesn't support appendages
    }

    // =====================================================
    // Field getters (for receiving/reading)
    // =====================================================

    public long getTimestamp() {
        return getLong(TIMESTAMP_OFFSET);
    }

    @Override
    public long getUserRefNum() {
        return getUnsignedInt(USER_REF_NUM_OFFSET);
    }

    public int getDecrementQuantity() {
        return getInt(DECREMENT_QUANTITY_OFFSET);
    }

    public long getDecrementQuantityUnsigned() {
        return getUnsignedInt(DECREMENT_QUANTITY_OFFSET);
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
            case REASON_MASS_CANCEL -> "Mass Cancel";
            default -> "Unknown(" + getReason() + ")";
        };
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public V50OrderCanceledMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    @Override
    public V50OrderCanceledMessage setUserRefNum(long userRefNum) {
        putInt(USER_REF_NUM_OFFSET, (int) userRefNum);
        return this;
    }

    public V50OrderCanceledMessage setDecrementQuantity(int quantity) {
        putInt(DECREMENT_QUANTITY_OFFSET, quantity);
        return this;
    }

    public V50OrderCanceledMessage setReason(char reason) {
        putChar(REASON_OFFSET, reason);
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50OrderCanceled{userRef=%d, decrement=%d, reason=%c (%s)}",
                getUserRefNum(), getDecrementQuantity(), getReason(), getReasonDescription());
    }
}
