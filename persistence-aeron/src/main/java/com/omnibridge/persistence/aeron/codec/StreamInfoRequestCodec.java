package com.omnibridge.persistence.aeron.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * SBE codec for StreamInfoRequestMessage (templateId=5).
 *
 * <p>Block layout (8 bytes):
 * <pre>
 * [0-7]   correlationId    int64
 * </pre>
 *
 * <p>Var-length: streamName (empty = all streams)
 */
public final class StreamInfoRequestCodec {

    public static final int TEMPLATE_ID = MessageTypes.STREAM_INFO_REQUEST;
    public static final int BLOCK_LENGTH = 8;

    private static final int CORRELATION_ID_OFFSET = 0;

    private StreamInfoRequestCodec() {
    }

    public static int encode(MutableDirectBuffer buffer, int offset,
                             long correlationId, String streamName) {
        AeronMessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID,
                MessageTypes.SCHEMA_ID, MessageTypes.SCHEMA_VERSION);
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        buffer.putLong(pos + CORRELATION_ID_OFFSET, correlationId, ByteOrder.LITTLE_ENDIAN);
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

    public static String decodeStreamName(DirectBuffer buffer, int offset) {
        int pos = offset + AeronMessageHeader.HEADER_SIZE + BLOCK_LENGTH;
        int len = buffer.getInt(pos, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        return len > 0 ? buffer.getStringWithoutLengthAscii(pos, len) : "";
    }
}
