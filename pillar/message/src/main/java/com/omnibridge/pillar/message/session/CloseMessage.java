package com.omnibridge.pillar.message.session;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Close message.
 * <p>
 * Sent by the client to close a stream.
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       8       Stream ID
 * 8       8       Reserved
 * </pre>
 */
public class CloseMessage extends PillarMessage {

    public static final int STREAM_ID_OFFSET = 0;

    public static final int BLOCK_LENGTH = 16;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.CLOSE;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.CLOSE.getTemplateId();
    }

    // ==================== Getters ====================

    public long getStreamId() {
        return getUInt64(STREAM_ID_OFFSET);
    }

    // ==================== Setters ====================

    public CloseMessage setStreamId(long streamId) {
        putUInt64(STREAM_ID_OFFSET, streamId);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "CloseMessage[not wrapped]";
        }
        return "CloseMessage[streamId=" + getStreamId() + "]";
    }
}
