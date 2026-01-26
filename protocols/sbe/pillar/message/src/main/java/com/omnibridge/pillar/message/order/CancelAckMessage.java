package com.omnibridge.pillar.message.order;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Cancel Acknowledgment message.
 * <p>
 * Sent by the gateway to confirm order cancellation.
 * <p>
 * Message structure (after SeqMsg header):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClOrdID (Cancel request's ClOrdID)
 * 8       8       OrigClOrdID (Original order's ClOrdID)
 * 16      8       OrderID
 * 24      8       Symbol
 * 32      1       Side
 * 33      1       OrdStatus
 * 34      2       Reserved
 * 36      4       CumQty
 * 40      8       LeavesQty (should be 0 for full cancel)
 * 48      8       TransactTime
 * 56      8       Reserved
 * </pre>
 */
public class CancelAckMessage extends PillarMessage {

    public static final int CL_ORD_ID_OFFSET = 0;
    public static final int ORIG_CL_ORD_ID_OFFSET = 8;
    public static final int ORDER_ID_OFFSET = 16;
    public static final int SYMBOL_OFFSET = 24;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 32;
    public static final int ORD_STATUS_OFFSET = 33;
    public static final int CUM_QTY_OFFSET = 36;
    public static final int LEAVES_QTY_OFFSET = 40;
    public static final int TRANSACT_TIME_OFFSET = 48;

    public static final int BLOCK_LENGTH = 64;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.CANCEL_ACK;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.CANCEL_ACK.getTemplateId();
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

    public byte getOrdStatus() {
        return getByte(ORD_STATUS_OFFSET);
    }

    public long getCumQty() {
        return getUInt32(CUM_QTY_OFFSET);
    }

    public long getLeavesQty() {
        return getUInt64(LEAVES_QTY_OFFSET);
    }

    public long getTransactTime() {
        return getUInt64(TRANSACT_TIME_OFFSET);
    }

    // ==================== Setters ====================

    public CancelAckMessage setClOrdId(long clOrdId) {
        putUInt64(CL_ORD_ID_OFFSET, clOrdId);
        return this;
    }

    public CancelAckMessage setOrigClOrdId(long origClOrdId) {
        putUInt64(ORIG_CL_ORD_ID_OFFSET, origClOrdId);
        return this;
    }

    public CancelAckMessage setOrderId(long orderId) {
        putUInt64(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public CancelAckMessage setSymbol(String symbol) {
        putString(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public CancelAckMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public CancelAckMessage setOrdStatus(byte status) {
        putByte(ORD_STATUS_OFFSET, status);
        return this;
    }

    public CancelAckMessage setCumQty(long cumQty) {
        putUInt32(CUM_QTY_OFFSET, cumQty);
        return this;
    }

    public CancelAckMessage setLeavesQty(long leavesQty) {
        putUInt64(LEAVES_QTY_OFFSET, leavesQty);
        return this;
    }

    public CancelAckMessage setTransactTime(long transactTime) {
        putUInt64(TRANSACT_TIME_OFFSET, transactTime);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "CancelAckMessage[not wrapped]";
        }
        return "CancelAckMessage[clOrdId=" + getClOrdId() +
               ", origClOrdId=" + getOrigClOrdId() +
               ", orderId=" + getOrderId() +
               ", symbol=" + getSymbol() + "]";
    }
}
