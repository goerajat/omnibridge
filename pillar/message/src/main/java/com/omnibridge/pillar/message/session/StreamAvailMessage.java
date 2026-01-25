package com.omnibridge.pillar.message.session;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Stream Available message.
 * <p>
 * Sent by the gateway to advertise available streams after login
 * and periodically (once per second) as a heartbeat for the stream.
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       8       Stream ID
 * 8       8       Next Sequence Number
 * 16      1       Stream Type (1=TG, 2=GT)
 * 17      1       Access Flags (read/write permissions)
 * 18      2       Reserved
 * 20      4       Reserved
 * </pre>
 */
public class StreamAvailMessage extends PillarMessage {

    public static final int STREAM_ID_OFFSET = 0;
    public static final int NEXT_SEQ_NUM_OFFSET = 8;
    public static final int STREAM_TYPE_OFFSET = 16;
    public static final int ACCESS_FLAGS_OFFSET = 17;

    public static final int BLOCK_LENGTH = 24;

    // Stream types
    public static final byte STREAM_TYPE_TG = 1; // Trader to Gateway
    public static final byte STREAM_TYPE_GT = 2; // Gateway to Trader

    // Access flags
    public static final byte ACCESS_READ = 0x01;
    public static final byte ACCESS_WRITE = 0x02;
    public static final byte ACCESS_READ_WRITE = 0x03;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.STREAM_AVAIL;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.STREAM_AVAIL.getTemplateId();
    }

    // ==================== Getters ====================

    public long getStreamId() {
        return getUInt64(STREAM_ID_OFFSET);
    }

    public long getNextSequenceNumber() {
        return getUInt64(NEXT_SEQ_NUM_OFFSET);
    }

    public byte getStreamType() {
        return getByte(STREAM_TYPE_OFFSET);
    }

    public byte getAccessFlags() {
        return getByte(ACCESS_FLAGS_OFFSET);
    }

    // ==================== Setters ====================

    public StreamAvailMessage setStreamId(long streamId) {
        putUInt64(STREAM_ID_OFFSET, streamId);
        return this;
    }

    public StreamAvailMessage setNextSequenceNumber(long seqNum) {
        putUInt64(NEXT_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    public StreamAvailMessage setStreamType(byte streamType) {
        putByte(STREAM_TYPE_OFFSET, streamType);
        return this;
    }

    public StreamAvailMessage setAccessFlags(byte flags) {
        putByte(ACCESS_FLAGS_OFFSET, flags);
        return this;
    }

    // ==================== Helpers ====================

    public boolean isTGStream() {
        return getStreamType() == STREAM_TYPE_TG;
    }

    public boolean isGTStream() {
        return getStreamType() == STREAM_TYPE_GT;
    }

    public boolean canRead() {
        return (getAccessFlags() & ACCESS_READ) != 0;
    }

    public boolean canWrite() {
        return (getAccessFlags() & ACCESS_WRITE) != 0;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "StreamAvailMessage[not wrapped]";
        }
        return "StreamAvailMessage[streamId=" + getStreamId() +
               ", nextSeq=" + getNextSequenceNumber() +
               ", type=" + (isTGStream() ? "TG" : "GT") +
               ", access=" + getAccessFlags() + "]";
    }
}
