package com.omnibridge.pillar.message.order;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Replace Acknowledgment message.
 * <p>
 * Sent by the gateway to confirm order modification/replacement.
 * <p>
 * Message structure (after SeqMsg header):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClOrdID (Replace request's ClOrdID)
 * 8       8       OrigClOrdID (Original order's ClOrdID)
 * 16      8       OrderID
 * 24      8       Symbol
 * 32      1       Side
 * 33      1       OrdType
 * 34      1       TimeInForce
 * 35      1       OrdStatus
 * 36      4       OrderQty
 * 40      8       Price
 * 48      8       LeavesQty
 * 56      8       CumQty
 * 64      8       TransactTime
 * 72      16      Reserved
 * </pre>
 */
public class ReplaceAckMessage extends PillarMessage {

    public static final int CL_ORD_ID_OFFSET = 0;
    public static final int ORIG_CL_ORD_ID_OFFSET = 8;
    public static final int ORDER_ID_OFFSET = 16;
    public static final int SYMBOL_OFFSET = 24;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 32;
    public static final int ORD_TYPE_OFFSET = 33;
    public static final int TIME_IN_FORCE_OFFSET = 34;
    public static final int ORD_STATUS_OFFSET = 35;
    public static final int ORDER_QTY_OFFSET = 36;
    public static final int PRICE_OFFSET = 40;
    public static final int LEAVES_QTY_OFFSET = 48;
    public static final int CUM_QTY_OFFSET = 56;
    public static final int TRANSACT_TIME_OFFSET = 64;

    public static final int BLOCK_LENGTH = 88;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.REPLACE_ACK;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.REPLACE_ACK.getTemplateId();
    }

    // ==================== Getters ====================

    public long getClOrdId() {
        return getUInt64(CL_ORD_ID_OFFSET);
    }

    public long getOrigClOrdId() {
        return getUInt64(ORIG_CL_ORD_ID_OFFSET);
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

    public long getLeavesQty() {
        return getUInt64(LEAVES_QTY_OFFSET);
    }

    public long getCumQty() {
        return getUInt64(CUM_QTY_OFFSET);
    }

    public long getTransactTime() {
        return getUInt64(TRANSACT_TIME_OFFSET);
    }

    // ==================== Setters ====================

    public ReplaceAckMessage setClOrdId(long clOrdId) {
        putUInt64(CL_ORD_ID_OFFSET, clOrdId);
        return this;
    }

    public ReplaceAckMessage setOrigClOrdId(long origClOrdId) {
        putUInt64(ORIG_CL_ORD_ID_OFFSET, origClOrdId);
        return this;
    }

    public ReplaceAckMessage setOrderId(long orderId) {
        putUInt64(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public ReplaceAckMessage setSymbol(String symbol) {
        putString(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public ReplaceAckMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public ReplaceAckMessage setOrdType(byte ordType) {
        putByte(ORD_TYPE_OFFSET, ordType);
        return this;
    }

    public ReplaceAckMessage setTimeInForce(byte tif) {
        putByte(TIME_IN_FORCE_OFFSET, tif);
        return this;
    }

    public ReplaceAckMessage setOrdStatus(byte status) {
        putByte(ORD_STATUS_OFFSET, status);
        return this;
    }

    public ReplaceAckMessage setOrderQty(long qty) {
        putUInt32(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public ReplaceAckMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, price);
        return this;
    }

    public ReplaceAckMessage setLeavesQty(long leavesQty) {
        putUInt64(LEAVES_QTY_OFFSET, leavesQty);
        return this;
    }

    public ReplaceAckMessage setCumQty(long cumQty) {
        putUInt64(CUM_QTY_OFFSET, cumQty);
        return this;
    }

    public ReplaceAckMessage setTransactTime(long transactTime) {
        putUInt64(TRANSACT_TIME_OFFSET, transactTime);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "ReplaceAckMessage[not wrapped]";
        }
        return "ReplaceAckMessage[clOrdId=" + getClOrdId() +
               ", origClOrdId=" + getOrigClOrdId() +
               ", orderId=" + getOrderId() +
               ", qty=" + getOrderQty() +
               ", price=" + getPrice() + "]";
    }
}
