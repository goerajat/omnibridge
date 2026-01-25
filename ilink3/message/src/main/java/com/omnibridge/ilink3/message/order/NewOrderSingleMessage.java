package com.omnibridge.ilink3.message.order;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 NewOrderSingle message (Template ID 514).
 * <p>
 * Sent by the client to submit a new order.
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
 * 69      8       OrderRequestID
 * 77      8       SendingTimeEpoch
 * 85      8       StopPx (PRICE9)
 * 93      8       Location
 * 101     4       MinQty
 * 105     4       DisplayQty
 * 109     2       ExpireDate
 * 111     1       OrdType
 * 112     1       TimeInForce
 * 113     1       ManualOrderIndicator
 * 114     1       ExecInst
 * 115     1       ExecutionMode
 * 116     1       LiquidityFlag
 * 117     1       ManagedOrder
 * </pre>
 */
public class NewOrderSingleMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 514;
    public static final int BLOCK_LENGTH = 118;

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
    private static final int ORDER_REQUEST_ID_OFFSET = 69;
    private static final int SENDING_TIME_EPOCH_OFFSET = 77;
    private static final int STOP_PX_OFFSET = 85;
    private static final int LOCATION_OFFSET = 93;
    private static final int LOCATION_LENGTH = 5;
    private static final int MIN_QTY_OFFSET = 101;
    private static final int DISPLAY_QTY_OFFSET = 105;
    private static final int EXPIRE_DATE_OFFSET = 109;
    private static final int ORD_TYPE_OFFSET = 111;
    private static final int TIME_IN_FORCE_OFFSET = 112;
    private static final int MANUAL_ORDER_INDICATOR_OFFSET = 113;
    private static final int EXEC_INST_OFFSET = 114;
    private static final int EXECUTION_MODE_OFFSET = 115;
    private static final int LIQUIDITY_FLAG_OFFSET = 116;
    private static final int MANAGED_ORDER_OFFSET = 117;

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
        return ILink3MessageType.NEW_ORDER_SINGLE;
    }

    // Price (PRICE9 - 8 bytes, divide by 10^9 to get actual price)
    public NewOrderSingleMessage setPrice(double price) {
        putLong(PRICE_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    public double getPrice() {
        return getLong(PRICE_OFFSET) / PRICE_MULTIPLIER;
    }

    public long getPriceRaw() {
        return getLong(PRICE_OFFSET);
    }

    // Order Quantity
    public NewOrderSingleMessage setOrderQty(int qty) {
        putInt(ORDER_QTY_OFFSET, qty);
        return this;
    }

    public int getOrderQty() {
        return getInt(ORDER_QTY_OFFSET);
    }

    // Security ID
    public NewOrderSingleMessage setSecurityId(int securityId) {
        putInt(SECURITY_ID_OFFSET, securityId);
        return this;
    }

    public int getSecurityId() {
        return getInt(SECURITY_ID_OFFSET);
    }

    // Side (1=Buy, 2=Sell)
    public NewOrderSingleMessage setSide(byte side) {
        putByte(SIDE_OFFSET, side);
        return this;
    }

    public byte getSide() {
        return getByte(SIDE_OFFSET);
    }

    // Sequence Number
    public NewOrderSingleMessage setSeqNum(int seqNum) {
        putInt(SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    public int getSeqNum() {
        return getInt(SEQ_NUM_OFFSET);
    }

    // Sender ID
    public NewOrderSingleMessage setSenderId(String senderId) {
        putString(SENDER_ID_OFFSET, SENDER_ID_LENGTH, senderId);
        return this;
    }

    public String getSenderId() {
        return getString(SENDER_ID_OFFSET, SENDER_ID_LENGTH);
    }

    // Client Order ID
    public NewOrderSingleMessage setClOrdId(String clOrdId) {
        putString(CL_ORD_ID_OFFSET, CL_ORD_ID_LENGTH, clOrdId);
        return this;
    }

    public String getClOrdId() {
        return getString(CL_ORD_ID_OFFSET, CL_ORD_ID_LENGTH);
    }

    // Party Details List Request ID
    public NewOrderSingleMessage setPartyDetailsListReqId(long id) {
        putLong(PARTY_DETAILS_LIST_REQ_ID_OFFSET, id);
        return this;
    }

    public long getPartyDetailsListReqId() {
        return getLong(PARTY_DETAILS_LIST_REQ_ID_OFFSET);
    }

    // Order Request ID
    public NewOrderSingleMessage setOrderRequestId(long id) {
        putLong(ORDER_REQUEST_ID_OFFSET, id);
        return this;
    }

    public long getOrderRequestId() {
        return getLong(ORDER_REQUEST_ID_OFFSET);
    }

    // Sending Time Epoch (nanoseconds)
    public NewOrderSingleMessage setSendingTimeEpoch(long time) {
        putLong(SENDING_TIME_EPOCH_OFFSET, time);
        return this;
    }

    public long getSendingTimeEpoch() {
        return getLong(SENDING_TIME_EPOCH_OFFSET);
    }

    // Stop Price (PRICE9)
    public NewOrderSingleMessage setStopPx(double price) {
        putLong(STOP_PX_OFFSET, (long) (price * PRICE_MULTIPLIER));
        return this;
    }

    public double getStopPx() {
        return getLong(STOP_PX_OFFSET) / PRICE_MULTIPLIER;
    }

    // Location
    public NewOrderSingleMessage setLocation(String location) {
        putString(LOCATION_OFFSET, LOCATION_LENGTH, location);
        return this;
    }

    public String getLocation() {
        return getString(LOCATION_OFFSET, LOCATION_LENGTH);
    }

    // Minimum Quantity
    public NewOrderSingleMessage setMinQty(int qty) {
        putInt(MIN_QTY_OFFSET, qty);
        return this;
    }

    public int getMinQty() {
        return getInt(MIN_QTY_OFFSET);
    }

    // Display Quantity
    public NewOrderSingleMessage setDisplayQty(int qty) {
        putInt(DISPLAY_QTY_OFFSET, qty);
        return this;
    }

    public int getDisplayQty() {
        return getInt(DISPLAY_QTY_OFFSET);
    }

    // Expire Date
    public NewOrderSingleMessage setExpireDate(int date) {
        putShort(EXPIRE_DATE_OFFSET, (short) date);
        return this;
    }

    public int getExpireDate() {
        return getUnsignedShort(EXPIRE_DATE_OFFSET);
    }

    // Order Type (1=Market, 2=Limit, 3=Stop, 4=StopLimit)
    public NewOrderSingleMessage setOrdType(byte ordType) {
        putByte(ORD_TYPE_OFFSET, ordType);
        return this;
    }

    public byte getOrdType() {
        return getByte(ORD_TYPE_OFFSET);
    }

    // Time In Force (0=Day, 1=GTC, 3=IOC, 4=FOK, 6=GTD)
    public NewOrderSingleMessage setTimeInForce(byte tif) {
        putByte(TIME_IN_FORCE_OFFSET, tif);
        return this;
    }

    public byte getTimeInForce() {
        return getByte(TIME_IN_FORCE_OFFSET);
    }

    // Manual Order Indicator
    public NewOrderSingleMessage setManualOrderIndicator(byte indicator) {
        putByte(MANUAL_ORDER_INDICATOR_OFFSET, indicator);
        return this;
    }

    public byte getManualOrderIndicator() {
        return getByte(MANUAL_ORDER_INDICATOR_OFFSET);
    }

    // Execution Instructions
    public NewOrderSingleMessage setExecInst(byte execInst) {
        putByte(EXEC_INST_OFFSET, execInst);
        return this;
    }

    public byte getExecInst() {
        return getByte(EXEC_INST_OFFSET);
    }

    // Execution Mode
    public NewOrderSingleMessage setExecutionMode(byte mode) {
        putByte(EXECUTION_MODE_OFFSET, mode);
        return this;
    }

    public byte getExecutionMode() {
        return getByte(EXECUTION_MODE_OFFSET);
    }

    // Liquidity Flag
    public NewOrderSingleMessage setLiquidityFlag(byte flag) {
        putByte(LIQUIDITY_FLAG_OFFSET, flag);
        return this;
    }

    public byte getLiquidityFlag() {
        return getByte(LIQUIDITY_FLAG_OFFSET);
    }

    // Managed Order
    public NewOrderSingleMessage setManagedOrder(byte managed) {
        putByte(MANAGED_ORDER_OFFSET, managed);
        return this;
    }

    public byte getManagedOrder() {
        return getByte(MANAGED_ORDER_OFFSET);
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "NewOrderSingleMessage[not wrapped]";
        }
        return String.format("NewOrderSingleMessage[clOrdId=%s, securityId=%d, side=%d, price=%.6f, qty=%d, ordType=%d, tif=%d]",
                getClOrdId(), getSecurityId(), getSide(), getPrice(), getOrderQty(), getOrdType(), getTimeInForce());
    }
}
