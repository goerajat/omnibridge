package com.omnibridge.ilink3.message.order;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 OrderCancelReplaceRequest message (Template ID 515).
 * <p>
 * Sent by the client to modify an existing order.
 * <p>
 * Block Layout (simplified):
 * <pre>
 * Offset  Length  Field
 * 0       8       Price (PRICE9)
 * 8       4       OrderQty
 * 12      4       SecurityID
 * 16      1       Side
 * 17      4       SeqNum
 * 21      20      SenderID
 * 41      20      ClOrdID
 * 61      8       PartyDetailsListReqID
 * 69      8       OrderID
 * 77      8       StopPx (PRICE9)
 * 85      8       OrderRequestID
 * 93      8       SendingTimeEpoch
 * 101     5       Location
 * 106     4       MinQty
 * 110     4       DisplayQty
 * 114     2       ExpireDate
 * 116     1       OrdType
 * 117     1       TimeInForce
 * 118     1       ManualOrderIndicator
 * 119     1       OfmOverride
 * 120     1       ExecInst
 * 121     1       ExecutionMode
 * 122     1       LiquidityFlag
 * 123     1       ManagedOrder
 * 124     1       ShortSaleType
 * 125     5       Reserved
 * </pre>
 */
public class OrderCancelReplaceRequestMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 515;
    public static final int BLOCK_LENGTH = 130;

    // Field offsets
    private static final int PRICE_OFFSET = 0;
    private static final int ORDER_QTY_OFFSET = 8;
    private static final int SECURITY_ID_OFFSET = 12;
    private static final int SIDE_OFFSET = 16;
    private static final int SEQ_NUM_OFFSET = 17;
    private static final int SENDER_ID_OFFSET = 21;
    private static final int SENDER_ID_LENGTH = 20;
    private static final int CL_ORD_ID_OFFSET = 41;
    private static final int CL_ORD_ID_LENGTH = 20;
    private static final int PARTY_DETAILS_LIST_REQ_ID_OFFSET = 61;
    private static final int ORDER_ID_OFFSET = 69;
    private static final int STOP_PX_OFFSET = 77;
    private static final int ORDER_REQUEST_ID_OFFSET = 85;
    private static final int SENDING_TIME_EPOCH_OFFSET = 93;
    private static final int LOCATION_OFFSET = 101;
    private static final int LOCATION_LENGTH = 5;
    private static final int MIN_QTY_OFFSET = 106;
    private static final int DISPLAY_QTY_OFFSET = 110;
    private static final int EXPIRE_DATE_OFFSET = 114;
    private static final int ORD_TYPE_OFFSET = 116;
    private static final int TIME_IN_FORCE_OFFSET = 117;
    private static final int MANUAL_ORDER_INDICATOR_OFFSET = 118;
    private static final int OFM_OVERRIDE_OFFSET = 119;
    private static final int EXEC_INST_OFFSET = 120;
    private static final int EXECUTION_MODE_OFFSET = 121;
    private static final int LIQUIDITY_FLAG_OFFSET = 122;
    private static final int MANAGED_ORDER_OFFSET = 123;
    private static final int SHORT_SALE_TYPE_OFFSET = 124;

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
        return ILink3MessageType.ORDER_CANCEL_REPLACE_REQUEST;
    }

    // Price (PRICE9)
    public OrderCancelReplaceRequestMessage setPrice(double price) {
        putLong(PRICE_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    public double getPrice() {
        return getLong(PRICE_OFFSET) / PRICE_MULTIPLIER;
    }

    // Order Quantity
    public OrderCancelReplaceRequestMessage setOrderQty(int qty) {
        putInt(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public int getOrderQty() {
        return getInt(ORDER_QTY_OFFSET);
    }

    // Security ID
    public OrderCancelReplaceRequestMessage setSecurityId(int securityId) {
        putInt(SECURITY_ID_OFFSET, securityId);
        return this;
    }

    public int getSecurityId() {
        return getInt(SECURITY_ID_OFFSET);
    }

    // Side
    public OrderCancelReplaceRequestMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public byte getSide() {
        return getByte(SIDE_OFFSET);
    }

    // Sequence Number
    public OrderCancelReplaceRequestMessage setSeqNum(int seqNum) {
        putInt(SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    public int getSeqNum() {
        return getInt(SEQ_NUM_OFFSET);
    }

    // Sender ID
    public OrderCancelReplaceRequestMessage setSenderId(String senderId) {
        putString(SENDER_ID_OFFSET, SENDER_ID_LENGTH, senderId);
        return this;
    }

    public String getSenderId() {
        return getString(SENDER_ID_OFFSET, SENDER_ID_LENGTH);
    }

    // Client Order ID
    public OrderCancelReplaceRequestMessage setClOrdId(String clOrdId) {
        putString(CL_ORD_ID_OFFSET, CL_ORD_ID_LENGTH, clOrdId);
        return this;
    }

    public String getClOrdId() {
        return getString(CL_ORD_ID_OFFSET, CL_ORD_ID_LENGTH);
    }

    // Party Details List Request ID
    public OrderCancelReplaceRequestMessage setPartyDetailsListReqId(long id) {
        putLong(PARTY_DETAILS_LIST_REQ_ID_OFFSET, id);
        return this;
    }

    public long getPartyDetailsListReqId() {
        return getLong(PARTY_DETAILS_LIST_REQ_ID_OFFSET);
    }

    // Order ID (assigned by exchange)
    public OrderCancelReplaceRequestMessage setOrderId(long orderId) {
        putLong(ORDER_ID_OFFSET, orderId);
        return this;
    }

    public long getOrderId() {
        return getLong(ORDER_ID_OFFSET);
    }

    // Stop Price (PRICE9)
    public OrderCancelReplaceRequestMessage setStopPx(double price) {
        putLong(STOP_PX_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    public double getStopPx() {
        return getLong(STOP_PX_OFFSET) / PRICE_MULTIPLIER;
    }

    // Order Request ID
    public OrderCancelReplaceRequestMessage setOrderRequestId(long id) {
        putLong(ORDER_REQUEST_ID_OFFSET, id);
        return this;
    }

    public long getOrderRequestId() {
        return getLong(ORDER_REQUEST_ID_OFFSET);
    }

    // Sending Time Epoch (nanoseconds)
    public OrderCancelReplaceRequestMessage setSendingTimeEpoch(long time) {
        putLong(SENDING_TIME_EPOCH_OFFSET, time);
        return this;
    }

    public long getSendingTimeEpoch() {
        return getLong(SENDING_TIME_EPOCH_OFFSET);
    }

    // Location
    public OrderCancelReplaceRequestMessage setLocation(String location) {
        putString(LOCATION_OFFSET, LOCATION_LENGTH, location);
        return this;
    }

    public String getLocation() {
        return getString(LOCATION_OFFSET, LOCATION_LENGTH);
    }

    // Minimum Quantity
    public OrderCancelReplaceRequestMessage setMinQty(int qty) {
        putInt(MIN_QTY_OFFSET, qty);
        return this;
    }

    public int getMinQty() {
        return getInt(MIN_QTY_OFFSET);
    }

    // Display Quantity
    public OrderCancelReplaceRequestMessage setDisplayQty(int qty) {
        putInt(DISPLAY_QTY_OFFSET, qty);
        return this;
    }

    public int getDisplayQty() {
        return getInt(DISPLAY_QTY_OFFSET);
    }

    // Expire Date
    public OrderCancelReplaceRequestMessage setExpireDate(int date) {
        putShort(EXPIRE_DATE_OFFSET, (short) date);
        return this;
    }

    public int getExpireDate() {
        return getUnsignedShort(EXPIRE_DATE_OFFSET);
    }

    // Order Type
    public OrderCancelReplaceRequestMessage setOrdType(byte ordType) {
        putByte(ORD_TYPE_OFFSET, ordType);
        return this;
    }

    public byte getOrdType() {
        return getByte(ORD_TYPE_OFFSET);
    }

    // Time In Force
    public OrderCancelReplaceRequestMessage setTimeInForce(byte tif) {
        putByte(TIME_IN_FORCE_OFFSET, tif);
        return this;
    }

    public byte getTimeInForce() {
        return getByte(TIME_IN_FORCE_OFFSET);
    }

    // Manual Order Indicator
    public OrderCancelReplaceRequestMessage setManualOrderIndicator(byte indicator) {
        putByte(MANUAL_ORDER_INDICATOR_OFFSET, indicator);
        return this;
    }

    public byte getManualOrderIndicator() {
        return getByte(MANUAL_ORDER_INDICATOR_OFFSET);
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "OrderCancelReplaceRequestMessage[not wrapped]";
        }
        return String.format("OrderCancelReplaceRequestMessage[clOrdId=%s, orderId=%d, price=%.6f, qty=%d]",
                getClOrdId(), getOrderId(), getPrice(), getOrderQty());
    }
}
