package com.fixengine.ouch.message;

/**
 * Order Replaced message - confirmation of order replacement.
 *
 * <p>OUCH Order Replaced message format (based on OUCH 4.2):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('U')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       14      Replacement Order Token (alpha)
 * 23      1       Buy/Sell Indicator
 * 24      4       Shares (unsigned int)
 * 28      8       Stock Symbol (alpha)
 * 36      4       Price (signed int, price * 10000)
 * 40      4       Time In Force (unsigned int)
 * 44      4       Firm (alpha)
 * 48      1       Display
 * 49      8       Order Reference Number (unsigned long)
 * 57      1       Capacity
 * 58      1       Intermarket Sweep Eligibility
 * 59      4       Minimum Quantity (unsigned int)
 * 63      1       Cross Type
 * 64      1       Order State
 * 65      14      Previous Order Token (alpha)
 * Total: 79 bytes
 * </pre>
 */
public class OrderReplacedMessage extends OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int REPLACEMENT_TOKEN_OFFSET = 9;
    public static final int TOKEN_LENGTH = 14;
    public static final int SIDE_OFFSET = 23;
    public static final int SHARES_OFFSET = 24;
    public static final int SYMBOL_OFFSET = 28;
    public static final int SYMBOL_LENGTH = 8;
    public static final int PRICE_OFFSET = 36;
    public static final int TIME_IN_FORCE_OFFSET = 40;
    public static final int FIRM_OFFSET = 44;
    public static final int FIRM_LENGTH = 4;
    public static final int DISPLAY_OFFSET = 48;
    public static final int ORDER_REF_NUM_OFFSET = 49;
    public static final int CAPACITY_OFFSET = 57;
    public static final int ISO_ELIGIBILITY_OFFSET = 58;
    public static final int MIN_QUANTITY_OFFSET = 59;
    public static final int CROSS_TYPE_OFFSET = 63;
    public static final int ORDER_STATE_OFFSET = 64;
    public static final int PREVIOUS_TOKEN_OFFSET = 65;

    public static final int MESSAGE_LENGTH = 79;

    // Order State values
    public static final char ORDER_STATE_LIVE = 'L';
    public static final char ORDER_STATE_DEAD = 'D';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ORDER_REPLACED;
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
        return getReplacementOrderToken();
    }

    public String getReplacementOrderToken() {
        return getAlpha(REPLACEMENT_TOKEN_OFFSET, TOKEN_LENGTH);
    }

    public String getPreviousOrderToken() {
        return getAlpha(PREVIOUS_TOKEN_OFFSET, TOKEN_LENGTH);
    }

    public Side getSide() {
        return Side.fromCode(getChar(SIDE_OFFSET));
    }

    public char getSideCode() {
        return getChar(SIDE_OFFSET);
    }

    public int getShares() {
        return getInt(SHARES_OFFSET);
    }

    public long getSharesUnsigned() {
        return getUnsignedInt(SHARES_OFFSET);
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

    public char getIntermarketSweepEligibility() {
        return getChar(ISO_ELIGIBILITY_OFFSET);
    }

    public int getMinimumQuantity() {
        return getInt(MIN_QUANTITY_OFFSET);
    }

    public char getCrossType() {
        return getChar(CROSS_TYPE_OFFSET);
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

    public OrderReplacedMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    public OrderReplacedMessage setReplacementOrderToken(String token) {
        putAlpha(REPLACEMENT_TOKEN_OFFSET, token, TOKEN_LENGTH);
        return this;
    }

    public OrderReplacedMessage setPreviousOrderToken(String token) {
        putAlpha(PREVIOUS_TOKEN_OFFSET, token, TOKEN_LENGTH);
        return this;
    }

    public OrderReplacedMessage setSide(Side side) {
        putChar(SIDE_OFFSET, side.getCode());
        return this;
    }

    public OrderReplacedMessage setSide(char side) {
        putChar(SIDE_OFFSET, side);
        return this;
    }

    public OrderReplacedMessage setShares(int shares) {
        putInt(SHARES_OFFSET, shares);
        return this;
    }

    public OrderReplacedMessage setSymbol(String symbol) {
        putAlpha(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public OrderReplacedMessage setPrice(long priceInMicros) {
        putPrice(PRICE_OFFSET, priceInMicros);
        return this;
    }

    public OrderReplacedMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    public OrderReplacedMessage setTimeInForce(int seconds) {
        putInt(TIME_IN_FORCE_OFFSET, seconds);
        return this;
    }

    public OrderReplacedMessage setFirm(String firm) {
        putAlpha(FIRM_OFFSET, firm, FIRM_LENGTH);
        return this;
    }

    public OrderReplacedMessage setDisplay(char display) {
        putChar(DISPLAY_OFFSET, display);
        return this;
    }

    public OrderReplacedMessage setOrderReferenceNumber(long orderRef) {
        putLong(ORDER_REF_NUM_OFFSET, orderRef);
        return this;
    }

    public OrderReplacedMessage setCapacity(char capacity) {
        putChar(CAPACITY_OFFSET, capacity);
        return this;
    }

    public OrderReplacedMessage setIntermarketSweepEligibility(char iso) {
        putChar(ISO_ELIGIBILITY_OFFSET, iso);
        return this;
    }

    public OrderReplacedMessage setMinimumQuantity(int minQty) {
        putInt(MIN_QUANTITY_OFFSET, minQty);
        return this;
    }

    public OrderReplacedMessage setCrossType(char crossType) {
        putChar(CROSS_TYPE_OFFSET, crossType);
        return this;
    }

    public OrderReplacedMessage setOrderState(char state) {
        putChar(ORDER_STATE_OFFSET, state);
        return this;
    }

    @Override
    public String toString() {
        return String.format("OrderReplaced{newToken=%s, prevToken=%s, side=%c, shares=%d, symbol=%s, price=%.4f}",
                getReplacementOrderToken(), getPreviousOrderToken(), getSideCode(),
                getShares(), getSymbol(), getPriceAsDouble());
    }
}
