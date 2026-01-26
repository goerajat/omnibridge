package com.omnibridge.optiq.message.order;

import com.omnibridge.optiq.message.OptiqMessage;
import com.omnibridge.optiq.message.OptiqMessageType;

/**
 * Optiq Reject message (Template ID 301).
 * <p>
 * Sent by the OEG when an order is rejected.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       8       MsgSeqNum
 * 8       4       FirmID
 * 12      8       SendingTime
 * 20      8       OEGIn
 * 28      8       OEGOut
 * 36      8       ClientOrderID
 * 44      4       SymbolIndex
 * 48      1       EMM
 * 49      1       RejectedMessageID
 * 50      2       ErrorCode
 * 52      2       Reserved
 * 54      10      RejectedMessage (optional)
 * </pre>
 */
public class RejectMessage extends OptiqMessage {

    public static final int TEMPLATE_ID = 301;
    public static final int BLOCK_LENGTH = 64;

    // Field offsets
    private static final int MSG_SEQ_NUM_OFFSET = 0;
    private static final int FIRM_ID_OFFSET = 8;
    private static final int SENDING_TIME_OFFSET = 12;
    private static final int OEG_IN_OFFSET = 20;
    private static final int OEG_OUT_OFFSET = 28;
    private static final int CLIENT_ORDER_ID_OFFSET = 36;
    private static final int SYMBOL_INDEX_OFFSET = 44;
    private static final int EMM_OFFSET = 48;
    private static final int REJECTED_MESSAGE_ID_OFFSET = 49;
    private static final int ERROR_CODE_OFFSET = 50;
    private static final int REJECTED_MESSAGE_OFFSET = 54;
    private static final int REJECTED_MESSAGE_LENGTH = 10;

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
        return OptiqMessageType.REJECT;
    }

    // Message Sequence Number
    public long getMsgSeqNum() {
        return getLong(MSG_SEQ_NUM_OFFSET);
    }

    public RejectMessage setMsgSeqNum(long seqNum) {
        putLong(MSG_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    // Firm ID
    public int getFirmId() {
        return getInt(FIRM_ID_OFFSET);
    }

    public RejectMessage setFirmId(int firmId) {
        putInt(FIRM_ID_OFFSET, firmId);
        return this;
    }

    // Sending Time
    public long getSendingTime() {
        return getLong(SENDING_TIME_OFFSET);
    }

    public RejectMessage setSendingTime(long time) {
        putLong(SENDING_TIME_OFFSET, time);
        return this;
    }

    // OEG In
    public long getOegIn() {
        return getLong(OEG_IN_OFFSET);
    }

    public RejectMessage setOegIn(long time) {
        putLong(OEG_IN_OFFSET, time);
        return this;
    }

    // OEG Out
    public long getOegOut() {
        return getLong(OEG_OUT_OFFSET);
    }

    public RejectMessage setOegOut(long time) {
        putLong(OEG_OUT_OFFSET, time);
        return this;
    }

    // Client Order ID
    public long getClientOrderId() {
        return getLong(CLIENT_ORDER_ID_OFFSET);
    }

    public RejectMessage setClientOrderId(long orderId) {
        putLong(CLIENT_ORDER_ID_OFFSET, orderId);
        return this;
    }

    // Symbol Index
    public int getSymbolIndex() {
        return getInt(SYMBOL_INDEX_OFFSET);
    }

    public RejectMessage setSymbolIndex(int symbolIndex) {
        putInt(SYMBOL_INDEX_OFFSET, symbolIndex);
        return this;
    }

    // EMM
    public byte getEmm() {
        return getByte(EMM_OFFSET);
    }

    public RejectMessage setEmm(byte emm) {
        putByte(EMM_OFFSET, emm);
        return this;
    }

    // Rejected Message ID
    public byte getRejectedMessageId() {
        return getByte(REJECTED_MESSAGE_ID_OFFSET);
    }

    public RejectMessage setRejectedMessageId(byte id) {
        putByte(REJECTED_MESSAGE_ID_OFFSET, id);
        return this;
    }

    // Error Code
    public int getErrorCode() {
        return getUnsignedShort(ERROR_CODE_OFFSET);
    }

    public RejectMessage setErrorCode(int code) {
        putShort(ERROR_CODE_OFFSET, (short) code);
        return this;
    }

    // Rejected Message (text)
    public String getRejectedMessage() {
        return getString(REJECTED_MESSAGE_OFFSET, REJECTED_MESSAGE_LENGTH);
    }

    public RejectMessage setRejectedMessage(String message) {
        putString(REJECTED_MESSAGE_OFFSET, REJECTED_MESSAGE_LENGTH, message);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "RejectMessage[not wrapped]";
        }
        return String.format("RejectMessage[clientOrderId=%d, errorCode=%d]",
                getClientOrderId(), getErrorCode());
    }
}
