package com.omnibridge.optiq.message.order;

import com.omnibridge.optiq.message.OptiqMessage;
import com.omnibridge.optiq.message.OptiqMessageType;

/**
 * Optiq ModifyOrder message (Template ID 201).
 * <p>
 * Sent by the client to modify an existing order.
 * <p>
 * Block Layout (simplified):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClMsgSeqNum
 * 8       4       FirmID
 * 12      8       SendingTime
 * 20      8       ClientOrderID
 * 28      8       OrderID (exchange assigned)
 * 36      8       OrigClientOrderID
 * 44      4       SymbolIndex
 * 48      1       EMM
 * 49      1       OrderSide
 * 50      1       OrderType
 * 51      1       TimeInForce
 * 52      8       OrderQty
 * 60      8       OrderPx
 * 68      4       ExecutionWithinFirmShortCode
 * 72      4       TradingCapacity
 * 76      4       AccountType
 * 80      1       LPRole
 * 81      8       ExecutionInstruction
 * 89      1       DarkExecutionInstruction
 * 90      2       MIFIDIndicators
 * 92      12      Reserved
 * </pre>
 */
public class ModifyOrderMessage extends OptiqMessage {

    public static final int TEMPLATE_ID = 201;
    public static final int BLOCK_LENGTH = 104;

    // Field offsets
    private static final int CL_MSG_SEQ_NUM_OFFSET = 0;
    private static final int FIRM_ID_OFFSET = 8;
    private static final int SENDING_TIME_OFFSET = 12;
    private static final int CLIENT_ORDER_ID_OFFSET = 20;
    private static final int ORDER_ID_OFFSET = 28;
    private static final int ORIG_CLIENT_ORDER_ID_OFFSET = 36;
    private static final int SYMBOL_INDEX_OFFSET = 44;
    private static final int EMM_OFFSET = 48;
    private static final int ORDER_SIDE_OFFSET = 49;
    private static final int ORDER_TYPE_OFFSET = 50;
    private static final int TIME_IN_FORCE_OFFSET = 51;
    private static final int ORDER_QTY_OFFSET = 52;
    private static final int ORDER_PX_OFFSET = 60;
    private static final int EXECUTION_WITHIN_FIRM_SHORT_CODE_OFFSET = 68;
    private static final int TRADING_CAPACITY_OFFSET = 72;
    private static final int ACCOUNT_TYPE_OFFSET = 76;
    private static final int LP_ROLE_OFFSET = 80;

    // Price multiplier (10^8)
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
        return OptiqMessageType.MODIFY_ORDER;
    }

    // Client Message Sequence Number
    public ModifyOrderMessage setClMsgSeqNum(long seqNum) {
        putLong(CL_MSG_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    public long getClMsgSeqNum() {
        return getLong(CL_MSG_SEQ_NUM_OFFSET);
    }

    // Firm ID
    public ModifyOrderMessage setFirmId(int firmId) {
        putInt(FIRM_ID_OFFSET, firmId);
        return this;
    }

    public int getFirmId() {
        return getInt(FIRM_ID_OFFSET);
    }

    // Sending Time
    public ModifyOrderMessage setSendingTime(long time) {
        putLong(SENDING_TIME_OFFSET, time);
        return this;
    }

    public long getSendingTime() {
        return getLong(SENDING_TIME_OFFSET);
    }

    // Client Order ID (new)
    public ModifyOrderMessage setClientOrderId(long orderId) {
        putLong(CLIENT_ORDER_ID_OFFSET, orderId);
        return this;
    }

    public long getClientOrderId() {
        return getLong(CLIENT_ORDER_ID_OFFSET);
    }

    // Order ID (exchange assigned)
    public ModifyOrderMessage setOrderId(long orderId) {
        putLong(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public long getOrderId() {
        return getLong(ORDER_ID_OFFSET);
    }

    // Original Client Order ID
    public ModifyOrderMessage setOrigClientOrderId(long orderId) {
        putLong(ORIG_CLIENT_ORDER_ID_OFFSET, orderId);
        return this;
    }

    public long getOrigClientOrderId() {
        return getLong(ORIG_CLIENT_ORDER_ID_OFFSET);
    }

    // Symbol Index
    public ModifyOrderMessage setSymbolIndex(int symbolIndex) {
        putInt(SYMBOL_INDEX_OFFSET, symbolIndex);
        return this;
    }

    public int getSymbolIndex() {
        return getInt(SYMBOL_INDEX_OFFSET);
    }

    // EMM
    public ModifyOrderMessage setEmm(byte emm) {
        putByte(EMM_OFFSET, emm);
        return this;
    }

    public byte getEmm() {
        return getByte(EMM_OFFSET);
    }

    // Order Side
    public ModifyOrderMessage setOrderSide(byte side) {
        putByte(ORDER_SIDE_OFFSET, side);
        return this;
    }

    public byte getOrderSide() {
        return getByte(ORDER_SIDE_OFFSET);
    }

    // Order Type
    public ModifyOrderMessage setOrderType(byte ordType) {
        putByte(ORDER_TYPE_OFFSET, ordType);
        return this;
    }

    public byte getOrderType() {
        return getByte(ORDER_TYPE_OFFSET);
    }

    // Time In Force
    public ModifyOrderMessage setTimeInForce(byte tif) {
        putByte(TIME_IN_FORCE_OFFSET, tif);
        return this;
    }

    public byte getTimeInForce() {
        return getByte(TIME_IN_FORCE_OFFSET);
    }

    // Order Quantity
    public ModifyOrderMessage setOrderQty(long qty) {
        putLong(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public long getOrderQty() {
        return getLong(ORDER_QTY_OFFSET);
    }

    // Order Price
    public ModifyOrderMessage setOrderPx(double price) {
        putLong(ORDER_PX_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    public double getOrderPx() {
        return getLong(ORDER_PX_OFFSET) / PRICE_MULTIPLIER;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "ModifyOrderMessage[not wrapped]";
        }
        return String.format("ModifyOrderMessage[clientOrderId=%d, orderId=%d, price=%.6f, qty=%d]",
                getClientOrderId(), getOrderId(), getOrderPx(), getOrderQty());
    }
}
