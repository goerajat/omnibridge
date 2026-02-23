package com.omnibridge.persistence.aeron.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * SBE codec for HeartbeatMessage (templateId=7).
 *
 * <p>Block layout (16 bytes):
 * <pre>
 * [0-7]   timestamp    int64   Sender epoch millis
 * [8-15]  publisherId  int64   Identifies the sender
 * </pre>
 *
 * <p>No var-length data.
 */
public final class HeartbeatCodec {

    public static final int TEMPLATE_ID = MessageTypes.HEARTBEAT;
    public static final int BLOCK_LENGTH = 16;
    public static final int ENCODED_LENGTH = AeronMessageHeader.HEADER_SIZE + BLOCK_LENGTH;

    private static final int TIMESTAMP_OFFSET = 0;
    private static final int PUBLISHER_ID_OFFSET = 8;

    private HeartbeatCodec() {
    }

    public static int encode(MutableDirectBuffer buffer, int offset,
                             long timestamp, long publisherId) {
        AeronMessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID,
                MessageTypes.SCHEMA_ID, MessageTypes.SCHEMA_VERSION);
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        buffer.putLong(pos + TIMESTAMP_OFFSET, timestamp, ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(pos + PUBLISHER_ID_OFFSET, publisherId, ByteOrder.LITTLE_ENDIAN);

        return ENCODED_LENGTH;
    }

    public static long decodeTimestamp(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + TIMESTAMP_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static long decodePublisherId(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + PUBLISHER_ID_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }
}
