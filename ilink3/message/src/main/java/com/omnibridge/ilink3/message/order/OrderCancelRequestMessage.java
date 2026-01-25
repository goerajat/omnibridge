package com.omnibridge.ilink3.message.order;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 OrderCancelRequest message (Template ID 516).
 * <p>
 * Sent by the client to cancel an existing order.
 * <p>
 * Block Layout (simplified):
 * <pre>
 * Offset  Length  Field
 * 0       8       OrderID
 * 8       8       PartyDetailsListReqID
 * 16      1       ManualOrderIndicator
 * 17      4       SeqNum
 * 21      20      SenderID
 * 41      20      ClOrdID
 * 61      1       Side (for iLink 3 v9 this field is at byte 61)
 * </pre>
 */
public class OrderCancelRequestMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 516;
    public static final int BLOCK_LENGTH = 62;

    // Field offsets
    private static final int ORDER_ID_OFFSET = 0;
    private static final int PARTY_DETAILS_LIST_REQ_ID_OFFSET = 8;
    private static final int MANUAL_ORDER_INDICATOR_OFFSET = 16;
    private static final int SEQ_NUM_OFFSET = 17;
    private static final int SENDER_ID_OFFSET = 21;
    private static final int SENDER_ID_LENGTH = 20;
    private static final int CL_ORD_ID_OFFSET = 41;
    private static final int CL_ORD_ID_LENGTH = 20;
    private static final int SIDE_OFFSET = 61;

    @Override
    public int getTemplateId() {
        return TEMPLATE_ID;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public ILink3MessageType getMessageType() {
        return ILink3MessageType.ORDER_CANCEL_REQUEST;
    }

    // Order ID (assigned by exchange)
    public OrderCancelRequestMessage setOrderId(long orderId) {
        putLong(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public long getOrderId() {
        return getLong(ORDER_ID_OFFSET);
    }

    // Party Details List Request ID
    public OrderCancelRequestMessage setPartyDetailsListReqId(long id) {
        putLong(PARTY_DETAILS_LIST_REQ_ID_OFFSET, id);
        return this;
    }

    public long getPartyDetailsListReqId() {
        return getLong(PARTY_DETAILS_LIST_REQ_ID_OFFSET);
    }

    // Manual Order Indicator
    public OrderCancelRequestMessage setManualOrderIndicator(byte indicator) {
        putByte(MANUAL_ORDER_INDICATOR_OFFSET, indicator);
        return this;
    }

    public byte getManualOrderIndicator() {
        return getByte(MANUAL_ORDER_INDICATOR_OFFSET);
    }

    // Sequence Number
    public OrderCancelRequestMessage setSeqNum(int seqNum) {
        putInt(SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    public int getSeqNum() {
        return getInt(SEQ_NUM_OFFSET);
    }

    // Sender ID
    public OrderCancelRequestMessage setSenderId(String senderId) {
        putString(SENDER_ID_OFFSET, SENDER_ID_LENGTH, senderId);
        return this;
    }

    public String getSenderId() {
        return getString(SENDER_ID_OFFSET, SENDER_ID_LENGTH);
    }

    // Client Order ID (original order's ClOrdId)
    public OrderCancelRequestMessage setClOrdId(String clOrdId) {
        putString(CL_ORD_ID_OFFSET, CL_ORD_ID_LENGTH, clOrdId);
        return this;
    }

    public String getClOrdId() {
        return getString(CL_ORD_ID_OFFSET, CL_ORD_ID_LENGTH);
    }

    // Side
    public OrderCancelRequestMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public byte getSide() {
        return getByte(SIDE_OFFSET);
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "OrderCancelRequestMessage[not wrapped]";
        }
        return String.format("OrderCancelRequestMessage[clOrdId=%s, orderId=%d, side=%d]",
                getClOrdId(), getOrderId(), getSide());
    }
}
