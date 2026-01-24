package com.fixengine.ouch.message;

/**
 * Replace Order message - modify an existing order.
 *
 * <p>OUCH Replace Order message format (based on OUCH 4.2):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('U')
 * 1       14      Existing Order Token (alpha)
 * 15      14      Replacement Order Token (alpha)
 * 29      4       Shares (unsigned int)
 * 33      4       Price (signed int, price * 10000)
 * 37      4       Time In Force (unsigned int)
 * 41      1       Display
 * 42      1       Intermarket Sweep Eligibility
 * 43      4       Minimum Quantity (unsigned int)
 * Total: 47 bytes
 * </pre>
 */
public class ReplaceOrderMessage extends OuchMessage {

    public static final int MSG_TYPE_OFFSET = 0;
    public static final int EXISTING_TOKEN_OFFSET = 1;
    public static final int REPLACEMENT_TOKEN_OFFSET = 15;
    public static final int TOKEN_LENGTH = 14;
    public static final int SHARES_OFFSET = 29;
    public static final int PRICE_OFFSET = 33;
    public static final int TIME_IN_FORCE_OFFSET = 37;
    public static final int DISPLAY_OFFSET = 41;
    public static final int ISO_ELIGIBILITY_OFFSET = 42;
    public static final int MIN_QUANTITY_OFFSET = 43;

    public static final int MESSAGE_LENGTH = 47;

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.REPLACE_ORDER;
    }

    @Override
    public int getMessageLength() {
        return MESSAGE_LENGTH;
    }

    // =====================================================
    // Field getters (for receiving/reading)
    // =====================================================

    /**
     * Get the existing order token to be replaced.
     */
    public String getExistingOrderToken() {
        return getAlpha(EXISTING_TOKEN_OFFSET, TOKEN_LENGTH);
    }

    /**
     * Get the replacement order token (alias for getExistingOrderToken for compatibility).
     */
    public String getOrderToken() {
        return getExistingOrderToken();
    }

    /**
     * Get the new replacement order token.
     */
    public String getReplacementOrderToken() {
        return getAlpha(REPLACEMENT_TOKEN_OFFSET, TOKEN_LENGTH);
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
     * Get the display indicator.
     */
    public char getDisplay() {
        return getChar(DISPLAY_OFFSET);
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

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public ReplaceOrderMessage setExistingOrderToken(String token) {
        putAlpha(EXISTING_TOKEN_OFFSET, token, TOKEN_LENGTH);
        return this;
    }

    public ReplaceOrderMessage setReplacementOrderToken(String token) {
        putAlpha(REPLACEMENT_TOKEN_OFFSET, token, TOKEN_LENGTH);
        return this;
    }

    public ReplaceOrderMessage setShares(int shares) {
        putInt(SHARES_OFFSET, shares);
        return this;
    }

    public ReplaceOrderMessage setPrice(long priceInMicros) {
        putPrice(PRICE_OFFSET, priceInMicros);
        return this;
    }

    public ReplaceOrderMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    public ReplaceOrderMessage setTimeInForce(int seconds) {
        putInt(TIME_IN_FORCE_OFFSET, seconds);
        return this;
    }

    public ReplaceOrderMessage setDisplay(char display) {
        putChar(DISPLAY_OFFSET, display);
        return this;
    }

    public ReplaceOrderMessage setIntermarketSweepEligibility(char iso) {
        putChar(ISO_ELIGIBILITY_OFFSET, iso);
        return this;
    }

    public ReplaceOrderMessage setMinimumQuantity(int minQty) {
        putInt(MIN_QUANTITY_OFFSET, minQty);
        return this;
    }

    @Override
    public String toString() {
        return String.format("ReplaceOrder{existingToken=%s, newToken=%s, shares=%d, price=%.4f}",
                getExistingOrderToken(), getReplacementOrderToken(), getShares(), getPriceAsDouble());
    }
}
