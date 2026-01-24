package com.fixengine.ouch.message.v50;

import com.fixengine.ouch.message.OuchMessageType;

/**
 * OUCH 5.0 Replace Order message - replace an existing order.
 *
 * <p>Message format (variable length):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('U')
 * 1       4       Existing UserRefNum (unsigned int)
 * 5       4       Replacement UserRefNum (unsigned int)
 * 9       4       Quantity (unsigned int)
 * 13      4       Price (signed int, price * 10000)
 * 17      4       Time In Force (unsigned int)
 * 21      1       Display
 * 22      4       Minimum Quantity (unsigned int)
 * 26      1       Appendage Count
 * 27+     var     Appendages
 * Base: 27 bytes
 * </pre>
 */
public class V50ReplaceOrderMessage extends V50OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int EXISTING_USER_REF_OFFSET = 1;
    public static final int REPLACEMENT_USER_REF_OFFSET = 5;
    public static final int QUANTITY_OFFSET = 9;
    public static final int PRICE_OFFSET = 13;
    public static final int TIME_IN_FORCE_OFFSET = 17;
    public static final int DISPLAY_OFFSET = 21;
    public static final int MIN_QUANTITY_OFFSET = 22;
    public static final int APPENDAGE_COUNT_OFFSET = 26;

    public static final int BASE_MESSAGE_LENGTH = 27;

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.REPLACE_ORDER;
    }

    @Override
    public int getBaseMessageLength() {
        return BASE_MESSAGE_LENGTH;
    }

    @Override
    public int getAppendageCountOffset() {
        return APPENDAGE_COUNT_OFFSET;
    }

    // =====================================================
    // Field getters (for receiving/reading)
    // =====================================================

    public long getExistingUserRefNum() {
        return getUnsignedInt(EXISTING_USER_REF_OFFSET);
    }

    @Override
    public long getUserRefNum() {
        return getExistingUserRefNum();
    }

    public long getReplacementUserRefNum() {
        return getUnsignedInt(REPLACEMENT_USER_REF_OFFSET);
    }

    public int getQuantity() {
        return getInt(QUANTITY_OFFSET);
    }

    public long getQuantityUnsigned() {
        return getUnsignedInt(QUANTITY_OFFSET);
    }

    public long getPrice() {
        return getPrice(PRICE_OFFSET);
    }

    public double getPriceAsDouble() {
        return getPrice(PRICE_OFFSET) / 10000.0;
    }

    public int getTimeInForce() {
        return getInt(TIME_IN_FORCE_OFFSET);
    }

    public char getDisplay() {
        return getChar(DISPLAY_OFFSET);
    }

    public int getMinimumQuantity() {
        return getInt(MIN_QUANTITY_OFFSET);
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public V50ReplaceOrderMessage setExistingUserRefNum(long userRefNum) {
        putInt(EXISTING_USER_REF_OFFSET, (int) userRefNum);
        return this;
    }

    @Override
    public V50ReplaceOrderMessage setUserRefNum(long userRefNum) {
        return setExistingUserRefNum(userRefNum);
    }

    public V50ReplaceOrderMessage setReplacementUserRefNum(long userRefNum) {
        putInt(REPLACEMENT_USER_REF_OFFSET, (int) userRefNum);
        return this;
    }

    public V50ReplaceOrderMessage setQuantity(int quantity) {
        putInt(QUANTITY_OFFSET, quantity);
        return this;
    }

    public V50ReplaceOrderMessage setPrice(long priceInMicros) {
        putPrice(PRICE_OFFSET, priceInMicros);
        return this;
    }

    public V50ReplaceOrderMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    public V50ReplaceOrderMessage setTimeInForce(int seconds) {
        putInt(TIME_IN_FORCE_OFFSET, seconds);
        return this;
    }

    public V50ReplaceOrderMessage setDisplay(char display) {
        putChar(DISPLAY_OFFSET, display);
        return this;
    }

    public V50ReplaceOrderMessage setMinimumQuantity(int minQty) {
        putInt(MIN_QUANTITY_OFFSET, minQty);
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50ReplaceOrder{existingRef=%d, newRef=%d, qty=%d, price=%.4f, appendages=%d}",
                getExistingUserRefNum(), getReplacementUserRefNum(), getQuantity(),
                getPriceAsDouble(), getAppendageCount());
    }
}
