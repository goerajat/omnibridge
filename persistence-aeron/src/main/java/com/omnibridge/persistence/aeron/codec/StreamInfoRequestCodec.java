package com.omnibridge.persistence.aeron.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * SBE codec for StreamInfoRequestMessage (templateId=5).
 *
 * <p>Block layout (16 bytes):
 * <pre>
 * [0-7]   correlationId    int64
 * [8-15]  publisherId      int64   0 = all publishers
 * </pre>
 *
 * <p>Var-length: streamName (empty = all streams)
 *
 * <p>Backward compatible: old 8-byte block messages are decoded with publisherId=0.
 */
public final class StreamInfoRequestCodec {

    public static final int TEMPLATE_ID = MessageTypes.STREAM_INFO_REQUEST;
    public static final int BLOCK_LENGTH = 16;

    private static final int CORRELATION_ID_OFFSET = 0;
    private static final int PUBLISHER_ID_OFFSET = 8;

    private StreamInfoRequestCodec() {
    }

    /**
     * Encode a stream info request without a publisher ID (defaults to 0 = all publishers).
     */
    public static int encode(MutableDirectBuffer buffer, int offset,
                             long correlationId, String streamName) {
        return encode(buffer, offset, correlationId, streamName, 0);
    }

    /**
     * Encode a stream info request with a publisher ID for publisher-scoped queries.
     */
    public static int encode(MutableDirectBuffer buffer, int offset,
                             long correlationId, String streamName,
                             long publisherId) {
        AeronMessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID,
                MessageTypes.SCHEMA_ID, MessageTypes.SCHEMA_VERSION);
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        buffer.putLong(pos + CORRELATION_ID_OFFSET, correlationId, ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(pos + PUBLISHER_ID_OFFSET, publisherId, ByteOrder.LITTLE_ENDIAN);
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

    /**
     * Decode publisher ID with backward compatibility.
     * Old 8-byte block messages return 0 (all publishers).
     */
    public static long decodePublisherId(DirectBuffer buffer, int offset) {
        int blockLength = AeronMessageHeader.readBlockLength(buffer, offset);
        if (blockLength >= BLOCK_LENGTH) {
            return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + PUBLISHER_ID_OFFSET,
                    ByteOrder.LITTLE_ENDIAN);
        }
        return 0;
    }

    /**
     * Decode stream name using the header's block length for backward compatibility.
     */
    public static String decodeStreamName(DirectBuffer buffer, int offset) {
        int blockLength = AeronMessageHeader.readBlockLength(buffer, offset);
        int pos = offset + AeronMessageHeader.HEADER_SIZE + blockLength;
        int len = buffer.getInt(pos, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        return len > 0 ? buffer.getStringWithoutLengthAscii(pos, len) : "";
    }
}
