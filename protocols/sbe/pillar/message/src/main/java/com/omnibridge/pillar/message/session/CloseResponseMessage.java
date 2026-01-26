package com.omnibridge.pillar.message.session;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Close Response message.
 * <p>
 * Sent by the gateway in response to a Close message.
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       8       Stream ID
 * 8       1       Status (0=Success, 1=StreamNotFound, etc.)
 * 9       3       Reserved
 * </pre>
 */
public class CloseResponseMessage extends PillarMessage {

    public static final int STREAM_ID_OFFSET = 0;
    public static final int STATUS_OFFSET = 8;

    public static final int BLOCK_LENGTH = 12;

    // Status codes
    public static final byte STATUS_SUCCESS = 0;
    public static final byte STATUS_STREAM_NOT_FOUND = 1;
    public static final byte STATUS_NOT_OPEN = 2;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.CLOSE_RESPONSE;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.CLOSE_RESPONSE.getTemplateId();
    }

    // ==================== Getters ====================

    public long getStreamId() {
        return getUInt64(STREAM_ID_OFFSET);
    }

    public byte getStatus() {
        return getByte(STATUS_OFFSET);
    }

    // ==================== Setters ====================

    public CloseResponseMessage setStreamId(long streamId) {
        putUInt64(STREAM_ID_OFFSET, streamId);
        return this;
    }

    public CloseResponseMessage setStatus(byte status) {
        putByte(STATUS_OFFSET, status);
        return this;
    }

    // ==================== Helpers ====================

    public boolean isSuccess() {
        return getStatus() == STATUS_SUCCESS;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "CloseResponseMessage[not wrapped]";
        }
        return "CloseResponseMessage[streamId=" + getStreamId() +
               ", status=" + getStatus() + "]";
    }
}
