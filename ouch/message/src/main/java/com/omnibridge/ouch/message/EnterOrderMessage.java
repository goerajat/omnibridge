package com.omnibridge.ouch.message;

/**
 * Enter Order message - submit a new order.
 *
 * <p>OUCH Enter Order message format (based on OUCH 4.2):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('O')
 * 1       14      Order Token (alpha, client order ID)
 * 15      1       Buy/Sell Indicator
 * 16      4       Shares (unsigned int)
 * 20      8       Stock Symbol (alpha)
 * 28      4       Price (signed int, price * 10000)
 * 32      4       Time In Force (unsigned int, seconds)
 * 36      4       Firm (alpha)
 * 40      1       Display
 * 41      1       Capacity
 * 42      1       Intermarket Sweep Eligibility
 * 43      4       Minimum Quantity (unsigned int)
 * 47      1       Cross Type
 * Total: 48 bytes
 * </pre>
 */
public class EnterOrderMessage extends OuchMessage {

    // Field offsets
    public static final int MSG_TYPE_OFFSET = 0;
    public static final int ORDER_TOKEN_OFFSET = 1;
    public static final int ORDER_TOKEN_LENGTH = 14;
    public static final int SIDE_OFFSET = 15;
    public static final int SHARES_OFFSET = 16;
    public static final int SYMBOL_OFFSET = 20;
    public static final int SYMBOL_LENGTH = 8;
    public static final int PRICE_OFFSET = 28;
    public static final int TIME_IN_FORCE_OFFSET = 32;
    public static final int FIRM_OFFSET = 36;
    public static final int FIRM_LENGTH = 4;
    public static final int DISPLAY_OFFSET = 40;
    public static final int CAPACITY_OFFSET = 41;
    public static final int ISO_ELIGIBILITY_OFFSET = 42;
    public static final int MIN_QUANTITY_OFFSET = 43;
    public static final int CROSS_TYPE_OFFSET = 47;

    public static final int MESSAGE_LENGTH = 48;

    // Display values
    public static final char DISPLAY_VISIBLE = 'Y';
    public static final char DISPLAY_HIDDEN = 'N';
    public static final char DISPLAY_ATTRIBUTABLE = 'A';
    public static final char DISPLAY_POST_ONLY = 'P';

    // Capacity values
    public static final char CAPACITY_AGENCY = 'A';
    public static final char CAPACITY_PRINCIPAL = 'P';
    public static final char CAPACITY_RISKLESS_PRINCIPAL = 'R';

    // ISO Eligibility values
    public static final char ISO_ELIGIBLE = 'Y';
    public static final char ISO_NOT_ELIGIBLE = 'N';

