package com.omnibridge.pillar.message.order;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Execution Report message.
 * <p>
 * Sent by the gateway to report a trade execution.
 * <p>
 * Message structure (after SeqMsg header):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClOrdID
 * 8       8       OrderID
 * 16      8       ExecID (Trade ID)
 * 24      8       Symbol
 * 32      1       Side
 * 33      1       OrdType
 * 34      1       TimeInForce
 * 35      1       OrdStatus
 * 36      4       OrderQty
 * 40      8       Price
 * 48      8       LastQty (Quantity executed in this fill)
 * 56      8       LastPx (Price of this fill)
 * 64      8       LeavesQty
 * 72      8       CumQty
 * 80      8       AvgPx (Average price of all fills)
 * 88      8       TransactTime
 * 96      8       TradeDate
 * 104     16      ContraBroker (Counterparty ID)
 * 120     8       ExecType (0=New, F=Trade, etc.)
 * 128     16      Reserved
 * </pre>
 */
public class ExecutionReportMessage extends PillarMessage {

    public static final int CL_ORD_ID_OFFSET = 0;
    public static final int ORDER_ID_OFFSET = 8;
    public static final int EXEC_ID_OFFSET = 16;
    public static final int SYMBOL_OFFSET = 24;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 32;
    public static final int ORD_TYPE_OFFSET = 33;
    public static final int TIME_IN_FORCE_OFFSET = 34;
    public static final int ORD_STATUS_OFFSET = 35;
    public static final int ORDER_QTY_OFFSET = 36;
    public static final int PRICE_OFFSET = 40;
    public static final int LAST_QTY_OFFSET = 48;
    public static final int LAST_PX_OFFSET = 56;
    public static final int LEAVES_QTY_OFFSET = 64;
    public static final int CUM_QTY_OFFSET = 72;
    public static final int AVG_PX_OFFSET = 80;
    public static final int TRANSACT_TIME_OFFSET = 88;
    public static final int TRADE_DATE_OFFSET = 96;
    public static final int CONTRA_BROKER_OFFSET = 104;
    public static final int CONTRA_BROKER_LENGTH = 16;
    public static final int EXEC_TYPE_OFFSET = 120;

    public static final int BLOCK_LENGTH = 144;

