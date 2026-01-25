package com.omnibridge.ilink3.message.order;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 ExecutionReportNew message (Template ID 522).
 * <p>
 * Sent by the CME Globex system to acknowledge a new order.
 * <p>
 * Block Layout (simplified):
 * <pre>
 * Offset  Length  Field
 * 0       4       SeqNum
 * 4       8       UUID
 * 12      20      ExecID
 * 32      20      SenderID
 * 52      20      ClOrdID
 * 72      8       PartyDetailsListReqID
 * 80      8       OrderID
 * 88      8       Price
 * 96      8       StopPx
 * 104     8       TransactTime
 * 112     8       SendingTimeEpoch
 * 120     8       OrderRequestID
 * 128     4       OrderQty
 * 132     4       CumQty
 * 136     4       LeavesQty
 * 140     2       ExpireDate
 * </pre>
 */
public class ExecutionReportNewMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 522;
    public static final int BLOCK_LENGTH = 142;

    // Field offsets
    private static final int SEQ_NUM_OFFSET = 0;
    private static final int UUID_OFFSET = 4;
    private static final int EXEC_ID_OFFSET = 12;
    private static final int EXEC_ID_LENGTH = 20;
    private static final int SENDER_ID_OFFSET = 32;
    private static final int SENDER_ID_LENGTH = 20;
    private static final int CL_ORD_ID_OFFSET = 52;
    private static final int CL_ORD_ID_LENGTH = 20;
    private static final int PARTY_DETAILS_LIST_REQ_ID_OFFSET = 72;
    private static final int ORDER_ID_OFFSET = 80;
    private static final int PRICE_OFFSET = 88;
    private static final int STOP_PX_OFFSET = 96;
    private static final int TRANSACT_TIME_OFFSET = 104;
    private static final int SENDING_TIME_EPOCH_OFFSET = 112;
    private static final int ORDER_REQUEST_ID_OFFSET = 120;
    private static final int ORDER_QTY_OFFSET = 128;
    private static final int CUM_QTY_OFFSET = 132;
    private static final int LEAVES_QTY_OFFSET = 136;
    private static final int EXPIRE_DATE_OFFSET = 140;

    // Price multiplier (10^9 for PRICE9)
    private static final double PRICE_MULTIPLIER = 1_000_000_000.0;

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
        return ILink3MessageType.EXECUTION_REPORT_NEW;
    }

    // Sequence Number
    public int getSeqNum() {
        return getInt(SEQ_NUM_OFFSET);
    }

    public ExecutionReportNewMessage setSeqNum(int seqNum) {
        putInt(SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    // UUID
    public long getUuid() {
        return getLong(UUID_OFFSET);
    }

    public ExecutionReportNewMessage setUuid(long uuid) {
        putLong(UUID_OFFSET, uuid);
        return this;
    }

    // Execution ID
    public String getExecId() {
        return getString(EXEC_ID_OFFSET, EXEC_ID_LENGTH);
    }

    public ExecutionReportNewMessage setExecId(String execId) {
        putString(EXEC_ID_OFFSET, EXEC_ID_LENGTH, execId);
        return this;
    }

    // Sender ID
    public String getSenderId() {
        return getString(SENDER_ID_OFFSET, SENDER_ID_LENGTH);
    }

    public ExecutionReportNewMessage setSenderId(String senderId) {
        putString(SENDER_ID_OFFSET, SENDER_ID_LENGTH, senderId);
        return this;
    }

    // Client Order ID
    public String getClOrdId() {
        return getString(CL_ORD_ID_OFFSET, CL_ORD_ID_LENGTH);
    }

    public ExecutionReportNewMessage setClOrdId(String clOrdId) {
        putString(CL_ORD_ID_OFFSET, CL_ORD_ID_LENGTH, clOrdId);
        return this;
    }

    // Party Details List Request ID
    public long getPartyDetailsListReqId() {
        return getLong(PARTY_DETAILS_LIST_REQ_ID_OFFSET);
    }

    public ExecutionReportNewMessage setPartyDetailsListReqId(long id) {
        putLong(PARTY_DETAILS_LIST_REQ_ID_OFFSET, id);
        return this;
    }

    // Order ID
    public long getOrderId() {
        return getLong(ORDER_ID_OFFSET);
    }

    public ExecutionReportNewMessage setOrderId(long orderId) {
        putLong(ORDER_ID_OFFSET, orderId);
        return this;
    }

    // Price
    public double getPrice() {
        return getLong(PRICE_OFFSET) / PRICE_MULTIPLIER;
    }

    public ExecutionReportNewMessage setPrice(double price) {
        putLong(PRICE_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    // Stop Price
    public double getStopPx() {
        return getLong(STOP_PX_OFFSET) / PRICE_MULTIPLIER;
    }

    public ExecutionReportNewMessage setStopPx(double price) {
        putLong(STOP_PX_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    // Transaction Time
    public long getTransactTime() {
        return getLong(TRANSACT_TIME_OFFSET);
    }

    public ExecutionReportNewMessage setTransactTime(long time) {
        putLong(TRANSACT_TIME_OFFSET, time);
        return this;
    }

    // Sending Time Epoch
    public long getSendingTimeEpoch() {
        return getLong(SENDING_TIME_EPOCH_OFFSET);
    }

    public ExecutionReportNewMessage setSendingTimeEpoch(long time) {
        putLong(SENDING_TIME_EPOCH_OFFSET, time);
        return this;
    }

    // Order Request ID
    public long getOrderRequestId() {
        return getLong(ORDER_REQUEST_ID_OFFSET);
    }

    public ExecutionReportNewMessage setOrderRequestId(long id) {
        putLong(ORDER_REQUEST_ID_OFFSET, id);
        return this;
    }

    // Order Quantity
    public int getOrderQty() {
        return getInt(ORDER_QTY_OFFSET);
    }

    public ExecutionReportNewMessage setOrderQty(int qty) {
        putInt(ORDER_QTY_OFFSET, qty);
        return this;
    }

    // Cumulative Quantity
    public int getCumQty() {
        return getInt(CUM_QTY_OFFSET);
    }

    public ExecutionReportNewMessage setCumQty(int qty) {
        putInt(CUM_QTY_OFFSET, qty);
        return this;
    }

    // Leaves Quantity
    public int getLeavesQty() {
        return getInt(LEAVES_QTY_OFFSET);
    }

    public ExecutionReportNewMessage setLeavesQty(int qty) {
        putInt(LEAVES_QTY_OFFSET, qty);
        return this;
    }

    // Expire Date
    public int getExpireDate() {
        return getUnsignedShort(EXPIRE_DATE_OFFSET);
    }

    public ExecutionReportNewMessage setExpireDate(int date) {
        putShort(EXPIRE_DATE_OFFSET, (short) date);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "ExecutionReportNewMessage[not wrapped]";
        }
        return String.format("ExecutionReportNewMessage[clOrdId=%s, orderId=%d, price=%.6f, orderQty=%d, leavesQty=%d]",
                getClOrdId(), getOrderId(), getPrice(), getOrderQty(), getLeavesQty());
    }
}
