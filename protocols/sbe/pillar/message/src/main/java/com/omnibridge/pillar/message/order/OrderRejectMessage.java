package com.omnibridge.pillar.message.order;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Order Reject message.
 * <p>
 * Sent by the gateway when an order is rejected.
 * <p>
 * Message structure (after SeqMsg header):
 * <pre>
 * Offset  Length  Field
 * 0       8       ClOrdID
 * 8       8       OrderID (if assigned before rejection)
 * 16      8       Symbol
 * 24      1       Side
 * 25      1       OrdType
 * 26      2       RejectReasonCode
 * 28      4       OrderQty
 * 32      8       Price
 * 40      8       TransactTime
 * 48      48      RejectReasonText (ASCII)
 * </pre>
 */
public class OrderRejectMessage extends PillarMessage {

    public static final int CL_ORD_ID_OFFSET = 0;
    public static final int ORDER_ID_OFFSET = 8;
    public static final int SYMBOL_OFFSET = 16;
    public static final int SYMBOL_LENGTH = 8;
    public static final int SIDE_OFFSET = 24;
    public static final int ORD_TYPE_OFFSET = 25;
    public static final int REJECT_REASON_CODE_OFFSET = 26;
    public static final int ORDER_QTY_OFFSET = 28;
    public static final int PRICE_OFFSET = 32;
    public static final int TRANSACT_TIME_OFFSET = 40;
    public static final int REJECT_REASON_TEXT_OFFSET = 48;
    public static final int REJECT_REASON_TEXT_LENGTH = 48;

    public static final int BLOCK_LENGTH = 96;

    // Common reject reason codes
    public static final int REJECT_UNKNOWN_SYMBOL = 1;
    public static final int REJECT_EXCHANGE_CLOSED = 2;
    public static final int REJECT_ORDER_EXCEEDS_LIMIT = 3;
    public static final int REJECT_DUPLICATE_ORDER = 4;
    public static final int REJECT_INVALID_PRICE = 5;
    public static final int REJECT_INVALID_QUANTITY = 6;
    public static final int REJECT_UNAUTHORIZED = 7;
    public static final int REJECT_BROKER_OPTION = 8;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.ORDER_REJECT;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.ORDER_REJECT.getTemplateId();
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

    public int getRejectReasonCode() {
        return getUInt16(REJECT_REASON_CODE_OFFSET);
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

    public String getRejectReasonText() {
        return getString(REJECT_REASON_TEXT_OFFSET, REJECT_REASON_TEXT_LENGTH);
    }

    // ==================== Setters ====================

    public OrderRejectMessage setClOrdId(long clOrdId) {
        putUInt64(CL_ORD_ID_OFFSET, clOrdId);
        return this;
    }

    public OrderRejectMessage setOrderId(long orderId) {
        putUInt64(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public OrderRejectMessage setSymbol(String symbol) {
        putString(SYMBOL_OFFSET, symbol, SYMBOL_LENGTH);
        return this;
    }

    public OrderRejectMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public OrderRejectMessage setOrdType(byte ordType) {
        putByte(ORD_TYPE_OFFSET, ordType);
        return this;
    }

    public OrderRejectMessage setRejectReasonCode(int code) {
        putUInt16(REJECT_REASON_CODE_OFFSET, code);
        return this;
    }

    public OrderRejectMessage setOrderQty(long qty) {
        putUInt32(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public OrderRejectMessage setPrice(double price) {
        putPrice(PRICE_OFFSET, price);
        return this;
    }

    public OrderRejectMessage setTransactTime(long transactTime) {
        putUInt64(TRANSACT_TIME_OFFSET, transactTime);
        return this;
    }

    public OrderRejectMessage setRejectReasonText(String text) {
        putString(REJECT_REASON_TEXT_OFFSET, text, REJECT_REASON_TEXT_LENGTH);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "OrderRejectMessage[not wrapped]";
        }
        return "OrderRejectMessage[clOrdId=" + getClOrdId() +
               ", symbol=" + getSymbol() +
               ", rejectCode=" + getRejectReasonCode() +
               ", text=" + getRejectReasonText() + "]";
    }
}
