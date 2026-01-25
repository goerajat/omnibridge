package com.omnibridge.pillar.message.session;

import com.omnibridge.pillar.message.PillarMessage;
import com.omnibridge.pillar.message.PillarMessageType;

/**
 * NYSE Pillar Open message.
 * <p>
 * Sent by the client to open a stream for reading or writing.
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       8       Stream ID
 * 8       8       Start Sequence Number (for reading)
 * 16      1       Access Flags (read/write)
 * 17      1       Throttle Preference
 * 18      2       Reserved
 * 20      4       Reserved
 * 24      8       Reserved
 * </pre>
 */
public class OpenMessage extends PillarMessage {

    public static final int STREAM_ID_OFFSET = 0;
    public static final int START_SEQ_NUM_OFFSET = 8;
    public static final int ACCESS_FLAGS_OFFSET = 16;
    public static final int THROTTLE_PREFERENCE_OFFSET = 17;

    public static final int BLOCK_LENGTH = 32;

    // Access flags (same as StreamAvail)
    public static final byte ACCESS_READ = 0x01;
    public static final byte ACCESS_WRITE = 0x02;
    public static final byte ACCESS_READ_WRITE = 0x03;

    // Throttle preferences
    public static final byte THROTTLE_REJECT = 0;
    public static final byte THROTTLE_QUEUE = 1;

    @Override
    public PillarMessageType getMessageType() {
        return PillarMessageType.OPEN;
    }

    @Override
    public int getBlockLength() {
        return BLOCK_LENGTH;
    }

    @Override
    public int getTemplateId() {
        return PillarMessageType.OPEN.getTemplateId();
    }

    // ==================== Getters ====================

    public long getStreamId() {
        return getUInt64(STREAM_ID_OFFSET);
    }

    public long getStartSequenceNumber() {
        return getUInt64(START_SEQ_NUM_OFFSET);
    }

    public byte getAccessFlags() {
        return getByte(ACCESS_FLAGS_OFFSET);
    }

    public byte getThrottlePreference() {
        return getByte(THROTTLE_PREFERENCE_OFFSET);
    }

    // ==================== Setters ====================

    public OpenMessage setStreamId(long streamId) {
        putUInt64(STREAM_ID_OFFSET, streamId);
        return this;
    }

    public OpenMessage setStartSequenceNumber(long seqNum) {
        putUInt64(START_SEQ_NUM_OFFSET, seqNum);
        return this;
    }

    public OpenMessage setAccessFlags(byte flags) {
        putByte(ACCESS_FLAGS_OFFSET, flags);
        return this;
    }

    public OpenMessage setThrottlePreference(byte preference) {
        putByte(THROTTLE_PREFERENCE_OFFSET, preference);
        return this;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "OpenMessage[not wrapped]";
        }
        return "OpenMessage[streamId=" + getStreamId() +
               ", startSeq=" + getStartSequenceNumber() +
               ", access=" + getAccessFlags() + "]";
    }
}
