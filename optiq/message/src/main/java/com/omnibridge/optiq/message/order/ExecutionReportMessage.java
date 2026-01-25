package com.omnibridge.optiq.message.order;

import com.omnibridge.optiq.message.OptiqMessage;
import com.omnibridge.optiq.message.OptiqMessageType;

/**
 * Optiq ExecutionReport message (Template ID 300).
 * <p>
 * Sent by the OEG to acknowledge order events.
 * <p>
 * Block Layout (simplified):
 * <pre>
 * Offset  Length  Field
 * 0       8       MsgSeqNum
 * 8       4       FirmID
 * 12      8       BookIn
 * 20      8       BookOut
 * 28      8       OEGIn
 * 36      8       OEGOut
 * 44      8       ClientOrderID
 * 52      8       OrderID
 * 60      4       SymbolIndex
 * 64      1       EMM
 * 65      1       OrderSide
 * 66      1       OrderStatus
 * 67      1       OrderType
 * 68      8       LastShares
 * 76      8       LastPx
 * 84      8       LeavesQty
 * 92      8       CumQty
 * 100     8       OrderQty
 * 108     8       OrderPx
 * 116     4       TradeType
 * </pre>
 */
public class ExecutionReportMessage extends OptiqMessage {

    public static final int TEMPLATE_ID = 300;
    public static final int BLOCK_LENGTH = 120;

