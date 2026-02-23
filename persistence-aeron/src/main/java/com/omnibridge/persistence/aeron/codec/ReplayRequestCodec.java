package com.omnibridge.persistence.aeron.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * SBE codec for ReplayRequestMessage (templateId=2).
 *
 * <p>Block layout (41 bytes):
 * <pre>
 * [0-7]   correlationId       int64
 * [8]     direction           uint8   0=BOTH, 1=INBOUND, 2=OUTBOUND
 * [9-12]  fromSequenceNumber  int32   0 = no lower bound
 * [13-16] toSequenceNumber    int32   0 = no upper bound
 * [17-24] fromTimestamp       int64   0 = no lower bound
 * [25-32] toTimestamp         int64   0 = no upper bound
 * [33-40] maxEntries          int64   0 = unlimited
 * </pre>
 *
 * <p>Var-length: streamName (empty = all streams)
 */
public final class ReplayRequestCodec {

    public static final int TEMPLATE_ID = MessageTypes.REPLAY_REQUEST;
    public static final int BLOCK_LENGTH = 41;

    private static final int CORRELATION_ID_OFFSET = 0;
    private static final int DIRECTION_OFFSET = 8;
    private static final int FROM_SEQ_NUM_OFFSET = 9;
    private static final int TO_SEQ_NUM_OFFSET = 13;
    private static final int FROM_TIMESTAMP_OFFSET = 17;
    private static final int TO_TIMESTAMP_OFFSET = 25;
    private static final int MAX_ENTRIES_OFFSET = 33;

    private ReplayRequestCodec() {
    }

    public static int encode(MutableDirectBuffer buffer, int offset,
                             long correlationId, byte direction,
                             int fromSeqNum, int toSeqNum,
                             long fromTimestamp, long toTimestamp,
                             long maxEntries, String streamName) {
        AeronMessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID,
                MessageTypes.SCHEMA_ID, MessageTypes.SCHEMA_VERSION);
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        buffer.putLong(pos + CORRELATION_ID_OFFSET, correlationId, ByteOrder.LITTLE_ENDIAN);
        buffer.putByte(pos + DIRECTION_OFFSET, direction);
        buffer.putInt(pos + FROM_SEQ_NUM_OFFSET, fromSeqNum, ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(pos + TO_SEQ_NUM_OFFSET, toSeqNum, ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(pos + FROM_TIMESTAMP_OFFSET, fromTimestamp, ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(pos + TO_TIMESTAMP_OFFSET, toTimestamp, ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(pos + MAX_ENTRIES_OFFSET, maxEntries, ByteOrder.LITTLE_ENDIAN);
        pos += BLOCK_LENGTH;

        // streamName
        byte[] streamBytes = (streamName != null && !streamName.isEmpty())
                ? streamName.getBytes(StandardCharsets.UTF_8) : new byte[0];
        buffer.putInt(pos, streamBytes.length, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        if (streamBytes.length > 0) {
            buffer.putBytes(pos, streamBytes);
            pos += streamBytes.length;
        }

        return pos - offset;
    }

    public static long decodeCorrelationId(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + CORRELATION_ID_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static byte decodeDirection(DirectBuffer buffer, int offset) {
        return buffer.getByte(offset + AeronMessageHeader.HEADER_SIZE + DIRECTION_OFFSET);
    }

    public static int decodeFromSeqNum(DirectBuffer buffer, int offset) {
        return buffer.getInt(offset + AeronMessageHeader.HEADER_SIZE + FROM_SEQ_NUM_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static int decodeToSeqNum(DirectBuffer buffer, int offset) {
        return buffer.getInt(offset + AeronMessageHeader.HEADER_SIZE + TO_SEQ_NUM_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static long decodeFromTimestamp(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + FROM_TIMESTAMP_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static long decodeToTimestamp(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + TO_TIMESTAMP_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static long decodeMaxEntries(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + MAX_ENTRIES_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static String decodeStreamName(DirectBuffer buffer, int offset) {
        int pos = offset + AeronMessageHeader.HEADER_SIZE + BLOCK_LENGTH;
        int len = buffer.getInt(pos, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        return len > 0 ? buffer.getStringWithoutLengthAscii(pos, len) : "";
    }
}