    // Cross Type values
    public static final char CROSS_NONE = 'N';
    public static final char CROSS_OPENING = 'O';
    public static final char CROSS_CLOSING = 'C';
    public static final char CROSS_HALT_IPO = 'H';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ENTER_ORDER;
    }

    @Override
    public int getMessageLength() {
        return MESSAGE_LENGTH;
    }

    // =====================================================
    // Field getters (for receiving/reading)
    // =====================================================

    /**
     * Get the order token (client order ID).
     */
    public String getOrderToken() {
        return getAlpha(ORDER_TOKEN_OFFSET, ORDER_TOKEN_LENGTH);
    }

    /**
     * Get the side (buy/sell indicator).
     */
    public Side getSide() {
        return Side.fromCode(getChar(SIDE_OFFSET));
    }

    /**
     * Get the side code character.
     */
    public char getSideCode() {
        return getChar(SIDE_OFFSET);
    }

    /**
     * Get the number of shares.
     */
    public int getShares() {
        return getInt(SHARES_OFFSET);
    }

    /**
     * Get the shares as unsigned value.
     */
    public long getSharesUnsigned() {
        return getUnsignedInt(SHARES_OFFSET);
    }

    /**
     * Get the stock symbol.
     */
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

    /**
     * Get the time in force in seconds.
     */
    public int getTimeInForce() {
        return getInt(TIME_IN_FORCE_OFFSET);
    }

    /**
     * Get the firm identifier.
     */
    public String getFirm() {
        return getAlpha(FIRM_OFFSET, FIRM_LENGTH);
    }

    /**
     * Get the display indicator.
     */
    public char getDisplay() {
        return getChar(DISPLAY_OFFSET);
    }

    /**
     * Get the capacity indicator.
     */
    public char getCapacity() {
        return getChar(CAPACITY_OFFSET);
    }

    /**
     * Get the intermarket sweep eligibility indicator.
     */
    public char getIntermarketSweepEligibility() {
        return getChar(ISO_ELIGIBILITY_OFFSET);
    }

    /**
     * Get the minimum quantity.
     */
    public int getMinimumQuantity() {
        return getInt(MIN_QUANTITY_OFFSET);
    }

    /**
     * Get the cross type.
     */
    public char getCrossType() {
        return getChar(CROSS_TYPE_OFFSET);
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public EnterOrderMessage setOrderToken(String token) {
        putAlpha(ORDER_TOKEN_OFFSET, token, ORDER_TOKEN_LENGTH);
        return this;
    }

    public EnterOrderMessage setSide(Side side) {
        putChar(SIDE_OFFSET, side.getCode());
        return this;
    }

    public EnterOrderMessage setSide(char side) {
        putChar(SIDE_OFFSET, side);
        return this;
    }

    public EnterOrderMessage setShares(int shares) {
        putInt(SHARES_OFFSET, shares);
        return this;
    }

    public EnterOrderMessage setSymbol(String symbol) {
        putAlpha(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    /**
     * Set the price in micros (1/10000 of a dollar).
     * For example, $150.00 = 1500000 micros.
     */
    public EnterOrderMessage setPrice(long priceInMicros) {
        putPrice(PRICE_OFFSET, priceInMicros);
        return this;
    }

    /**
     * Set the price from dollars and cents.
     */
    public EnterOrderMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    public EnterOrderMessage setTimeInForce(int seconds) {
        putInt(TIME_IN_FORCE_OFFSET, seconds);
        return this;
    }

    public EnterOrderMessage setTimeInForce(TimeInForce tif) {
        // For immediate or cancel, use 0
        if (tif == TimeInForce.IOC) {
            putInt(TIME_IN_FORCE_OFFSET, 0);
        } else {
            // For day orders, use 99999
            putInt(TIME_IN_FORCE_OFFSET, 99999);
        }
        return this;
    }

    public EnterOrderMessage setFirm(String firm) {
        putAlpha(FIRM_OFFSET, firm, FIRM_LENGTH);
        return this;
    }

    public EnterOrderMessage setDisplay(char display) {
        putChar(DISPLAY_OFFSET, display);
        return this;
    }

    public EnterOrderMessage setCapacity(char capacity) {
        putChar(CAPACITY_OFFSET, capacity);
        return this;
    }

    public EnterOrderMessage setIntermarketSweepEligibility(char iso) {
        putChar(ISO_ELIGIBILITY_OFFSET, iso);
        return this;
    }

    public EnterOrderMessage setMinimumQuantity(int minQty) {
        putInt(MIN_QUANTITY_OFFSET, minQty);
        return this;
    }

    public EnterOrderMessage setCrossType(char crossType) {
        putChar(CROSS_TYPE_OFFSET, crossType);
        return this;
    }

    /**
     * Set common defaults for a simple order.
     */
    public EnterOrderMessage setDefaults() {
        setDisplay(DISPLAY_VISIBLE);
        setCapacity(CAPACITY_AGENCY);
        setIntermarketSweepEligibility(ISO_NOT_ELIGIBLE);
        setMinimumQuantity(0);
        setCrossType(CROSS_NONE);
        setFirm("    ");
        return this;
    }

    @Override
    public String toString() {
        return String.format("EnterOrder{token=%s, side=%c, shares=%d, symbol=%s, price=%.4f, tif=%d}",
                getOrderToken(), getSideCode(), getShares(), getSymbol(),
                getPriceAsDouble(), getTimeInForce());
    }
}
