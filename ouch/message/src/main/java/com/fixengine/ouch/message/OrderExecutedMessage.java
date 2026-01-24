package com.fixengine.ouch.message;

/**
 * Order Executed message - notification of order fill.
 *
 * <p>OUCH Order Executed message format (based on OUCH 4.2):</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('E')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       14      Order Token (alpha)
 * 23      4       Executed Shares (unsigned int)
 * 27      4       Execution Price (signed int, price * 10000)
 * 31      1       Liquidity Flag
 * 32      8       Match Number (unsigned long)
 * Total: 40 bytes
 * </pre>
 */
public class OrderExecutedMessage extends OuchMessage {

    // Field offsets
    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int ORDER_TOKEN_OFFSET = 9;
    public static final int ORDER_TOKEN_LENGTH = 14;
    public static final int EXECUTED_SHARES_OFFSET = 23;
    public static final int EXECUTION_PRICE_OFFSET = 27;
    public static final int LIQUIDITY_FLAG_OFFSET = 31;
    public static final int MATCH_NUMBER_OFFSET = 32;

    public static final int MESSAGE_LENGTH = 40;

    // Liquidity Flag values
    public static final char LIQUIDITY_ADDED = 'A';
    public static final char LIQUIDITY_REMOVED = 'R';
    public static final char LIQUIDITY_OPENING_CROSS = 'O';
    public static final char LIQUIDITY_CLOSING_CROSS = 'C';
    public static final char LIQUIDITY_HALT_CROSS = 'H';

    @Override
    public OuchMessageType getMessageType() {
        return OuchMessageType.ORDER_EXECUTED;
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

    public int getExecutedShares() {
        return getInt(EXECUTED_SHARES_OFFSET);
    }

    public long getExecutedSharesUnsigned() {
        return getUnsignedInt(EXECUTED_SHARES_OFFSET);
    }

    /**
     * Get the execution price in micros (1/10000 of a dollar).
     */
    public long getExecutionPrice() {
        return getPrice(EXECUTION_PRICE_OFFSET);
    }

    /**
     * Get the execution price as a double.
     */
    public double getExecutionPriceAsDouble() {
        return getPrice(EXECUTION_PRICE_OFFSET) / 10000.0;
    }

    public char getLiquidityFlag() {
        return getChar(LIQUIDITY_FLAG_OFFSET);
    }

    public boolean isLiquidityAdded() {
        return getLiquidityFlag() == LIQUIDITY_ADDED;
    }

    public boolean isLiquidityRemoved() {
        return getLiquidityFlag() == LIQUIDITY_REMOVED;
    }

    public long getMatchNumber() {
        return getLong(MATCH_NUMBER_OFFSET);
    }

    // =====================================================
    // Field setters (for sending/writing)
    // =====================================================

    public OrderExecutedMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    public OrderExecutedMessage setOrderToken(String token) {
        putAlpha(ORDER_TOKEN_OFFSET, token, ORDER_TOKEN_LENGTH);
        return this;
    }

    public OrderExecutedMessage setExecutedShares(int shares) {
        putInt(EXECUTED_SHARES_OFFSET, shares);
        return this;
    }

    public OrderExecutedMessage setExecutionPrice(long priceInMicros) {
        putPrice(EXECUTION_PRICE_OFFSET, priceInMicros);
        return this;
    }

    public OrderExecutedMessage setExecutionPrice(double price) {
        putPrice(EXECUTION_PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    public OrderExecutedMessage setLiquidityFlag(char flag) {
        putChar(LIQUIDITY_FLAG_OFFSET, flag);
        return this;
    }

    public OrderExecutedMessage setMatchNumber(long matchNumber) {
        putLong(MATCH_NUMBER_OFFSET, matchNumber);
        return this;
    }

    @Override
    public String toString() {
        return String.format("OrderExecuted{token=%s, shares=%d, price=%.4f, liquidity=%c, match=%d}",
                getOrderToken(), getExecutedShares(), getExecutionPriceAsDouble(),
                getLiquidityFlag(), getMatchNumber());
    }
}
