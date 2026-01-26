package com.omnibridge.sbe.message;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for handling SBE variable-length data fields.
 * <p>
 * SBE variable-length data has a header followed by raw data:
 * <pre>
 * Offset  Length  Field
 * 0       1-2     length (8 or 16 bits depending on schema)
 * 1-2     N       data bytes
 * </pre>
 * <p>
 * Variable-length data typically appears at the end of a message,
 * after all fixed fields and repeating groups.
 */
public class SbeVarData {

    /** Byte order for SBE */
    private static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    /** The buffer containing the data */
    private DirectBuffer buffer;

    /** Offset of the var data header */
    private int offset;

    /** True if using 16-bit length header */
    private boolean use16BitLength;

    /** Cached data length */
    private int dataLength;

    /**
     * Wraps the var data for reading with 8-bit length header.
     *
     * @param buffer the buffer
     * @param offset the offset of the length header
     * @return this for method chaining
     */
    public SbeVarData wrap(DirectBuffer buffer, int offset) {
        return wrap(buffer, offset, false);
    }

    /**
     * Wraps the var data for reading.
     *
     * @param buffer the buffer
     * @param offset the offset of the length header
     * @param use16BitLength true for 16-bit length header
     * @return this for method chaining
     */
    public SbeVarData wrap(DirectBuffer buffer, int offset, boolean use16BitLength) {
        this.buffer = buffer;
        this.offset = offset;
        this.use16BitLength = use16BitLength;

        if (use16BitLength) {
            this.dataLength = buffer.getShort(offset, BYTE_ORDER) & 0xFFFF;
        } else {
            this.dataLength = buffer.getByte(offset) & 0xFF;
        }

        return this;
    }

    /**
     * Gets the header size (1 or 2 bytes).
     *
     * @return the header size
     */
    public int getHeaderSize() {
        return use16BitLength ? 2 : 1;
    }

    /**
     * Gets the data length (excluding header).
     *
     * @return the data length
     */
    public int getDataLength() {
        return dataLength;
    }

    /**
     * Gets the total encoded length (header + data).
     *
     * @return the total length
     */
    public int getEncodedLength() {
        return getHeaderSize() + dataLength;
    }

    /**
     * Gets the offset of the actual data (after header).
     *
     * @return the data offset
     */
    public int getDataOffset() {
        return offset + getHeaderSize();
    }

    /**
     * Gets the data as a byte array.
     *
     * @return the data bytes
     */
    public byte[] getData() {
        byte[] data = new byte[dataLength];
        buffer.getBytes(getDataOffset(), data);
        return data;
    }

    /**
     * Gets the data as a UTF-8 string.
     *
     * @return the string value
     */
    public String getDataAsString() {
        byte[] data = getData();
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Gets the data as an ASCII string.
     *
     * @return the string value
     */
    public String getDataAsAscii() {
        byte[] data = getData();
        return new String(data, StandardCharsets.US_ASCII);
    }

    /**
     * Copies the data to a destination buffer.
     *
     * @param dest destination buffer
     * @param destOffset offset in destination
     * @return number of bytes copied
     */
    public int copyTo(MutableDirectBuffer dest, int destOffset) {
        buffer.getBytes(getDataOffset(), dest, destOffset, dataLength);
        return dataLength;
    }

    /**
     * Copies the data to a byte array.
     *
     * @param dest destination array
     * @param destOffset offset in destination
     * @return number of bytes copied
     */
    public int copyTo(byte[] dest, int destOffset) {
        int copyLen = Math.min(dataLength, dest.length - destOffset);
        buffer.getBytes(getDataOffset(), dest, destOffset, copyLen);
        return copyLen;
    }

    /**
     * Checks if the data is empty.
     *
     * @return true if length is 0
     */
    public boolean isEmpty() {
        return dataLength == 0;
    }

    // ==========================================================================
    // Static writing utilities
    // ==========================================================================

    /**
     * Writes variable-length data with 8-bit length header.
     *
     * @param buffer the buffer to write to
     * @param offset the offset to write at
     * @param data the data bytes
     * @return the total bytes written (header + data)
     */
    public static int write(MutableDirectBuffer buffer, int offset, byte[] data) {
        return write(buffer, offset, data, false);
    }

    /**
     * Writes variable-length data.
     *
     * @param buffer the buffer to write to
     * @param offset the offset to write at
     * @param data the data bytes
     * @param use16BitLength true for 16-bit length header
     * @return the total bytes written (header + data)
     */
    public static int write(MutableDirectBuffer buffer, int offset, byte[] data,
                            boolean use16BitLength) {
        int dataLen = data != null ? data.length : 0;
        int headerSize;

        if (use16BitLength) {
            buffer.putShort(offset, (short) dataLen, BYTE_ORDER);
            headerSize = 2;
        } else {
            buffer.putByte(offset, (byte) dataLen);
            headerSize = 1;
        }

        if (dataLen > 0) {
            buffer.putBytes(offset + headerSize, data);
        }

        return headerSize + dataLen;
    }

    /**
     * Writes a string as variable-length data (UTF-8).
     *
     * @param buffer the buffer to write to
     * @param offset the offset to write at
     * @param value the string value
     * @param use16BitLength true for 16-bit length header
     * @return the total bytes written
     */
    public static int writeString(MutableDirectBuffer buffer, int offset, String value,
                                   boolean use16BitLength) {
        byte[] data = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return write(buffer, offset, data, use16BitLength);
    }

    /**
     * Writes an empty variable-length field.
     *
     * @param buffer the buffer to write to
     * @param offset the offset to write at
     * @param use16BitLength true for 16-bit length header
     * @return the header size
     */
    public static int writeEmpty(MutableDirectBuffer buffer, int offset, boolean use16BitLength) {
        if (use16BitLength) {
            buffer.putShort(offset, (short) 0, BYTE_ORDER);
            return 2;
        } else {
            buffer.putByte(offset, (byte) 0);
            return 1;
        }
    }

    @Override
    public String toString() {
        if (buffer == null) {
            return "SbeVarData[not wrapped]";
        }
        return String.format("SbeVarData[length=%d]", dataLength);
    }
}
