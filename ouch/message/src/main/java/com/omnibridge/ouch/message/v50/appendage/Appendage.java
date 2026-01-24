package com.omnibridge.ouch.message.v50.appendage;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Base class for OUCH 5.0 message appendages.
 *
 * <p>Appendages follow a tag-length-value format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Tag (appendage type identifier)
 * 1       2       Length (data length, big-endian)
 * 3       var     Data (appendage-specific)
 * </pre>
 */
public abstract class Appendage {

    public static final int TAG_OFFSET = 0;
    public static final int LENGTH_OFFSET = 1;
    public static final int DATA_OFFSET = 3;
    public static final int HEADER_SIZE = 3;

    protected MutableDirectBuffer buffer;
    protected int offset;
    protected int dataLength;

    /**
     * Get the appendage type.
     */
    public abstract AppendageType getType();

    /**
     * Get the minimum data length for this appendage.
     */
    public int getMinDataLength() {
        return getType().getMinDataLength();
    }

    /**
     * Get the total appendage length (header + data).
     */
    public int getTotalLength() {
        return HEADER_SIZE + dataLength;
    }

    /**
     * Get the data length.
     */
    public int getDataLength() {
        return dataLength;
    }

    /**
     * Wrap this appendage for reading from a buffer.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @return this appendage for chaining
     */
    public Appendage wrapForReading(DirectBuffer buffer, int offset) {
        if (buffer instanceof MutableDirectBuffer) {
            this.buffer = (MutableDirectBuffer) buffer;
        } else {
            throw new IllegalArgumentException("Buffer must be MutableDirectBuffer");
        }
        this.offset = offset;
        this.dataLength = buffer.getShort(offset + LENGTH_OFFSET, ByteOrder.BIG_ENDIAN) & 0xFFFF;
        return this;
    }

    /**
     * Wrap this appendage for writing to a buffer.
     *
     * @param buffer the target buffer
     * @param offset the starting offset
     * @param dataLength the data length to write
     * @return this appendage for chaining
     */
    public Appendage wrapForWriting(MutableDirectBuffer buffer, int offset, int dataLength) {
        this.buffer = buffer;
        this.offset = offset;
        this.dataLength = dataLength;
        writeHeader();
        return this;
    }

    /**
     * Write the appendage header (tag and length).
     */
    protected void writeHeader() {
        buffer.putByte(offset + TAG_OFFSET, getType().getTag());
        buffer.putShort(offset + LENGTH_OFFSET, (short) dataLength, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Read a byte from the data section.
     */
    protected byte getDataByte(int dataOffset) {
        return buffer.getByte(offset + DATA_OFFSET + dataOffset);
    }

    /**
     * Read a short from the data section.
     */
    protected short getDataShort(int dataOffset) {
        return buffer.getShort(offset + DATA_OFFSET + dataOffset, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Read an int from the data section.
     */
    protected int getDataInt(int dataOffset) {
        return buffer.getInt(offset + DATA_OFFSET + dataOffset, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Read a long from the data section.
     */
    protected long getDataLong(int dataOffset) {
        return buffer.getLong(offset + DATA_OFFSET + dataOffset, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Write a byte to the data section.
     */
    protected void putDataByte(int dataOffset, byte value) {
        buffer.putByte(offset + DATA_OFFSET + dataOffset, value);
    }

    /**
     * Write a short to the data section.
     */
    protected void putDataShort(int dataOffset, short value) {
        buffer.putShort(offset + DATA_OFFSET + dataOffset, value, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Write an int to the data section.
     */
    protected void putDataInt(int dataOffset, int value) {
        buffer.putInt(offset + DATA_OFFSET + dataOffset, value, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Write a long to the data section.
     */
    protected void putDataLong(int dataOffset, long value) {
        buffer.putLong(offset + DATA_OFFSET + dataOffset, value, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Reset the appendage state.
     */
    public void reset() {
        this.buffer = null;
        this.offset = 0;
        this.dataLength = 0;
    }

    @Override
    public String toString() {
        return getType().getDescription() + "{dataLen=" + dataLength + "}";
    }
}
