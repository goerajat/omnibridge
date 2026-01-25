package com.omnibridge.pillar.message.session;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Open Response message.
 * <p>
 * Sent by the gateway in response to an Open message.
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       8       Stream ID
 * 8       1       Status (0=Success, 1=StreamNotFound, etc.)
 * 9       1       Reserved
 * 10      2       Reserved
 * 12      4       Reserved
 * </pre>
 */
public class OpenResponseMessage extends PillarMessage {

    public static final int STREAM_ID_OFFSET = 0;
    public static final int STATUS_OFFSET = 8;

    public static final int BLOCK_LENGTH = 16;

    // Status codes
    public static final byte STATUS_SUCCESS = 0;
    public static final byte STATUS_STREAM_NOT_FOUND = 1;
    public static final byte STATUS_ACCESS_DENIED = 2;
    public static final byte STATUS_ALREADY_OPEN = 3;
    public static final byte STATUS_INVALID_SEQUENCE = 4;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.OPEN_RESPONSE;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.OPEN_RESPONSE.getTemplateId();
    }

    // ==================== Getters ====================

    public long getStreamId() {
        return getUInt64(STREAM_ID_OFFSET);
    }

    public byte getStatus() {
        return getByte(STATUS_OFFSET);
    }

    // ==================== Setters ====================

    public OpenResponseMessage setStreamId(long streamId) {
        putUInt64(STREAM_ID_OFFSET, streamId);
        return this;
    }

    public OpenResponseMessage setStatus(byte status) {
        putByte(STATUS_OFFSET, status);
        return this;
    }

    // ==================== Helpers ====================

    public boolean isSuccess() {
        return getStatus() == STATUS_SUCCESS;
    }

    public String getStatusDescription() {
        return switch (getStatus()) {
            case STATUS_SUCCESS -> "Success";
            case STATUS_STREAM_NOT_FOUND -> "Stream Not Found";
            case STATUS_ACCESS_DENIED -> "Access Denied";
            case STATUS_ALREADY_OPEN -> "Already Open";
            case STATUS_INVALID_SEQUENCE -> "Invalid Sequence";
            default -> "Unknown (" + getStatus() + ")";
        };
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "OpenResponseMessage[not wrapped]";
        }
        return "OpenResponseMessage[streamId=" + getStreamId() +
               ", status=" + getStatusDescription() + "]";
    }
}
