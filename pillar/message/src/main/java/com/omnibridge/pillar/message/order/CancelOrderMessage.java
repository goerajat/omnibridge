package com.omnibridge.pillar.message.order;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Cancel Order message.
 * <p>
 * Sent by the client to cancel an existing order.
 * <p>
 * Message structure (after SeqMsg header):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClOrdID (Client Order ID for this cancel request)
 * 8       8       OrigClOrdID (Original order's ClOrdID)
 * 16      8       OrderID (Exchange assigned order ID, optional)
 * 24      8       Symbol
 * 32      1       Side
 * 33      7       Reserved
 * 40      8       TransactTime
 * 48      16      Reserved
 * </pre>
 */
public class CancelOrderMessage extends PillarMessage {

    public static final int CL_ORD_ID_OFFSET = 0;
    public static final int ORIG_CL_ORD_ID_OFFSET = 8;
    public static final int ORDER_ID_OFFSET = 16;
    public static final int SYMBOL_OFFSET = 24;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 32;
    public static final int TRANSACT_TIME_OFFSET = 40;

    public static final int BLOCK_LENGTH = 64;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.CANCEL_ORDER;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.CANCEL_ORDER.getTemplateId();
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

    public long getTransactTime() {
        return getUInt64(TRANSACT_TIME_OFFSET);
    }

    // ==================== Setters ====================

    public CancelOrderMessage setClOrdId(long clOrdId) {
        putUInt64(CL_ORD_ID_OFFSET, clOrdId);
        return this;
    }

    public CancelOrderMessage setOrigClOrdId(long origClOrdId) {
        putUInt64(ORIG_CL_ORD_ID_OFFSET, origClOrdId);
        return this;
    }

    public CancelOrderMessage setOrderId(long orderId) {
        putUInt64(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public CancelOrderMessage setSymbol(String symbol) {
        putString(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public CancelOrderMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public CancelOrderMessage setTransactTime(long transactTime) {
        putUInt64(TRANSACT_TIME_OFFSET, transactTime);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "CancelOrderMessage[not wrapped]";
        }
        return "CancelOrderMessage[clOrdId=" + getClOrdId() +
               ", origClOrdId=" + getOrigClOrdId() +
               ", symbol=" + getSymbol() + "]";
    }
}
