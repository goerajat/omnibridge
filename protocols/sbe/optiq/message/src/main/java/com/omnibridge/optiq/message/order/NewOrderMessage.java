package com.omnibridge.optiq.message.order;

import com.omnibridge.optiq.message.OptiqMessage;
import com.omnibridge.optiq.message.OptiqMessageType;

/**
 * Optiq NewOrder message (Template ID 200).
 * <p>
 * Sent by the client to submit a new order.
 * <p>
 * Block Layout (simplified):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClMsgSeqNum
 * 8       4       FirmID
 * 12      8       SendingTime
 * 20      8       ClientOrderID
 * 28      4       SymbolIndex
 * 32      1       EMM (Exchange Membership Model)
 * 33      1       OrderSide
 * 34      1       OrderType
 * 35      1       TimeInForce
 * 36      8       OrderQty
 * 44      8       OrderPx (price * 10^8)
 * 52      4       ExecutionWithinFirmShortCode
 * 56      4       TradingCapacity
 * 60      4       AccountType
 * 64      1       LPRole
 * 65      8       ExecutionInstruction
 * 73      1       DarkExecutionInstruction
 * 74      2       MIFIDIndicators
 * 76      20      Reserved
 * </pre>
 */
public class NewOrderMessage extends OptiqMessage {

    public static final int TEMPLATE_ID = 200;
    public static final int BLOCK_LENGTH = 96;

    // Field offsets
    private static final int CL_MSG_SEQ_NUM_OFFSET = 0;
    private static final int FIRM_ID_OFFSET = 8;
    private static final int SENDING_TIME_OFFSET = 12;
    private static final int CLIENT_ORDER_ID_OFFSET = 20;
    private static final int SYMBOL_INDEX_OFFSET = 28;
    private static final int EMM_OFFSET = 32;
    private static final int ORDER_SIDE_OFFSET = 33;
    private static final int ORDER_TYPE_OFFSET = 34;
    private static final int TIME_IN_FORCE_OFFSET = 35;
    private static final int ORDER_QTY_OFFSET = 36;
    private static final int ORDER_PX_OFFSET = 44;
    private static final int EXECUTION_WITHIN_FIRM_SHORT_CODE_OFFSET = 52;
    private static final int TRADING_CAPACITY_OFFSET = 56;
    private static final int ACCOUNT_TYPE_OFFSET = 60;
    private static final int LP_ROLE_OFFSET = 64;
    private static final int EXECUTION_INSTRUCTION_OFFSET = 65;
    private static final int DARK_EXECUTION_INSTRUCTION_OFFSET = 73;
    private static final int MIFID_INDICATORS_OFFSET = 74;

    // Price multiplier (10^8 for Optiq prices)
    private static final double PRICE_MULTIPLIER = 100_000_000.0;

    @Override
    public int getTemplateId() {
        return TEMPLATE_ID;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public OptiqMessageType getMessageType() {
        return OptiqMessageType.NEW_ORDER;
    }

    // Client Message Sequence Number
    public NewOrderMessage setClMsgSeqNum(long seqNum) {
        putLong(CL_MSG_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    public long getClMsgSeqNum() {
        return getLong(CL_MSG_SEQ_NUM_OFFSET);
    }

    // Firm ID
    public NewOrderMessage setFirmId(int firmId) {
        putInt(FIRM_ID_OFFSET, firmId);
        return this;
    }

    public int getFirmId() {
        return getInt(FIRM_ID_OFFSET);
    }

    // Sending Time (nanoseconds since midnight)
    public NewOrderMessage setSendingTime(long time) {
        putLong(SENDING_TIME_OFFSET, time);
        return this;
    }

    public long getSendingTime() {
        return getLong(SENDING_TIME_OFFSET);
    }

    // Client Order ID
    public NewOrderMessage setClientOrderId(long orderId) {
        putLong(CLIENT_ORDER_ID_OFFSET, orderId);
        return this;
    }

    public long getClientOrderId() {
        return getLong(CLIENT_ORDER_ID_OFFSET);
    }

    // Symbol Index
    public NewOrderMessage setSymbolIndex(int symbolIndex) {
        putInt(SYMBOL_INDEX_OFFSET, symbolIndex);
        return this;
    }

    public int getSymbolIndex() {
        return getInt(SYMBOL_INDEX_OFFSET);
    }

    // EMM (Exchange Membership Model)
    public NewOrderMessage setEmm(byte emm) {
        putByte(EMM_OFFSET, emm);
        return this;
    }

    public byte getEmm() {
        return getByte(EMM_OFFSET);
    }

    // Order Side (1=Buy, 2=Sell)
    public NewOrderMessage setOrderSide(byte side) {
        putByte(ORDER_SIDE_OFFSET, side);
        return this;
    }

    public byte getOrderSide() {
        return getByte(ORDER_SIDE_OFFSET);
    }

    // Order Type (1=Market, 2=Limit, 3=StopOnQuote, etc.)
    public NewOrderMessage setOrderType(byte ordType) {
        putByte(ORDER_TYPE_OFFSET, ordType);
        return this;
    }

    public byte getOrderType() {
        return getByte(ORDER_TYPE_OFFSET);
    }

    // Time In Force (0=Day, 1=GTC, 3=IOC, 4=FOK, 6=GTD)
    public NewOrderMessage setTimeInForce(byte tif) {
        putByte(TIME_IN_FORCE_OFFSET, tif);
        return this;
    }

    public byte getTimeInForce() {
        return getByte(TIME_IN_FORCE_OFFSET);
    }

    // Order Quantity
    public NewOrderMessage setOrderQty(long qty) {
        putLong(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public long getOrderQty() {
        return getLong(ORDER_QTY_OFFSET);
    }

    // Order Price (scaled by 10^8)
    public NewOrderMessage setOrderPx(double price) {
        putLong(ORDER_PX_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    public double getOrderPx() {
        return getLong(ORDER_PX_OFFSET) / PRICE_MULTIPLIER;
    }

    public long getOrderPxRaw() {
        return getLong(ORDER_PX_OFFSET);
    }

    // Execution Within Firm Short Code
    public NewOrderMessage setExecutionWithinFirmShortCode(int code) {
        putInt(EXECUTION_WITHIN_FIRM_SHORT_CODE_OFFSET, code);
        return this;
    }

    public int getExecutionWithinFirmShortCode() {
        return getInt(EXECUTION_WITHIN_FIRM_SHORT_CODE_OFFSET);
    }

    // Trading Capacity
    public NewOrderMessage setTradingCapacity(int capacity) {
        putInt(TRADING_CAPACITY_OFFSET, capacity);
        return this;
    }

    public int getTradingCapacity() {
        return getInt(TRADING_CAPACITY_OFFSET);
    }

    // Account Type
    public NewOrderMessage setAccountType(int type) {
        putInt(ACCOUNT_TYPE_OFFSET, type);
        return this;
    }

    public int getAccountType() {
        return getInt(ACCOUNT_TYPE_OFFSET);
    }

    // LP Role
    public NewOrderMessage setLpRole(byte role) {
        putByte(LP_ROLE_OFFSET, role);
        return this;
    }

    public byte getLpRole() {
        return getByte(LP_ROLE_OFFSET);
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "NewOrderMessage[not wrapped]";
        }
        return String.format("NewOrderMessage[clientOrderId=%d, symbolIndex=%d, side=%d, price=%.6f, qty=%d, ordType=%d]",
                getClientOrderId(), getSymbolIndex(), getOrderSide(), getOrderPx(), getOrderQty(), getOrderType());
    }
}
