package com.omnibridge.pillar.message.order;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Replace Reject message.
 * <p>
 * Sent by the gateway when a replace request is rejected.
 * <p>
 * Message structure (after SeqMsg header):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClOrdID (Replace request's ClOrdID)
 * 8       8       OrigClOrdID (Original order's ClOrdID)
 * 16      8       OrderID
 * 24      8       Symbol
 * 32      1       Side
 * 33      1       OrdStatus
 * 34      2       CxlRejReason
 * 36      4       Reserved
 * 40      8       TransactTime
 * 48      48      Text (Rejection reason text)
 * </pre>
 */
public class ReplaceRejectMessage extends PillarMessage {

    public static final int CL_ORD_ID_OFFSET = 0;
    public static final int ORIG_CL_ORD_ID_OFFSET = 8;
    public static final int ORDER_ID_OFFSET = 16;
    public static final int SYMBOL_OFFSET = 24;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 32;
    public static final int ORD_STATUS_OFFSET = 33;
    public static final int CXL_REJ_REASON_OFFSET = 34;
    public static final int TRANSACT_TIME_OFFSET = 40;
    public static final int TEXT_OFFSET = 48;
    public static final int TEXT_LENGTH = 48;

    public static final int BLOCK_LENGTH = 96;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.REPLACE_REJECT;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.REPLACE_REJECT.getTemplateId();
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

    public int getCxlRejReason() {
        return getUInt16(CXL_REJ_REASON_OFFSET);
    }

    public long getTransactTime() {
        return getUInt64(TRANSACT_TIME_OFFSET);
    }

    public String getText() {
        return getString(TEXT_OFFSET, TEXT_LENGTH);
    }

    // ==================== Setters ====================

    public ReplaceRejectMessage setClOrdId(long clOrdId) {
        putUInt64(CL_ORD_ID_OFFSET, clOrdId);
        return this;
    }

    public ReplaceRejectMessage setOrigClOrdId(long origClOrdId) {
        putUInt64(ORIG_CL_ORD_ID_OFFSET, origClOrdId);
        return this;
    }

    public ReplaceRejectMessage setOrderId(long orderId) {
        putUInt64(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public ReplaceRejectMessage setSymbol(String symbol) {
        putString(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public ReplaceRejectMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public ReplaceRejectMessage setOrdStatus(byte status) {
        putByte(ORD_STATUS_OFFSET, status);
        return this;
    }

    public ReplaceRejectMessage setCxlRejReason(int reason) {
        putUInt16(CXL_REJ_REASON_OFFSET, reason);
        return this;
    }

    public ReplaceRejectMessage setTransactTime(long transactTime) {
        putUInt64(TRANSACT_TIME_OFFSET, transactTime);
        return this;
    }

    public ReplaceRejectMessage setText(String text) {
        putString(TEXT_OFFSET, text, TEXT_LENGTH);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "ReplaceRejectMessage[not wrapped]";
        }
        return "ReplaceRejectMessage[clOrdId=" + getClOrdId() +
               ", origClOrdId=" + getOrigClOrdId() +
               ", reason=" + getCxlRejReason() +
               ", text=" + getText() + "]";
    }
}
