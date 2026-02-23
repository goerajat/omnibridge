package com.omnibridge.persistence.aeron.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * SBE codec for ReplayCompleteMessage (templateId=4).
 *
 * <p>Block layout (17 bytes):
 * <pre>
 * [0-7]   correlationId    int64
 * [8-15]  entryCount       int64
 * [16]    status           uint8   0=SUCCESS, 1=PARTIAL, 2=ERROR
 * </pre>
 *
 * <p>Var-length: errorMessage (empty if success)
 */
public final class ReplayCompleteCodec {

    public static final int TEMPLATE_ID = MessageTypes.REPLAY_COMPLETE;
    public static final int BLOCK_LENGTH = 17;

    private static final int CORRELATION_ID_OFFSET = 0;
    private static final int ENTRY_COUNT_OFFSET = 8;
    private static final int STATUS_OFFSET = 16;

    private ReplayCompleteCodec() {
    }

    public static int encode(MutableDirectBuffer buffer, int offset,
                             long correlationId, long entryCount, byte status,
                             String errorMessage) {
        AeronMessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID,
                MessageTypes.SCHEMA_ID, MessageTypes.SCHEMA_VERSION);
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        buffer.putLong(pos + CORRELATION_ID_OFFSET, correlationId, ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(pos + ENTRY_COUNT_OFFSET, entryCount, ByteOrder.LITTLE_ENDIAN);
        buffer.putByte(pos + STATUS_OFFSET, status);
        pos += BLOCK_LENGTH;

        // errorMessage
        byte[] errBytes = (errorMessage != null && !errorMessage.isEmpty())
                ? errorMessage.getBytes(StandardCharsets.UTF_8) : new byte[0];
        buffer.putInt(pos, errBytes.length, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        if (errBytes.length > 0) {
            buffer.putBytes(pos, errBytes);
            pos += errBytes.length;
        }

        return pos - offset;
    }

    public static long decodeCorrelationId(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + CORRELATION_ID_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static long decodeEntryCount(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + ENTRY_COUNT_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }

    public static byte decodeStatus(DirectBuffer buffer, int offset) {
        return buffer.getByte(offset + AeronMessageHeader.HEADER_SIZE + STATUS_OFFSET);
    }

    public static String decodeErrorMessage(DirectBuffer buffer, int offset) {
        int pos = offset + AeronMessageHeader.HEADER_SIZE + BLOCK_LENGTH;
        int len = buffer.getInt(pos, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        return len > 0 ? buffer.getStringWithoutLengthAscii(pos, len) : "";
    }
}
