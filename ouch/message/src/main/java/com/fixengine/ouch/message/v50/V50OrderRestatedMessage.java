package com.fixengine.ouch.message.v50;

import com.fixengine.ouch.message.OuchMessageType;

/**
 * OUCH 5.0 Order Restated message - notification that order details were changed by exchange.
 *
 * <p>This message is new in OUCH 5.0 and is sent when the exchange changes
 * order parameters (e.g., price adjustment due to corporate action).</p>
 *
 * <p>Message format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('R')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       4       UserRefNum (unsigned int)
 * 13      1       Restate Reason
 * 14      4       New Quantity (unsigned int)
 * 18      4       New Price (signed int, price * 10000)
 * Total: 22 bytes
 * </pre>
 */
public class V50OrderRestatedMessage extends V50OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int USER_REF_NUM_OFFSET = 9;
    public static final int RESTATE_REASON_OFFSET = 13;
    public static final int NEW_QUANTITY_OFFSET = 14;
    public static final int NEW_PRICE_OFFSET = 18;

    public static final int BASE_MESSAGE_LENGTH = 22;

    // Restate Reason values
    public static final char REASON_REFRESH_OF_DISPLAY = 'R';
    public static final char REASON_UPDATE_OF_DISPLAYED_PRICE = 'P';
    public static final char REASON_CORPORATE_ACTION = 'C';
    public static final char REASON_REGULATORY = 'G';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ORDER_RESTATED;
    }

    @Override
    public int getBaseMessageLength() {
        return BASE_MESSAGE_LENGTH;
    }

    @Override
    public int getAppendageCountOffset() {
        return -1; // Restated message doesn't support appendages
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

    public char getRestateReason() {
        return getChar(RESTATE_REASON_OFFSET);
    }

    public String getRestateReasonDescription() {
        return switch (getRestateReason()) {
            case REASON_REFRESH_OF_DISPLAY -> "Refresh of Display";
            case REASON_UPDATE_OF_DISPLAYED_PRICE -> "Update of Displayed Price";
            case REASON_CORPORATE_ACTION -> "Corporate Action";
            case REASON_REGULATORY -> "Regulatory";
            default -> "Unknown(" + getRestateReason() + ")";
        };
    }

    public int getNewQuantity() {
        return getInt(NEW_QUANTITY_OFFSET);
    }

    public long getNewQuantityUnsigned() {
        return getUnsignedInt(NEW_QUANTITY_OFFSET);
    }

    public long getNewPrice() {
        return getPrice(NEW_PRICE_OFFSET);
    }

    public double getNewPriceAsDouble() {
        return getPrice(NEW_PRICE_OFFSET) / 10000.0;
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public V50OrderRestatedMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    @Override
    public V50OrderRestatedMessage setUserRefNum(long userRefNum) {
        putInt(USER_REF_NUM_OFFSET, (int) userRefNum);
        return this;
    }

    public V50OrderRestatedMessage setRestateReason(char reason) {
        putChar(RESTATE_REASON_OFFSET, reason);
        return this;
    }

    public V50OrderRestatedMessage setNewQuantity(int quantity) {
        putInt(NEW_QUANTITY_OFFSET, quantity);
        return this;
    }

    public V50OrderRestatedMessage setNewPrice(long priceInMicros) {
        putPrice(NEW_PRICE_OFFSET, priceInMicros);
        return this;
    }

    public V50OrderRestatedMessage setNewPrice(double price) {
        putPrice(NEW_PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50OrderRestated{userRef=%d, reason=%c (%s), newQty=%d, newPrice=%.4f}",
                getUserRefNum(), getRestateReason(), getRestateReasonDescription(),
                getNewQuantity(), getNewPriceAsDouble());
    }
}
