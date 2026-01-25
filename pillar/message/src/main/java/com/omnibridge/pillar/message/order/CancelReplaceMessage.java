package com.omnibridge.pillar.message.order;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Cancel/Replace Order message.
 * <p>
 * Sent by the client to modify an existing order.
 * <p>
 * Message structure (after SeqMsg header):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClOrdID (Client Order ID for this replace request)
 * 8       8       OrigClOrdID (Original order's ClOrdID)
 * 16      8       OrderID (Exchange assigned order ID, optional)
 * 24      8       Symbol
 * 32      1       Side
 * 33      1       OrdType
 * 34      1       TimeInForce
 * 35      1       ExecInst
 * 36      4       OrderQty
 * 40      8       Price (scaled by 10^8)
 * 48      8       StopPx (scaled by 10^8)
 * 56      8       MinQty
 * 64      8       MaxFloor
 * 72      8       ExpireTime
 * 80      8       TransactTime
 * 88      16      MPID
 * 104     16      Account
 * 120     8       Reserved
 * 128     8       Reserved
 * 136     8       Reserved
 * </pre>
 */
public class CancelReplaceMessage extends PillarMessage {

    public static final int CL_ORD_ID_OFFSET = 0;
    public static final int ORIG_CL_ORD_ID_OFFSET = 8;
    public static final int ORDER_ID_OFFSET = 16;
    public static final int SYMBOL_OFFSET = 24;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 32;
    public static final int ORD_TYPE_OFFSET = 33;
    public static final int TIME_IN_FORCE_OFFSET = 34;
    public static final int EXEC_INST_OFFSET = 35;
    public static final int ORDER_QTY_OFFSET = 36;
    public static final int PRICE_OFFSET = 40;
    public static final int STOP_PX_OFFSET = 48;
    public static final int MIN_QTY_OFFSET = 56;
    public static final int MAX_FLOOR_OFFSET = 64;
    public static final int EXPIRE_TIME_OFFSET = 72;
    public static final int TRANSACT_TIME_OFFSET = 80;
    public static final int MPID_OFFSET = 88;
    public static final int MPID_LENGTH = 16;
    public static final int ACCOUNT_OFFSET = 104;
    public static final int ACCOUNT_LENGTH = 16;

    public static final int BLOCK_LENGTH = 144;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.CANCEL_REPLACE;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.CANCEL_REPLACE.getTemplateId();
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

    public byte getExecInst() {
        return getByte(EXEC_INST_OFFSET);
    }

    public long getOrderQty() {
        return getUInt32(ORDER_QTY_OFFSET);
    }

    public double getPrice() {
        return getPrice(PRICE_OFFSET);
    }

    public double getStopPx() {
        return getPrice(STOP_PX_OFFSET);
    }

    public long getMinQty() {
        return getUInt64(MIN_QTY_OFFSET);
    }

    public long getMaxFloor() {
        return getUInt64(MAX_FLOOR_OFFSET);
    }

    public long getExpireTime() {
        return getUInt64(EXPIRE_TIME_OFFSET);
    }

    public long getTransactTime() {
        return getUInt64(TRANSACT_TIME_OFFSET);
    }

    public String getMpid() {
        return getString(MPID_OFFSET, MPID_LENGTH);
    }

    public String getAccount() {
        return getString(ACCOUNT_OFFSET, ACCOUNT_LENGTH);
    }

    // ==================== Setters ====================

    public CancelReplaceMessage setClOrdId(long clOrdId) {
        putUInt64(CL_ORD_ID_OFFSET, clOrdId);
        return this;
    }

    public CancelReplaceMessage setOrigClOrdId(long origClOrdId) {
        putUInt64(ORIG_CL_ORD_ID_OFFSET, origClOrdId);
        return this;
    }

    public CancelReplaceMessage setOrderId(long orderId) {
        putUInt64(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public CancelReplaceMessage setSymbol(String symbol) {
        putString(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public CancelReplaceMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public CancelReplaceMessage setOrdType(byte ordType) {
        putByte(ORD_TYPE_OFFSET, ordType);
        return this;
    }

    public CancelReplaceMessage setTimeInForce(byte tif) {
        putByte(TIME_IN_FORCE_OFFSET, tif);
        return this;
    }

    public CancelReplaceMessage setExecInst(byte execInst) {
        putByte(EXEC_INST_OFFSET, execInst);
        return this;
    }

    public CancelReplaceMessage setOrderQty(long qty) {
        putUInt32(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public CancelReplaceMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, price);
        return this;
    }

    public CancelReplaceMessage setStopPx(double stopPx) {
        putPrice(STOP_PX_OFFSET, stopPx);
        return this;
    }

    public CancelReplaceMessage setMinQty(long minQty) {
        putUInt64(MIN_QTY_OFFSET, minQty);
        return this;
    }

    public CancelReplaceMessage setMaxFloor(long maxFloor) {
        putUInt64(MAX_FLOOR_OFFSET, maxFloor);
        return this;
    }

    public CancelReplaceMessage setExpireTime(long expireTime) {
        putUInt64(EXPIRE_TIME_OFFSET, expireTime);
        return this;
    }

    public CancelReplaceMessage setTransactTime(long transactTime) {
        putUInt64(TRANSACT_TIME_OFFSET, transactTime);
        return this;
    }

    public CancelReplaceMessage setMpid(String mpid) {
        putString(MPID_OFFSET, mpid, MPID_LENGTH);
        return this;
    }

    public CancelReplaceMessage setAccount(String account) {
        putString(ACCOUNT_OFFSET, account, ACCOUNT_LENGTH);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "CancelReplaceMessage[not wrapped]";
        }
        return "CancelReplaceMessage[clOrdId=" + getClOrdId() +
               ", origClOrdId=" + getOrigClOrdId() +
               ", symbol=" + getSymbol() +
               ", qty=" + getOrderQty() +
               ", price=" + getPrice() + "]";
    }
}
