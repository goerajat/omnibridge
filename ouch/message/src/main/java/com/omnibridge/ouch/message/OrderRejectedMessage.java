package com.omnibridge.ouch.message;

/**
 * Order Rejected message - notification that an order was rejected.
 *
 * <p>OUCH Order Rejected message format (based on OUCH 4.2):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('J')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       14      Order Token (alpha)
 * 23      1       Reject Reason
 * Total: 24 bytes
 * </pre>
 */
public class OrderRejectedMessage extends OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int ORDER_TOKEN_OFFSET = 9;
    public static final int ORDER_TOKEN_LENGTH = 14;
    public static final int REJECT_REASON_OFFSET = 23;

    public static final int MESSAGE_LENGTH = 24;

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

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ORDER_REJECTED;
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
            default -> "Unknown(" + getRejectReason() + ")";
        };
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public OrderRejectedMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    public OrderRejectedMessage setOrderToken(String token) {
        putAlpha(ORDER_TOKEN_OFFSET, token, ORDER_TOKEN_LENGTH);
        return this;
    }

    public OrderRejectedMessage setRejectReason(char reason) {
        putChar(REJECT_REASON_OFFSET, reason);
        return this;
    }

    @Override
    public String toString() {
        return String.format("OrderRejected{token=%s, reason=%c (%s)}",
                getOrderToken(), getRejectReason(), getRejectReasonDescription());
    }
}
