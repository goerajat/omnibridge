package com.omnibridge.ilink3.message.session;

import com.omnibridge.ilink3.message.ILink3Message;
import com.omnibridge.ilink3.message.ILink3MessageType;

/**
 * iLink 3 Sequence message (Template ID 508).
 * <p>
 * Used for heartbeat and keep-alive purposes. Contains the next expected
 * sequence number from the sender. When no application messages are being
 * sent, this message is sent periodically to maintain the connection.
 * <p>
 * Block Layout:
 * <pre>
 * Offset  Length  Field
 * 0       8       UUID
 * 8       4       NextSeqNo
 * </pre>
 */
public class SequenceMessage extends ILink3Message {

    public static final int TEMPLATE_ID = 508;
    public static final int BLOCK_LENGTH = 12;

    // Field offsets
    private static final int UUID_OFFSET = 0;
    private static final int NEXT_SEQ_NO_OFFSET = 8;

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
        return ILink3MessageType.SEQUENCE;
    }

    // UUID
    public long getUuid() {
        return getLong(UUID_OFFSET);
    }

    public SequenceMessage setUuid(long uuid) {
        putLong(UUID_OFFSET, uuid);
        return this;
    }

    // Next Sequence Number
    public long getNextSeqNo() {
        return getUnsignedInt(NEXT_SEQ_NO_OFFSET);
    }

    public SequenceMessage setNextSeqNo(long seqNo) {
        putInt(NEXT_SEQ_NO_OFFSET, (int) seqNo);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "SequenceMessage[not wrapped]";
        }
        return String.format("SequenceMessage[uuid=%d, nextSeqNo=%d]",
                getUuid(), getNextSeqNo());
    }
}
