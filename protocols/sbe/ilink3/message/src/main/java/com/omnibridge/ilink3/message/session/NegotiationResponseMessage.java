package com.omnibridge.ilink3.message.session;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 NegotiationResponse message (Template ID 501).
 * <p>
 * Sent by the CME Globex system in response to a Negotiate message.
 * Indicates successful negotiation.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       8       UUID
 * 8       8       RequestTimestamp
 * 16      2       SecretKeySecureIDExpiration
 * 18      1       FaultToleranceIndicator
 * 19      1       SplitMsg
 * 20      4       PreviousSeqNo
 * 24      8       PreviousUUID
 * </pre>
 */
public class NegotiationResponseMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 501;
    public static final int BLOCK_LENGTH = 30;

    // Field offsets
    private static final int UUID_OFFSET = 0;
    private static final int REQUEST_TIMESTAMP_OFFSET = 8;
    private static final int SECRET_KEY_EXPIRATION_OFFSET = 16;
    private static final int FAULT_TOLERANCE_INDICATOR_OFFSET = 18;
    private static final int SPLIT_MSG_OFFSET = 19;
    private static final int PREVIOUS_SEQ_NO_OFFSET = 20;
    private static final int PREVIOUS_UUID_OFFSET = 24;

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
        return ILink3MessageType.NEGOTIATION_RESPONSE;
    }

    // UUID
    public long getUuid() {
        return getLong(UUID_OFFSET);
    }

    public NegotiationResponseMessage setUuid(long uuid) {
        putLong(UUID_OFFSET, uuid);
        return this;
    }

    // Request Timestamp
    public long getRequestTimestamp() {
        return getLong(REQUEST_TIMESTAMP_OFFSET);
    }

    public NegotiationResponseMessage setRequestTimestamp(long timestamp) {
        putLong(REQUEST_TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    // Secret Key Secure ID Expiration
    public int getSecretKeySecureIdExpiration() {
        return getUnsignedShort(SECRET_KEY_EXPIRATION_OFFSET);
    }

    public NegotiationResponseMessage setSecretKeySecureIdExpiration(int expiration) {
        putShort(SECRET_KEY_EXPIRATION_OFFSET, (short) expiration);
        return this;
    }

    // Fault Tolerance Indicator
    public byte getFaultToleranceIndicator() {
        return getByte(FAULT_TOLERANCE_INDICATOR_OFFSET);
    }

    public NegotiationResponseMessage setFaultToleranceIndicator(byte indicator) {
        putByte(FAULT_TOLERANCE_INDICATOR_OFFSET, indicator);
        return this;
    }

    // Split Message
    public byte getSplitMsg() {
        return getByte(SPLIT_MSG_OFFSET);
    }

    public NegotiationResponseMessage setSplitMsg(byte splitMsg) {
        putByte(SPLIT_MSG_OFFSET, splitMsg);
        return this;
    }

    // Previous Sequence Number
    public long getPreviousSeqNo() {
        return getUnsignedInt(PREVIOUS_SEQ_NO_OFFSET);
    }

    public NegotiationResponseMessage setPreviousSeqNo(long seqNo) {
        putInt(PREVIOUS_SEQ_NO_OFFSET, (int) seqNo);
        return this;
    }

    // Previous UUID
    public long getPreviousUuid() {
        return getLong(PREVIOUS_UUID_OFFSET);
    }

    public NegotiationResponseMessage setPreviousUuid(long uuid) {
        putLong(PREVIOUS_UUID_OFFSET, uuid);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "NegotiationResponseMessage[not wrapped]";
        }
        return String.format("NegotiationResponseMessage[uuid=%d, previousSeqNo=%d]",
                getUuid(), getPreviousSeqNo());
    }
}
