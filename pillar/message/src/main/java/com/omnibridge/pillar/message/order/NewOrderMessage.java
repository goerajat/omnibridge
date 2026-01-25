package com.omnibridge.pillar.message.order;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar New Order message.
 * <p>
 * Sent by the client to submit a new order. This message is also used
 * for Cancel/Replace when OrigClOrdID is non-zero.
 * <p>
 * Message structure (after SeqMsg header):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClOrdID (Client Order ID)
 * 8       8       OrigClOrdID (0 for new order, original ID for replace)
 * 16      8       Symbol
 * 24      1       Side (1=Buy, 2=Sell, 5=SellShort, 6=SellShortExempt)
 * 25      1       OrdType (1=Market, 2=Limit, 3=Stop, 4=StopLimit)
 * 26      1       TimeInForce (0=Day, 1=GTC, 2=AtOpen, 3=IOC, 4=FOK, 6=GTD)
 * 27      1       ExecInst (flags: 1=AllOrNone, 2=ParticipateDoNotInitiate, etc.)
 * 28      4       OrderQty
 * 32      8       Price (scaled by 10^8)
 * 40      8       StopPx (scaled by 10^8)
 * 48      8       MinQty
 * 56      8       MaxFloor (display quantity)
 * 64      8       ExpireTime (for GTD orders)
 * 72      8       TransactTime
 * 80      16      MPID (Market Participant ID)
 * 96      16      Account
 * 112     8       Reserved
 * 120     8       Reserved
 * </pre>
 */
public class NewOrderMessage extends PillarMessage {

    public static final int CL_ORD_ID_OFFSET = 0;
    public static final int ORIG_CL_ORD_ID_OFFSET = 8;
    public static final int SYMBOL_OFFSET = 16;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 24;
    public static final int ORD_TYPE_OFFSET = 25;
    public static final int TIME_IN_FORCE_OFFSET = 26;
    public static final int EXEC_INST_OFFSET = 27;
    public static final int ORDER_QTY_OFFSET = 28;
    public static final int PRICE_OFFSET = 32;
    public static final int STOP_PX_OFFSET = 40;
    public static final int MIN_QTY_OFFSET = 48;
    public static final int MAX_FLOOR_OFFSET = 56;
    public static final int EXPIRE_TIME_OFFSET = 64;
    public static final int TRANSACT_TIME_OFFSET = 72;
    public static final int MPID_OFFSET = 80;
    public static final int MPID_LENGTH = 16;
    public static final int ACCOUNT_OFFSET = 96;
    public static final int ACCOUNT_LENGTH = 16;

    public static final int BLOCK_LENGTH = 128;

    // Side values
    public static final byte SIDE_BUY = 1;
    public static final byte SIDE_SELL = 2;
    public static final byte SIDE_SELL_SHORT = 5;
    public static final byte SIDE_SELL_SHORT_EXEMPT = 6;

    // Order type values
    public static final byte ORD_TYPE_MARKET = 1;
    public static final byte ORD_TYPE_LIMIT = 2;
    public static final byte ORD_TYPE_STOP = 3;
    public static final byte ORD_TYPE_STOP_LIMIT = 4;

    // Time in force values
    public static final byte TIF_DAY = 0;
    public static final byte TIF_GTC = 1;
    public static final byte TIF_AT_OPEN = 2;
    public static final byte TIF_IOC = 3;
    public static final byte TIF_FOK = 4;
    public static final byte TIF_GTD = 6;

    // ExecInst flags
    public static final byte EXEC_INST_ALL_OR_NONE = 0x01;
    public static final byte EXEC_INST_PARTICIPATE_DONT_INITIATE = 0x02;
    public static final byte EXEC_INST_DO_NOT_ROUTE = 0x04;
    public static final byte EXEC_INST_INTERMARKET_SWEEP = 0x08;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.NEW_ORDER;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.NEW_ORDER.getTemplateId();
    }

    // ==================== Getters ====================

    public long getClOrdId() {
        return getUInt64(CL_ORD_ID_OFFSET);
    }

    public long getOrigClOrdId() {
        return getUInt64(ORIG_CL_ORD_ID_OFFSET);
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

    public long getRawPrice() {
        return getRawPrice(PRICE_OFFSET);
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

    public NewOrderMessage setClOrdId(long clOrdId) {
        putUInt64(CL_ORD_ID_OFFSET, clOrdId);
        return this;
    }

    public NewOrderMessage setOrigClOrdId(long origClOrdId) {
        putUInt64(ORIG_CL_ORD_ID_OFFSET, origClOrdId);
        return this;
    }

    public NewOrderMessage setSymbol(String symbol) {
        putString(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public NewOrderMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public NewOrderMessage setOrdType(byte ordType) {
        putByte(ORD_TYPE_OFFSET, ordType);
        return this;
    }

    public NewOrderMessage setTimeInForce(byte tif) {
        putByte(TIME_IN_FORCE_OFFSET, tif);
        return this;
    }

    public NewOrderMessage setExecInst(byte execInst) {
        putByte(EXEC_INST_OFFSET, execInst);
        return this;
    }

    public NewOrderMessage setOrderQty(long qty) {
        putUInt32(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public NewOrderMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, price);
        return this;
    }

    public NewOrderMessage setRawPrice(long rawPrice) {
        putRawPrice(PRICE_OFFSET, rawPrice);
        return this;
    }

    public NewOrderMessage setStopPx(double stopPx) {
        putPrice(STOP_PX_OFFSET, stopPx);
        return this;
    }

    public NewOrderMessage setMinQty(long minQty) {
        putUInt64(MIN_QTY_OFFSET, minQty);
        return this;
    }

    public NewOrderMessage setMaxFloor(long maxFloor) {
        putUInt64(MAX_FLOOR_OFFSET, maxFloor);
        return this;
    }

    public NewOrderMessage setExpireTime(long expireTime) {
        putUInt64(EXPIRE_TIME_OFFSET, expireTime);
        return this;
    }

    public NewOrderMessage setTransactTime(long transactTime) {
        putUInt64(TRANSACT_TIME_OFFSET, transactTime);
        return this;
    }

    public NewOrderMessage setMpid(String mpid) {
        putString(MPID_OFFSET, mpid, MPID_LENGTH);
        return this;
    }

    public NewOrderMessage setAccount(String account) {
        putString(ACCOUNT_OFFSET, account, ACCOUNT_LENGTH);
        return this;
    }

    // ==================== Helpers ====================

    public boolean isNewOrder() {
        return getOrigClOrdId() == 0;
    }

    public boolean isCancelReplace() {
        return getOrigClOrdId() != 0;
    }

    public String getSideDescription() {
        return switch (getSide()) {
            case SIDE_BUY -> "Buy";
            case SIDE_SELL -> "Sell";
            case SIDE_SELL_SHORT -> "SellShort";
            case SIDE_SELL_SHORT_EXEMPT -> "SellShortExempt";
            default -> "Unknown";
        };
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "NewOrderMessage[not wrapped]";
        }
        return "NewOrderMessage[clOrdId=" + getClOrdId() +
               ", symbol=" + getSymbol() +
               ", side=" + getSideDescription() +
               ", qty=" + getOrderQty() +
               ", price=" + getPrice() + "]";
    }
}
