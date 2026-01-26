package com.omnibridge.ouch.message.v50;

import com.omnibridge.ouch.message.OuchMessageType;

/**
 * OUCH 5.0 Order Rejected message - notification that an order was rejected.
 *
 * <p>Message format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('J')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       4       UserRefNum (unsigned int)
 * 13      1       Reject Reason
 * Total: 14 bytes
 * </pre>
 */
public class V50OrderRejectedMessage extends V50OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int USER_REF_NUM_OFFSET = 9;
    public static final int REJECT_REASON_OFFSET = 13;

    public static final int BASE_MESSAGE_LENGTH = 14;

    // Reject Reason values
    public static final char REASON_TEST_MODE = 'T';
    public static final char REASON_HALTED = 'H';
    public static final char REASON_SHARES_EXCEEDS_CONFIGURED_SAFETY = 'Z';
    public static final char REASON_INVALID_STOCK = 'S';
    public static final char REASON_INVALID_DISPLAY = 'D';
    public static final char REASON_CLOSED = 'C';
    public static final char REASON_REQUESTED_FIRM_NOT_AUTHORIZED = 'L';
    public static final char REASON_OUTSIDE_PERMITTED_TIMES = 'O';
    public static final char REASON_NOT_CLOSE_TIME = 'I';
    public static final char REASON_NOT_OPEN_TIME = 'M';
    public static final char REASON_INVALID_PRICE = 'X';
    public static final char REASON_INVALID_MIN_QTY = 'N';
    public static final char REASON_CANNOT_TRADE_THAT_STOCK = 'W';
    public static final char REASON_NOT_AUTHORIZED_TO_CANCEL = 'a';
    public static final char REASON_INVALID_ISO = 'b';
    public static final char REASON_INVALID_CAPACITY = 'c';
    public static final char REASON_MAX_ORDER_SIZE_EXCEEDED = 'F';
    public static final char REASON_RISK_MGT_MPID_FIRM = 'f';
    public static final char REASON_RISK_MGT_SYMBOL_STATE = 'm';
    public static final char REASON_INVALID_APPENDAGE = 'A';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ORDER_REJECTED;
    }

    @Override
    public int getBaseMessageLength() {
        return BASE_MESSAGE_LENGTH;
    }

    @Override
    public int getAppendageCountOffset() {
        return -1; // Rejected message doesn't support appendages
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

    public char getRejectReason() {
        return getChar(REJECT_REASON_OFFSET);
    }

    public String getRejectReasonDescription() {
        return switch (getRejectReason()) {
            case REASON_TEST_MODE -> "Test Mode";
            case REASON_HALTED -> "Halted";
            case REASON_SHARES_EXCEEDS_CONFIGURED_SAFETY -> "Shares Exceeds Safety Limit";
            case REASON_INVALID_STOCK -> "Invalid Stock";
            case REASON_INVALID_DISPLAY -> "Invalid Display";
            case REASON_CLOSED -> "Market Closed";
            case REASON_REQUESTED_FIRM_NOT_AUTHORIZED -> "Firm Not Authorized";
            case REASON_OUTSIDE_PERMITTED_TIMES -> "Outside Permitted Times";
            case REASON_NOT_CLOSE_TIME -> "Not Close Time";
            case REASON_NOT_OPEN_TIME -> "Not Open Time";
            case REASON_INVALID_PRICE -> "Invalid Price";
            case REASON_INVALID_MIN_QTY -> "Invalid Minimum Quantity";
            case REASON_CANNOT_TRADE_THAT_STOCK -> "Cannot Trade Stock";
            case REASON_NOT_AUTHORIZED_TO_CANCEL -> "Not Authorized to Cancel";
            case REASON_INVALID_ISO -> "Invalid ISO";
            case REASON_INVALID_CAPACITY -> "Invalid Capacity";
            case REASON_MAX_ORDER_SIZE_EXCEEDED -> "Max Order Size Exceeded";
            case REASON_RISK_MGT_MPID_FIRM -> "Risk Management MPID/Firm";
            case REASON_RISK_MGT_SYMBOL_STATE -> "Risk Management Symbol State";
            case REASON_INVALID_APPENDAGE -> "Invalid Appendage";
            default -> "Unknown(" + getRejectReason() + ")";
        };
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public V50OrderRejectedMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    @Override
    public V50OrderRejectedMessage setUserRefNum(long userRefNum) {
        putInt(USER_REF_NUM_OFFSET, (int) userRefNum);
        return this;
    }

    public V50OrderRejectedMessage setRejectReason(char reason) {
        putChar(REJECT_REASON_OFFSET, reason);
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50OrderRejected{userRef=%d, reason=%c (%s)}",
                getUserRefNum(), getRejectReason(), getRejectReasonDescription());
    }
}
