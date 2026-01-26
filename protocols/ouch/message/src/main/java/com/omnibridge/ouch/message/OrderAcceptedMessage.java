package com.omnibridge.ouch.message;

/**
 * Order Accepted message - confirmation of order entry.
 *
 * <p>OUCH Order Accepted message format (based on OUCH 4.2):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('A')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       14      Order Token (alpha)
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
 * 65      8       BBO Weight Indicator (optional)
 * Total: 65-73 bytes
 * </pre>
 */
public class OrderAcceptedMessage extends OuchMessage {

    // Field offsets
    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int ORDER_TOKEN_OFFSET = 9;
    public static final int ORDER_TOKEN_LENGTH = 14;
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

    public static final int MESSAGE_LENGTH = 65;

    // Order State values
    public static final char ORDER_STATE_LIVE = 'L';
    public static final char ORDER_STATE_DEAD = 'D';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ORDER_ACCEPTED;
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

    /**
     * Get the price in micros (1/10000 of a dollar).
     */
    public long getPrice() {
        return getPrice(PRICE_OFFSET);
    }

    /**
     * Get the price as a double.
     */
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

    public OrderAcceptedMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    public OrderAcceptedMessage setOrderToken(String token) {
        putAlpha(ORDER_TOKEN_OFFSET, token, ORDER_TOKEN_LENGTH);
        return this;
    }

    public OrderAcceptedMessage setSide(Side side) {
        putChar(SIDE_OFFSET, side.getCode());
        return this;
    }

    public OrderAcceptedMessage setSide(char side) {
        putChar(SIDE_OFFSET, side);
        return this;
    }

    public OrderAcceptedMessage setShares(int shares) {
        putInt(SHARES_OFFSET, shares);
        return this;
    }

    public OrderAcceptedMessage setSymbol(String symbol) {
        putAlpha(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public OrderAcceptedMessage setPrice(long priceInMicros) {
        putPrice(PRICE_OFFSET, priceInMicros);
        return this;
    }

    public OrderAcceptedMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    public OrderAcceptedMessage setTimeInForce(int seconds) {
        putInt(TIME_IN_FORCE_OFFSET, seconds);
        return this;
    }

    public OrderAcceptedMessage setFirm(String firm) {
        putAlpha(FIRM_OFFSET, firm, FIRM_LENGTH);
        return this;
    }

    public OrderAcceptedMessage setDisplay(char display) {
        putChar(DISPLAY_OFFSET, display);
        return this;
    }

    public OrderAcceptedMessage setOrderReferenceNumber(long orderRef) {
        putLong(ORDER_REF_NUM_OFFSET, orderRef);
        return this;
    }

    public OrderAcceptedMessage setCapacity(char capacity) {
        putChar(CAPACITY_OFFSET, capacity);
        return this;
    }

    public OrderAcceptedMessage setIntermarketSweepEligibility(char iso) {
        putChar(ISO_ELIGIBILITY_OFFSET, iso);
        return this;
    }

    public OrderAcceptedMessage setMinimumQuantity(int minQty) {
        putInt(MIN_QUANTITY_OFFSET, minQty);
        return this;
    }

    public OrderAcceptedMessage setCrossType(char crossType) {
        putChar(CROSS_TYPE_OFFSET, crossType);
        return this;
    }

    public OrderAcceptedMessage setOrderState(char state) {
        putChar(ORDER_STATE_OFFSET, state);
        return this;
    }

    /**
     * Copy fields from an EnterOrderMessage (for acceptor implementations).
     */
    public OrderAcceptedMessage copyFrom(EnterOrderMessage enterOrder) {
        setOrderToken(enterOrder.getOrderToken());
        setSide(enterOrder.getSideCode());
        setShares(enterOrder.getShares());
        setSymbol(enterOrder.getSymbol());
        setPrice(enterOrder.getPrice());
        setTimeInForce(enterOrder.getTimeInForce());
        setFirm(enterOrder.getFirm());
        setDisplay(enterOrder.getDisplay());
        setCapacity(enterOrder.getCapacity());
        setIntermarketSweepEligibility(enterOrder.getIntermarketSweepEligibility());
        setMinimumQuantity(enterOrder.getMinimumQuantity());
        setCrossType(enterOrder.getCrossType());
        return this;
    }

    @Override
    public String toString() {
        return String.format("OrderAccepted{token=%s, side=%c, shares=%d, symbol=%s, price=%.4f, orderRef=%d, state=%c}",
                getOrderToken(), getSideCode(), getShares(), getSymbol(),
                getPriceAsDouble(), getOrderReferenceNumber(), getOrderState());
    }
}
