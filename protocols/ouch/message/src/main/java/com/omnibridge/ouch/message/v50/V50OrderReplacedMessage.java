package com.omnibridge.ouch.message.v50;

import com.omnibridge.ouch.message.OuchMessageType;
import com.omnibridge.ouch.message.Side;

/**
 * OUCH 5.0 Order Replaced message - confirmation of order replacement.
 *
 * <p>Message format (variable length):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('U')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       4       Replacement UserRefNum (unsigned int)
 * 13      1       Side (Buy/Sell Indicator)
 * 14      4       Quantity (unsigned int)
 * 18      8       Symbol (alpha)
 * 26      4       Price (signed int, price * 10000)
 * 30      4       Time In Force (unsigned int)
 * 34      4       Firm (alpha)
 * 38      1       Display
 * 39      8       Order Reference Number (unsigned long)
 * 47      1       Capacity
 * 48      4       Minimum Quantity (unsigned int)
 * 52      1       Order State
 * 53      4       Previous UserRefNum (unsigned int)
 * 57      1       Appendage Count
 * 58+     var     Appendages
 * Base: 58 bytes
 * </pre>
 */
public class V50OrderReplacedMessage extends V50OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int REPLACEMENT_USER_REF_OFFSET = 9;
    public static final int SIDE_OFFSET = 13;
    public static final int QUANTITY_OFFSET = 14;
    public static final int SYMBOL_OFFSET = 18;
    public static final int SYMBOL_LENGTH = 8;
    public static final int PRICE_OFFSET = 26;
    public static final int TIME_IN_FORCE_OFFSET = 30;
    public static final int FIRM_OFFSET = 34;
    public static final int FIRM_LENGTH = 4;
    public static final int DISPLAY_OFFSET = 38;
    public static final int ORDER_REF_NUM_OFFSET = 39;
    public static final int CAPACITY_OFFSET = 47;
    public static final int MIN_QUANTITY_OFFSET = 48;
    public static final int ORDER_STATE_OFFSET = 52;
    public static final int PREVIOUS_USER_REF_OFFSET = 53;
    public static final int APPENDAGE_COUNT_OFFSET = 57;

    public static final int BASE_MESSAGE_LENGTH = 58;

    // Order State values
    public static final char ORDER_STATE_LIVE = 'L';
    public static final char ORDER_STATE_DEAD = 'D';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ORDER_REPLACED;
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

    public long getTimestamp() {
        return getLong(TIMESTAMP_OFFSET);
    }

    @Override
    public long getUserRefNum() {
        return getReplacementUserRefNum();
    }

    public long getReplacementUserRefNum() {
        return getUnsignedInt(REPLACEMENT_USER_REF_OFFSET);
    }

    public long getPreviousUserRefNum() {
        return getUnsignedInt(PREVIOUS_USER_REF_OFFSET);
    }

    public Side getSide() {
        return Side.fromCode(getChar(SIDE_OFFSET));
    }

    public char getSideCode() {
        return getChar(SIDE_OFFSET);
    }

    public int getQuantity() {
        return getInt(QUANTITY_OFFSET);
    }

    public long getQuantityUnsigned() {
        return getUnsignedInt(QUANTITY_OFFSET);
    }

    public String getSymbol() {
        return getAlpha(SYMBOL_OFFSET, SYMBOL_LENGTH);
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

    public String getFirm() {
        return getAlpha(FIRM_OFFSET, FIRM_LENGTH);
    }

    public char getDisplay() {
        return getChar(DISPLAY_OFFSET);
    }

    public long getOrderReferenceNumber() {
        return getLong(ORDER_REF_NUM_OFFSET);
    }

    public char getCapacity() {
        return getChar(CAPACITY_OFFSET);
    }

    public int getMinimumQuantity() {
        return getInt(MIN_QUANTITY_OFFSET);
    }

    public char getOrderState() {
        return getChar(ORDER_STATE_OFFSET);
    }

    public boolean isOrderLive() {
        return getOrderState() == ORDER_STATE_LIVE;
    }

    public boolean isOrderDead() {
        return getOrderState() == ORDER_STATE_DEAD;
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public V50OrderReplacedMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    @Override
    public V50OrderReplacedMessage setUserRefNum(long userRefNum) {
        return setReplacementUserRefNum(userRefNum);
    }

    public V50OrderReplacedMessage setReplacementUserRefNum(long userRefNum) {
        putInt(REPLACEMENT_USER_REF_OFFSET, (int) userRefNum);
        return this;
    }

    public V50OrderReplacedMessage setPreviousUserRefNum(long userRefNum) {
        putInt(PREVIOUS_USER_REF_OFFSET, (int) userRefNum);
        return this;
    }

    public V50OrderReplacedMessage setSide(Side side) {
        putChar(SIDE_OFFSET, side.getCode());
        return this;
    }

    public V50OrderReplacedMessage setSide(char side) {
        putChar(SIDE_OFFSET, side);
        return this;
    }

    public V50OrderReplacedMessage setQuantity(int quantity) {
        putInt(QUANTITY_OFFSET, quantity);
        return this;
    }

    public V50OrderReplacedMessage setSymbol(String symbol) {
        putAlpha(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public V50OrderReplacedMessage setPrice(long priceInMicros) {
        putPrice(PRICE_OFFSET, priceInMicros);
        return this;
    }

    public V50OrderReplacedMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    public V50OrderReplacedMessage setTimeInForce(int seconds) {
        putInt(TIME_IN_FORCE_OFFSET, seconds);
        return this;
    }

    public V50OrderReplacedMessage setFirm(String firm) {
        putAlpha(FIRM_OFFSET, firm, FIRM_LENGTH);
        return this;
    }

    public V50OrderReplacedMessage setDisplay(char display) {
        putChar(DISPLAY_OFFSET, display);
        return this;
    }

    public V50OrderReplacedMessage setOrderReferenceNumber(long orderRef) {
        putLong(ORDER_REF_NUM_OFFSET, orderRef);
        return this;
    }

    public V50OrderReplacedMessage setCapacity(char capacity) {
        putChar(CAPACITY_OFFSET, capacity);
        return this;
    }

    public V50OrderReplacedMessage setMinimumQuantity(int minQty) {
        putInt(MIN_QUANTITY_OFFSET, minQty);
        return this;
    }

    public V50OrderReplacedMessage setOrderState(char state) {
        putChar(ORDER_STATE_OFFSET, state);
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50OrderReplaced{newRef=%d, prevRef=%d, side=%c, qty=%d, symbol=%s, price=%.4f}",
                getReplacementUserRefNum(), getPreviousUserRefNum(), getSideCode(),
                getQuantity(), getSymbol(), getPriceAsDouble());
    }
}
