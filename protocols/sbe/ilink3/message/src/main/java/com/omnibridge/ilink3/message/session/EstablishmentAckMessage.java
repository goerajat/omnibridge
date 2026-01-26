package com.omnibridge.ilink3.message.session;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 EstablishmentAck message (Template ID 504).
 * <p>
 * Sent by the CME Globex system in response to an Establish message.
 * Indicates successful session establishment.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       8       UUID
 * 8       8       RequestTimestamp
 * 16      4       NextSeqNo
 * 20      4       PreviousSeqNo
 * 24      8       PreviousUUID
 * 32      2       KeepAliveInterval
 * </pre>
 */
public class EstablishmentAckMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 504;
    public static final int BLOCK_LENGTH = 34;

    // Field offsets
    private static final int UUID_OFFSET = 0;
    private static final int REQUEST_TIMESTAMP_OFFSET = 8;
    private static final int NEXT_SEQ_NO_OFFSET = 16;
    private static final int PREVIOUS_SEQ_NO_OFFSET = 20;
    private static final int PREVIOUS_UUID_OFFSET = 24;
    private static final int KEEP_ALIVE_INTERVAL_OFFSET = 32;

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
        return ILink3MessageType.ESTABLISHMENT_ACK;
    }

    // UUID
    public long getUuid() {
        return getLong(UUID_OFFSET);
    }

    public EstablishmentAckMessage setUuid(long uuid) {
        putLong(UUID_OFFSET, uuid);
        return this;
    }

    // Request Timestamp
    public long getRequestTimestamp() {
        return getLong(REQUEST_TIMESTAMP_OFFSET);
    }

    public EstablishmentAckMessage setRequestTimestamp(long timestamp) {
        putLong(REQUEST_TIMESTAMP_OFFSET, timestamp);
        return this;
    }

    // Next Sequence Number
    public long getNextSeqNo() {
        return getUnsignedInt(NEXT_SEQ_NO_OFFSET);
    }

    public EstablishmentAckMessage setNextSeqNo(long seqNo) {
        putInt(NEXT_SEQ_NO_OFFSET, (int) seqNo);
        return this;
    }

    // Previous Sequence Number
    public long getPreviousSeqNo() {
        return getUnsignedInt(PREVIOUS_SEQ_NO_OFFSET);
    }

    public EstablishmentAckMessage setPreviousSeqNo(long seqNo) {
        putInt(PREVIOUS_SEQ_NO_OFFSET, (int) seqNo);
        return this;
    }

    // Previous UUID
    public long getPreviousUuid() {
        return getLong(PREVIOUS_UUID_OFFSET);
    }

    public EstablishmentAckMessage setPreviousUuid(long uuid) {
        putLong(PREVIOUS_UUID_OFFSET, uuid);
        return this;
    }

    // Keep Alive Interval (milliseconds)
    public int getKeepAliveInterval() {
        return getUnsignedShort(KEEP_ALIVE_INTERVAL_OFFSET);
    }

    public EstablishmentAckMessage setKeepAliveInterval(int interval) {
        putShort(KEEP_ALIVE_INTERVAL_OFFSET, (short) interval);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "EstablishmentAckMessage[not wrapped]";
        }
        return String.format("EstablishmentAckMessage[uuid=%d, nextSeqNo=%d, keepAlive=%d]",
                getUuid(), getNextSeqNo(), getKeepAliveInterval());
    }
}
