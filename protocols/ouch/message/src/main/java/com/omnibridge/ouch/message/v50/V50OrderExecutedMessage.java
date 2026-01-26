package com.omnibridge.ouch.message.v50;

import com.omnibridge.ouch.message.OuchMessageType;

/**
 * OUCH 5.0 Order Executed message - notification of order fill.
 *
 * <p>Message format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Message Type ('E')
 * 1       8       Timestamp (nanoseconds since midnight)
 * 9       4       UserRefNum (unsigned int)
 * 13      4       Executed Quantity (unsigned int)
 * 17      4       Execution Price (signed int, price * 10000)
 * 21      1       Liquidity Flag
 * 22      8       Match Number (unsigned long)
 * Total: 30 bytes
 * </pre>
 */
public class V50OrderExecutedMessage extends V50OuchMessage {

    // Field offsets
    public static final int MSG_TYPE_OFFSET = 0;
    public static final int TIMESTAMP_OFFSET = 1;
    public static final int USER_REF_NUM_OFFSET = 9;
    public static final int EXECUTED_QUANTITY_OFFSET = 13;
    public static final int EXECUTION_PRICE_OFFSET = 17;
    public static final int LIQUIDITY_FLAG_OFFSET = 21;
    public static final int MATCH_NUMBER_OFFSET = 22;

    public static final int BASE_MESSAGE_LENGTH = 30;

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
    public int getBaseMessageLength() {
        return BASE_MESSAGE_LENGTH;
    }

    @Override
    public int getAppendageCountOffset() {
        return -1; // Executed message doesn't support appendages
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

    public int getExecutedQuantity() {
        return getInt(EXECUTED_QUANTITY_OFFSET);
    }

    public long getExecutedQuantityUnsigned() {
        return getUnsignedInt(EXECUTED_QUANTITY_OFFSET);
    }

    public long getExecutionPrice() {
        return getPrice(EXECUTION_PRICE_OFFSET);
    }

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

    public V50OrderExecutedMessage setTimestamp(long timestamp) {
        putLong(TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    @Override
    public V50OrderExecutedMessage setUserRefNum(long userRefNum) {
        putInt(USER_REF_NUM_OFFSET, (int) userRefNum);
        return this;
    }

    public V50OrderExecutedMessage setExecutedQuantity(int quantity) {
        putInt(EXECUTED_QUANTITY_OFFSET, quantity);
        return this;
    }

    public V50OrderExecutedMessage setExecutionPrice(long priceInMicros) {
        putPrice(EXECUTION_PRICE_OFFSET, priceInMicros);
        return this;
    }

    public V50OrderExecutedMessage setExecutionPrice(double price) {
        putPrice(EXECUTION_PRICE_OFFSET, (long) (price * 10000));
        return this;
    }

    public V50OrderExecutedMessage setLiquidityFlag(char flag) {
        putChar(LIQUIDITY_FLAG_OFFSET, flag);
        return this;
    }

    public V50OrderExecutedMessage setMatchNumber(long matchNumber) {
        putLong(MATCH_NUMBER_OFFSET, matchNumber);
        return this;
    }

    @Override
    public String toString() {
        return String.format("V50OrderExecuted{userRef=%d, qty=%d, price=%.4f, liquidity=%c, match=%d}",
                getUserRefNum(), getExecutedQuantity(), getExecutionPriceAsDouble(),
                getLiquidityFlag(), getMatchNumber());
    }
}
