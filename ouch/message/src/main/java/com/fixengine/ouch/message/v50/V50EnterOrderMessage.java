package com.fixengine.ouch.message.v50;

import com.fixengine.ouch.message.OuchMessageType;
import com.fixengine.ouch.message.Side;
import com.fixengine.ouch.message.TimeInForce;

/**
 * OUCH 5.0 Enter Order message - submit a new order.
 *
 * <p>Message format (variable length):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('O')
 * 1       4       UserRefNum (unsigned int, client order ID)
 * 5       1       Side (Buy/Sell Indicator)
 * 6       4       Quantity (unsigned int)
 * 10      8       Symbol (alpha)
 * 18      4       Price (signed int, price * 10000)
 * 22      4       Time In Force (unsigned int, seconds)
 * 26      4       Firm (alpha)
 * 30      1       Display (Y/N/A/Z)
 * 31      1       Capacity
 * 32      4       Minimum Quantity (unsigned int)
 * 36      1       Appendage Count
 * 37+     var     Appendages (tag-length-value)
 * Base: 37 bytes
 * </pre>
 */
public class V50EnterOrderMessage extends V50OuchMessage {

    // Field offsets
    public static final int MSG_TYPE_OFFSET = 0;
    public static final int USER_REF_NUM_OFFSET = 1;
    public static final int SIDE_OFFSET = 5;
    public static final int QUANTITY_OFFSET = 6;
    public static final int SYMBOL_OFFSET = 10;
    public static final int SYMBOL_LENGTH = 8;
    public static final int PRICE_OFFSET = 18;
    public static final int TIME_IN_FORCE_OFFSET = 22;
    public static final int FIRM_OFFSET = 26;
    public static final int FIRM_LENGTH = 4;
    public static final int DISPLAY_OFFSET = 30;
    public static final int CAPACITY_OFFSET = 31;
    public static final int MIN_QUANTITY_OFFSET = 32;
    public static final int APPENDAGE_COUNT_OFFSET = 36;

    public static final int BASE_MESSAGE_LENGTH = 37;

    // Display values (V50 simplified)
    public static final char DISPLAY_VISIBLE = 'Y';
    public static final char DISPLAY_HIDDEN = 'N';
    public static final char DISPLAY_ATTRIBUTABLE = 'A';
    public static final char DISPLAY_IMBALANCE_ONLY = 'Z';

    // Capacity values
    public static final char CAPACITY_AGENCY = 'A';
    public static final char CAPACITY_PRINCIPAL = 'P';
    public static final char CAPACITY_RISKLESS_PRINCIPAL = 'R';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ENTER_ORDER;
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

    @Override
    public long getUserRefNum() {
        return getUnsignedInt(USER_REF_NUM_OFFSET);
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

    public char getCapacity() {
        return getChar(CAPACITY_OFFSET);
    }

    public int getMinimumQuantity() {
        return getInt(MIN_QUANTITY_OFFSET);
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    @Override
    public V50EnterOrderMessage setUserRefNum(long userRefNum) {
        putInt(USER_REF_NUM_OFFSET, (int) userRefNum);
        return this;
    }

    public V50EnterOrderMessage setSide(Side side) {
        putChar(SIDE_OFFSET, side.getCode());
        return this;
    }

    public V50EnterOrderMessage setSide(char side) {
        putChar(SIDE_OFFSET, side);
        return this;
    }

    public V50EnterOrderMessage setQuantity(int quantity) {
        putInt(QUANTITY_OFFSET, quantity);
        return this;
    }

    public V50EnterOrderMessage setSymbol(String symbol) {
        putAlpha(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public V50EnterOrderMessage setPrice(long priceInMicros) {
        putPrice(PRICE_OFFSET, priceInMicros);
        return this;
    }

    public V50EnterOrderMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    public V50EnterOrderMessage setTimeInForce(int seconds) {
        putInt(TIME_IN_FORCE_OFFSET, seconds);
        return this;
    }

    public V50EnterOrderMessage setTimeInForce(TimeInForce tif) {
        if (tif == TimeInForce.IOC) {
            putInt(TIME_IN_FORCE_OFFSET, 0);
        } else {
            putInt(TIME_IN_FORCE_OFFSET, 99999);
        }
        return this;
    }

    public V50EnterOrderMessage setFirm(String firm) {
        putAlpha(FIRM_OFFSET, firm, FIRM_LENGTH);
        return this;
    }

    public V50EnterOrderMessage setDisplay(char display) {
        putChar(DISPLAY_OFFSET, display);
        return this;
    }

    public V50EnterOrderMessage setCapacity(char capacity) {
        putChar(CAPACITY_OFFSET, capacity);
        return this;
    }

    public V50EnterOrderMessage setMinimumQuantity(int minQty) {
        putInt(MIN_QUANTITY_OFFSET, minQty);
        return this;
    }

    public V50EnterOrderMessage setDefaults() {
        setDisplay(DISPLAY_VISIBLE);
        setCapacity(CAPACITY_AGENCY);
        setMinimumQuantity(0);
        setFirm("    ");
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50EnterOrder{userRef=%d, side=%c, qty=%d, symbol=%s, price=%.4f, tif=%d, appendages=%d}",
                getUserRefNum(), getSideCode(), getQuantity(), getSymbol(),
                getPriceAsDouble(), getTimeInForce(), getAppendageCount());
    }
}
