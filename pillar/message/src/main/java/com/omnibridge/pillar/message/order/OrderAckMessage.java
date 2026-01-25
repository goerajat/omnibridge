package com.omnibridge.pillar.message.order;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Order Acknowledgment message.
 * <p>
 * Sent by the gateway to acknowledge receipt of a new order.
 * <p>
 * Message structure (after SeqMsg header):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClOrdID
 * 8       8       OrderID (Exchange assigned)
 * 16      8       Symbol
 * 24      1       Side
 * 25      1       OrdType
 * 26      1       TimeInForce
 * 27      1       OrdStatus (0=New, 1=PartiallyFilled, etc.)
 * 28      4       OrderQty
 * 32      8       Price
 * 40      8       TransactTime
 * 48      8       LeavesQty
 * 56      8       CumQty
 * 64      16      Reserved
 * </pre>
 */
public class OrderAckMessage extends PillarMessage {

    public static final int CL_ORD_ID_OFFSET = 0;
    public static final int ORDER_ID_OFFSET = 8;
    public static final int SYMBOL_OFFSET = 16;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 24;
    public static final int ORD_TYPE_OFFSET = 25;
    public static final int TIME_IN_FORCE_OFFSET = 26;
    public static final int ORD_STATUS_OFFSET = 27;
    public static final int ORDER_QTY_OFFSET = 28;
    public static final int PRICE_OFFSET = 32;
    public static final int TRANSACT_TIME_OFFSET = 40;
    public static final int LEAVES_QTY_OFFSET = 48;
    public static final int CUM_QTY_OFFSET = 56;

    public static final int BLOCK_LENGTH = 80;

    // Order status values
    public static final byte ORD_STATUS_NEW = 0;
    public static final byte ORD_STATUS_PARTIALLY_FILLED = 1;
    public static final byte ORD_STATUS_FILLED = 2;
    public static final byte ORD_STATUS_DONE_FOR_DAY = 3;
    public static final byte ORD_STATUS_CANCELED = 4;
    public static final byte ORD_STATUS_REPLACED = 5;
    public static final byte ORD_STATUS_PENDING_CANCEL = 6;
    public static final byte ORD_STATUS_REJECTED = 8;
    public static final byte ORD_STATUS_PENDING_NEW = 10;
    public static final byte ORD_STATUS_PENDING_REPLACE = 14;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.ORDER_ACK;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.ORDER_ACK.getTemplateId();
    }

    // ==================== Getters ====================

    public long getClOrdId() {
        return getUInt64(CL_ORD_ID_OFFSET);
    }

    public long getOrderId() {
        return getUInt64(ORDER_ID_OFFSET);
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

    public long getTransactTime() {
        return getUInt64(TRANSACT_TIME_OFFSET);
    }

    public long getLeavesQty() {
        return getUInt64(LEAVES_QTY_OFFSET);
    }

    public long getCumQty() {
        return getUInt64(CUM_QTY_OFFSET);
    }

    // ==================== Setters ====================

    public OrderAckMessage setClOrdId(long clOrdId) {
        putUInt64(CL_ORD_ID_OFFSET, clOrdId);
        return this;
    }

    public OrderAckMessage setOrderId(long orderId) {
        putUInt64(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public OrderAckMessage setSymbol(String symbol) {
        putString(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public OrderAckMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public OrderAckMessage setOrdType(byte ordType) {
        putByte(ORD_TYPE_OFFSET, ordType);
        return this;
    }

    public OrderAckMessage setTimeInForce(byte tif) {
        putByte(TIME_IN_FORCE_OFFSET, tif);
        return this;
    }

    public OrderAckMessage setOrdStatus(byte status) {
        putByte(ORD_STATUS_OFFSET, status);
        return this;
    }

    public OrderAckMessage setOrderQty(long qty) {
        putUInt32(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public OrderAckMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, price);
        return this;
    }

    public OrderAckMessage setTransactTime(long transactTime) {
        putUInt64(TRANSACT_TIME_OFFSET, transactTime);
        return this;
    }

    public OrderAckMessage setLeavesQty(long leavesQty) {
        putUInt64(LEAVES_QTY_OFFSET, leavesQty);
        return this;
    }

    public OrderAckMessage setCumQty(long cumQty) {
        putUInt64(CUM_QTY_OFFSET, cumQty);
        return this;
    }

    // ==================== Helpers ====================

    public String getOrdStatusDescription() {
        return switch (getOrdStatus()) {
            case ORD_STATUS_NEW -> "New";
            case ORD_STATUS_PARTIALLY_FILLED -> "PartiallyFilled";
            case ORD_STATUS_FILLED -> "Filled";
            case ORD_STATUS_DONE_FOR_DAY -> "DoneForDay";
            case ORD_STATUS_CANCELED -> "Canceled";
            case ORD_STATUS_REPLACED -> "Replaced";
            case ORD_STATUS_PENDING_CANCEL -> "PendingCancel";
            case ORD_STATUS_REJECTED -> "Rejected";
            case ORD_STATUS_PENDING_NEW -> "PendingNew";
            case ORD_STATUS_PENDING_REPLACE -> "PendingReplace";
            default -> "Unknown(" + getOrdStatus() + ")";
        };
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "OrderAckMessage[not wrapped]";
        }
        return "OrderAckMessage[clOrdId=" + getClOrdId() +
               ", orderId=" + getOrderId() +
               ", symbol=" + getSymbol() +
               ", status=" + getOrdStatusDescription() + "]";
    }
}
