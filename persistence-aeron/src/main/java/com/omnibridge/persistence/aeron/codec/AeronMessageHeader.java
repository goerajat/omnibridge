package com.omnibridge.persistence.aeron.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * SBE message header encoder/decoder for the Aeron persistence protocol.
 *
 * <p>Header layout (8 bytes, little-endian):
 * <pre>
 * Offset  Length  Field
 * 0       2       blockLength   Size of the fixed-length message body
 * 2       2       templateId    Message type identifier
 * 4       2       schemaId      Schema identifier (100 for Aeron persistence)
 * 6       2       version       Schema version
 * </pre>
 */
public final class AeronMessageHeader {

    public static final int HEADER_SIZE = 8;

    public static final int BLOCK_LENGTH_OFFSET = 0;
    public static final int TEMPLATE_ID_OFFSET = 2;
    public static final int SCHEMA_ID_OFFSET = 4;
    public static final int VERSION_OFFSET = 6;

    private AeronMessageHeader() {
    }

    public static void write(MutableDirectBuffer buffer, int offset,
                             int blockLength, int templateId, int schemaId, int version) {
        buffer.putShort(offset + BLOCK_LENGTH_OFFSET, (short) blockLength, ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(offset + TEMPLATE_ID_OFFSET, (short) templateId, ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(offset + SCHEMA_ID_OFFSET, (short) schemaId, ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(offset + VERSION_OFFSET, (short) version, ByteOrder.LITTLE_ENDIAN);
    }

    public static int readBlockLength(DirectBuffer buffer, int offset) {
        return buffer.getShort(offset + BLOCK_LENGTH_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    public static int readTemplateId(DirectBuffer buffer, int offset) {
        return buffer.getShort(offset + TEMPLATE_ID_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    public static int readSchemaId(DirectBuffer buffer, int offset) {
        return buffer.getShort(offset + SCHEMA_ID_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    public static int readVersion(DirectBuffer buffer, int offset) {
        return buffer.getShort(offset + VERSION_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }
}