    // ExecType values
    public static final byte EXEC_TYPE_NEW = '0';
    public static final byte EXEC_TYPE_PARTIAL_FILL = '1';
    public static final byte EXEC_TYPE_FILL = '2';
    public static final byte EXEC_TYPE_DONE_FOR_DAY = '3';
    public static final byte EXEC_TYPE_CANCELED = '4';
    public static final byte EXEC_TYPE_REPLACED = '5';
    public static final byte EXEC_TYPE_PENDING_CANCEL = '6';
    public static final byte EXEC_TYPE_REJECTED = '8';
    public static final byte EXEC_TYPE_TRADE = 'F';
    public static final byte EXEC_TYPE_TRADE_CORRECT = 'G';
    public static final byte EXEC_TYPE_TRADE_CANCEL = 'H';

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.EXECUTION_REPORT;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.EXECUTION_REPORT.getTemplateId();
    }

    // ==================== Getters ====================

    public long getClOrdId() {
        return getUInt64(CL_ORD_ID_OFFSET);
    }

    public long getOrderId() {
        return getUInt64(ORDER_ID_OFFSET);
    }

    public long getExecId() {
        return getUInt64(EXEC_ID_OFFSET);
    }

    public String getSymbol() {
        return getString(SYMBOL_OFFSET, SYMBOL_LENGTH);
    }

    public byte getSide() {
        return getByte(SIDE_OFFSET);
    }

    public byte getOrdType() {
        return getByte(ORD_TYPE_OFFSET);
    }

    public byte getTimeInForce() {
        return getByte(TIME_IN_FORCE_OFFSET);
    }

    public byte getOrdStatus() {
        return getByte(ORD_STATUS_OFFSET);
    }

    public long getOrderQty() {
        return getUInt32(ORDER_QTY_OFFSET);
    }

    public double getPrice() {
        return getPrice(PRICE_OFFSET);
    }

    public long getLastQty() {
        return getUInt64(LAST_QTY_OFFSET);
    }

    public double getLastPx() {
        return getPrice(LAST_PX_OFFSET);
    }

    public long getLeavesQty() {
        return getUInt64(LEAVES_QTY_OFFSET);
    }

    public long getCumQty() {
        return getUInt64(CUM_QTY_OFFSET);
    }

    public double getAvgPx() {
        return getPrice(AVG_PX_OFFSET);
    }

    public long getTransactTime() {
        return getUInt64(TRANSACT_TIME_OFFSET);
    }

    public long getTradeDate() {
        return getUInt64(TRADE_DATE_OFFSET);
    }

    public String getContraBroker() {
        return getString(CONTRA_BROKER_OFFSET, CONTRA_BROKER_LENGTH);
    }

    public byte getExecType() {
        return getByte(EXEC_TYPE_OFFSET);
    }

    // ==================== Setters ====================

    public ExecutionReportMessage setClOrdId(long clOrdId) {
        putUInt64(CL_ORD_ID_OFFSET, clOrdId);
        return this;
    }

    public ExecutionReportMessage setOrderId(long orderId) {
        putUInt64(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public ExecutionReportMessage setExecId(long execId) {
        putUInt64(EXEC_ID_OFFSET, execId);
        return this;
    }

    public ExecutionReportMessage setSymbol(String symbol) {
        putString(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public ExecutionReportMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public ExecutionReportMessage setOrdType(byte ordType) {
        putByte(ORD_TYPE_OFFSET, ordType);
        return this;
    }

    public ExecutionReportMessage setTimeInForce(byte tif) {
        putByte(TIME_IN_FORCE_OFFSET, tif);
        return this;
    }

    public ExecutionReportMessage setOrdStatus(byte status) {
        putByte(ORD_STATUS_OFFSET, status);
        return this;
    }

    public ExecutionReportMessage setOrderQty(long qty) {
        putUInt32(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public ExecutionReportMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, price);
        return this;
    }

    public ExecutionReportMessage setRawPrice(long rawPrice) {
        putRawPrice(PRICE_OFFSET, rawPrice);
        return this;
    }

    public ExecutionReportMessage setLastQty(long lastQty) {
        putUInt64(LAST_QTY_OFFSET, lastQty);
        return this;
    }

    public ExecutionReportMessage setLastPx(double lastPx) {
        putPrice(LAST_PX_OFFSET, lastPx);
        return this;
    }

    public ExecutionReportMessage setRawLastPx(long rawLastPx) {
        putRawPrice(LAST_PX_OFFSET, rawLastPx);
        return this;
    }

    public ExecutionReportMessage setLeavesQty(long leavesQty) {
        putUInt64(LEAVES_QTY_OFFSET, leavesQty);
        return this;
    }

    public ExecutionReportMessage setCumQty(long cumQty) {
        putUInt64(CUM_QTY_OFFSET, cumQty);
        return this;
    }

    public ExecutionReportMessage setAvgPx(double avgPx) {
        putPrice(AVG_PX_OFFSET, avgPx);
        return this;
    }

    public ExecutionReportMessage setTransactTime(long transactTime) {
        putUInt64(TRANSACT_TIME_OFFSET, transactTime);
        return this;
    }

    public ExecutionReportMessage setTradeDate(long tradeDate) {
        putUInt64(TRADE_DATE_OFFSET, tradeDate);
        return this;
    }

    public ExecutionReportMessage setContraBroker(String contraBroker) {
        putString(CONTRA_BROKER_OFFSET, contraBroker, CONTRA_BROKER_LENGTH);
        return this;
    }

    public ExecutionReportMessage setExecType(byte execType) {
        putByte(EXEC_TYPE_OFFSET, execType);
        return this;
    }

    // ==================== Helpers ====================

    public boolean isTrade() {
        byte execType = getExecType();
        return execType == EXEC_TYPE_TRADE ||
               execType == EXEC_TYPE_FILL ||
               execType == EXEC_TYPE_PARTIAL_FILL;
    }

    public String getExecTypeDescription() {
        return switch (getExecType()) {
            case EXEC_TYPE_NEW -> "New";
            case EXEC_TYPE_PARTIAL_FILL -> "PartialFill";
            case EXEC_TYPE_FILL -> "Fill";
            case EXEC_TYPE_DONE_FOR_DAY -> "DoneForDay";
            case EXEC_TYPE_CANCELED -> "Canceled";
            case EXEC_TYPE_REPLACED -> "Replaced";
            case EXEC_TYPE_PENDING_CANCEL -> "PendingCancel";
            case EXEC_TYPE_REJECTED -> "Rejected";
            case EXEC_TYPE_TRADE -> "Trade";
            case EXEC_TYPE_TRADE_CORRECT -> "TradeCorrect";
            case EXEC_TYPE_TRADE_CANCEL -> "TradeCancel";
            default -> "Unknown(" + (char) getExecType() + ")";
        };
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "ExecutionReportMessage[not wrapped]";
        }
        return "ExecutionReportMessage[clOrdId=" + getClOrdId() +
               ", orderId=" + getOrderId() +
               ", execId=" + getExecId() +
               ", symbol=" + getSymbol() +
               ", execType=" + getExecTypeDescription() +
               ", lastQty=" + getLastQty() +
               ", lastPx=" + getLastPx() + "]";
    }
}
