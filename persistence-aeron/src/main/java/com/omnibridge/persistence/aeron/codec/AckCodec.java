package com.omnibridge.persistence.aeron.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * SBE codec for AckMessage (templateId=8).
 *
 * <p>Block layout (17 bytes):
 * <pre>
 * [0-7]   correlationId    int64   Matches original message
 * [8-15]  timestamp        int64   Server receive timestamp
 * [16]    status           uint8   0=OK, 1=ERROR
 * </pre>
 *
 * <p>No var-length data.
 */
public final class AckCodec {

    public static final int TEMPLATE_ID = MessageTypes.ACK;
    public static final int BLOCK_LENGTH = 17;
    public static final int ENCODED_LENGTH = AeronMessageHeader.HEADER_SIZE + BLOCK_LENGTH;

    private static final int CORRELATION_ID_OFFSET = 0;
    private static final int TIMESTAMP_OFFSET = 8;
    private static final int STATUS_OFFSET = 16;

    private AckCodec() {
    }

    public static int encode(MutableDirectBuffer buffer, int offset,
                             long correlationId, long timestamp, byte status) {
        AeronMessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID,
                MessageTypes.SCHEMA_ID, MessageTypes.SCHEMA_VERSION);
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        buffer.putLong(pos + CORRELATION_ID_OFFSET, correlationId, ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(pos + TIMESTAMP_OFFSET, timestamp, ByteOrder.LITTLE_ENDIAN);
        buffer.putByte(pos + STATUS_OFFSET, status);

        return ENCODED_LENGTH;
    }

    public static long decodeCorrelationId(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + CORRELATION_ID_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static long decodeTimestamp(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + TIMESTAMP_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static byte decodeStatus(DirectBuffer buffer, int offset) {
        return buffer.getByte(offset + AeronMessageHeader.HEADER_SIZE + STATUS_OFFSET);
    }
}