    // Field offsets
    private static final int MSG_SEQ_NUM_OFFSET = 0;
    private static final int FIRM_ID_OFFSET = 8;
    private static final int BOOK_IN_OFFSET = 12;
    private static final int BOOK_OUT_OFFSET = 20;
    private static final int OEG_IN_OFFSET = 28;
    private static final int OEG_OUT_OFFSET = 36;
    private static final int CLIENT_ORDER_ID_OFFSET = 44;
    private static final int ORDER_ID_OFFSET = 52;
    private static final int SYMBOL_INDEX_OFFSET = 60;
    private static final int EMM_OFFSET = 64;
    private static final int ORDER_SIDE_OFFSET = 65;
    private static final int ORDER_STATUS_OFFSET = 66;
    private static final int ORDER_TYPE_OFFSET = 67;
    private static final int LAST_SHARES_OFFSET = 68;
    private static final int LAST_PX_OFFSET = 76;
    private static final int LEAVES_QTY_OFFSET = 84;
    private static final int CUM_QTY_OFFSET = 92;
    private static final int ORDER_QTY_OFFSET = 100;
    private static final int ORDER_PX_OFFSET = 108;
    private static final int TRADE_TYPE_OFFSET = 116;

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
        return OptiqMessageType.EXECUTION_REPORT;
    }

    // Message Sequence Number
    public long getMsgSeqNum() {
        return getLong(MSG_SEQ_NUM_OFFSET);
    }

    public ExecutionReportMessage setMsgSeqNum(long seqNum) {
        putLong(MSG_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    // Firm ID
    public int getFirmId() {
        return getInt(FIRM_ID_OFFSET);
    }

    public ExecutionReportMessage setFirmId(int firmId) {
        putInt(FIRM_ID_OFFSET, firmId);
        return this;
    }

    // Book In Timestamp
    public long getBookIn() {
        return getLong(BOOK_IN_OFFSET);
    }

    public ExecutionReportMessage setBookIn(long time) {
        putLong(BOOK_IN_OFFSET, time);
        return this;
    }

    // Book Out Timestamp
    public long getBookOut() {
        return getLong(BOOK_OUT_OFFSET);
    }

    public ExecutionReportMessage setBookOut(long time) {
        putLong(BOOK_OUT_OFFSET, time);
        return this;
    }

    // OEG In Timestamp
    public long getOegIn() {
        return getLong(OEG_IN_OFFSET);
    }

    public ExecutionReportMessage setOegIn(long time) {
        putLong(OEG_IN_OFFSET, time);
        return this;
    }

    // OEG Out Timestamp
    public long getOegOut() {
        return getLong(OEG_OUT_OFFSET);
    }

    public ExecutionReportMessage setOegOut(long time) {
        putLong(OEG_OUT_OFFSET, time);
        return this;
    }

    // Client Order ID
    public long getClientOrderId() {
        return getLong(CLIENT_ORDER_ID_OFFSET);
    }

    public ExecutionReportMessage setClientOrderId(long orderId) {
        putLong(CLIENT_ORDER_ID_OFFSET, orderId);
        return this;
    }

    // Order ID (exchange assigned)
    public long getOrderId() {
        return getLong(ORDER_ID_OFFSET);
    }

    public ExecutionReportMessage setOrderId(long orderId) {
        putLong(ORDER_ID_OFFSET, orderId);
        return this;
    }

    // Symbol Index
    public int getSymbolIndex() {
        return getInt(SYMBOL_INDEX_OFFSET);
    }

    public ExecutionReportMessage setSymbolIndex(int symbolIndex) {
        putInt(SYMBOL_INDEX_OFFSET, symbolIndex);
        return this;
    }

    // EMM
    public byte getEmm() {
        return getByte(EMM_OFFSET);
    }

    public ExecutionReportMessage setEmm(byte emm) {
        putByte(EMM_OFFSET, emm);
        return this;
    }

    // Order Side
    public byte getOrderSide() {
        return getByte(ORDER_SIDE_OFFSET);
    }

    public ExecutionReportMessage setOrderSide(byte side) {
        putByte(ORDER_SIDE_OFFSET, side);
        return this;
    }

    // Order Status (0=New, 1=PartiallyFilled, 2=Filled, 4=Canceled, 8=Rejected)
    public byte getOrderStatus() {
        return getByte(ORDER_STATUS_OFFSET);
    }

    public ExecutionReportMessage setOrderStatus(byte status) {
        putByte(ORDER_STATUS_OFFSET, status);
        return this;
    }

    // Order Type
    public byte getOrderType() {
        return getByte(ORDER_TYPE_OFFSET);
    }

    public ExecutionReportMessage setOrderType(byte ordType) {
        putByte(ORDER_TYPE_OFFSET, ordType);
        return this;
    }

    // Last Shares (fill quantity)
    public long getLastShares() {
        return getLong(LAST_SHARES_OFFSET);
    }

    public ExecutionReportMessage setLastShares(long qty) {
        putLong(LAST_SHARES_OFFSET, qty);
        return this;
    }

    // Last Price (fill price)
    public double getLastPx() {
        return getLong(LAST_PX_OFFSET) / PRICE_MULTIPLIER;
    }

    public ExecutionReportMessage setLastPx(double price) {
        putLong(LAST_PX_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    // Leaves Quantity
    public long getLeavesQty() {
        return getLong(LEAVES_QTY_OFFSET);
    }

    public ExecutionReportMessage setLeavesQty(long qty) {
        putLong(LEAVES_QTY_OFFSET, qty);
        return this;
    }

    // Cumulative Quantity
    public long getCumQty() {
        return getLong(CUM_QTY_OFFSET);
    }

    public ExecutionReportMessage setCumQty(long qty) {
        putLong(CUM_QTY_OFFSET, qty);
        return this;
    }

    // Order Quantity
    public long getOrderQty() {
        return getLong(ORDER_QTY_OFFSET);
    }

    public ExecutionReportMessage setOrderQty(long qty) {
        putLong(ORDER_QTY_OFFSET, qty);
        return this;
    }

    // Order Price
    public double getOrderPx() {
        return getLong(ORDER_PX_OFFSET) / PRICE_MULTIPLIER;
    }

    public ExecutionReportMessage setOrderPx(double price) {
        putLong(ORDER_PX_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    // Trade Type
    public int getTradeType() {
        return getInt(TRADE_TYPE_OFFSET);
    }

    public ExecutionReportMessage setTradeType(int type) {
        putInt(TRADE_TYPE_OFFSET, type);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "ExecutionReportMessage[not wrapped]";
        }
        return String.format("ExecutionReportMessage[clientOrderId=%d, orderId=%d, status=%d, leavesQty=%d, cumQty=%d]",
                getClientOrderId(), getOrderId(), getOrderStatus(), getLeavesQty(), getCumQty());
    }
}
