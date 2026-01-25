package com.omnibridge.optiq.message.order;

import com.omnibridge.optiq.message.OptiqMessage;
import com.omnibridge.optiq.message.OptiqMessageType;

/**
 * Optiq CancelOrder message (Template ID 202).
 * <p>
 * Sent by the client to cancel an existing order.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       8       ClMsgSeqNum
 * 8       4       FirmID
 * 12      8       SendingTime
 * 20      8       ClientOrderID
 * 28      8       OrderID (exchange assigned)
 * 36      8       OrigClientOrderID
 * 44      4       SymbolIndex
 * </pre>
 */
public class CancelOrderMessage extends OptiqMessage {

    public static final int TEMPLATE_ID = 202;
    public static final int BLOCK_LENGTH = 48;

    // Field offsets
    private static final int CL_MSG_SEQ_NUM_OFFSET = 0;
    private static final int FIRM_ID_OFFSET = 8;
    private static final int SENDING_TIME_OFFSET = 12;
    private static final int CLIENT_ORDER_ID_OFFSET = 20;
    private static final int ORDER_ID_OFFSET = 28;
    private static final int ORIG_CLIENT_ORDER_ID_OFFSET = 36;
    private static final int SYMBOL_INDEX_OFFSET = 44;

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
        return OptiqMessageType.CANCEL_ORDER;
    }

    // Client Message Sequence Number
    public CancelOrderMessage setClMsgSeqNum(long seqNum) {
        putLong(CL_MSG_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    public long getClMsgSeqNum() {
        return getLong(CL_MSG_SEQ_NUM_OFFSET);
    }

    // Firm ID
    public CancelOrderMessage setFirmId(int firmId) {
        putInt(FIRM_ID_OFFSET, firmId);
        return this;
    }

    public int getFirmId() {
        return getInt(FIRM_ID_OFFSET);
    }

    // Sending Time
    public CancelOrderMessage setSendingTime(long time) {
        putLong(SENDING_TIME_OFFSET, time);
        return this;
    }

    public long getSendingTime() {
        return getLong(SENDING_TIME_OFFSET);
    }

    // Client Order ID
    public CancelOrderMessage setClientOrderId(long orderId) {
        putLong(CLIENT_ORDER_ID_OFFSET, orderId);
        return this;
    }

    public long getClientOrderId() {
        return getLong(CLIENT_ORDER_ID_OFFSET);
    }

    // Order ID (exchange assigned)
    public CancelOrderMessage setOrderId(long orderId) {
        putLong(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public long getOrderId() {
        return getLong(ORDER_ID_OFFSET);
    }

    // Original Client Order ID
    public CancelOrderMessage setOrigClientOrderId(long orderId) {
        putLong(ORIG_CLIENT_ORDER_ID_OFFSET, orderId);
        return this;
    }

    public long getOrigClientOrderId() {
        return getLong(ORIG_CLIENT_ORDER_ID_OFFSET);
    }

    // Symbol Index
    public CancelOrderMessage setSymbolIndex(int symbolIndex) {
        putInt(SYMBOL_INDEX_OFFSET, symbolIndex);
        return this;
    }

    public int getSymbolIndex() {
        return getInt(SYMBOL_INDEX_OFFSET);
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "CancelOrderMessage[not wrapped]";
        }
        return String.format("CancelOrderMessage[clientOrderId=%d, orderId=%d, symbolIndex=%d]",
                getClientOrderId(), getOrderId(), getSymbolIndex());
    }
}
