package com.omnibridge.pillar.message;

import com.omnibridge.sbe.message.SbeMessage;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Base class for all NYSE Pillar messages.
 * <p>
 * NYSE Pillar uses a binary protocol with:
 * <ul>
 *   <li>4-byte message header (MsgHeader)</li>
 *   <li>Little-endian byte order</li>
 *   <li>Price scale of 8 (123000000 = $1.23)</li>
 * </ul>
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       2       Message Type (MsgType)
 * 2       2       Message Length (includes header)
 * 4       var     Message Body
 * </pre>
 * <p>
 * For sequenced messages (Application Layer), a SeqMsg header follows:
 * <pre>
 * Offset  Length  Field
 * 0       8       Stream ID
 * 8       8       Sequence Number
 * 16      8       Timestamp (nanoseconds since epoch)
 * 24      var     Application Message
 * </pre>
 */
public abstract class PillarMessage extends SbeMessage {

    /** NYSE Pillar schema ID */
    public static final int SCHEMA_ID = 100;

    /** NYSE Pillar schema version */
    public static final int SCHEMA_VERSION = 1;

    /** Byte order for NYSE Pillar (little-endian) */
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    /** Size of the message header (MsgHeader) */
    public static final int MSG_HEADER_SIZE = 4;

    /** Size of the SeqMsg header for sequenced messages */
    public static final int SEQ_MSG_HEADER_SIZE = 24;

    /** Price scale factor (10^8) */
    public static final long PRICE_SCALE = 100_000_000L;

    /** Offset of message type in header */
    public static final int MSG_TYPE_OFFSET = 0;

    /** Offset of message length in header */
    public static final int MSG_LENGTH_OFFSET = 2;

    /** Offset of stream ID in SeqMsg header */
    protected static final int STREAM_ID_OFFSET = 0;

    /** Offset of sequence number in SeqMsg header */
    protected static final int SEQ_NUM_OFFSET = 8;

    /** Offset of timestamp in SeqMsg header */
    protected static final int TIMESTAMP_OFFSET = 16;

    @Override
    public int getSchemaId() {
        return SCHEMA_ID;
    }

    @Override
    public int getSchemaVersion() {
        return SCHEMA_VERSION;
    }

    /**
     * Wraps this message around a buffer for reading.
     * Assumes the buffer starts at the message header.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @param length the total message length including header
     * @return this message for method chaining
     */
    @Override
    public SbeMessage wrapForReading(DirectBuffer buffer, int offset, int length) {
        this.buffer = buffer;
        this.offset = offset + MSG_HEADER_SIZE;
        this.length = length - MSG_HEADER_SIZE;
        return this;
    }

    /**
     * Wraps this message around a buffer for writing.
     *
     * @param buffer the buffer to write to
     * @param offset the offset to start writing at (includes header)
     * @param maxLength the maximum available space
     * @param claimIndex the ring buffer claim index
     * @return this message for method chaining
     */
    @Override
    public SbeMessage wrapForWriting(MutableDirectBuffer buffer, int offset, int maxLength, int claimIndex) {
        this.buffer = buffer;
        this.offset = offset + MSG_HEADER_SIZE;
        this.length = maxLength - MSG_HEADER_SIZE;
        this.claimIndex = claimIndex;
        return this;
    }

    /**
     * Gets the total encoded length including message header.
     *
     * @return the total message length
     */
    @Override
    public int getEncodedLength() {
        return MSG_HEADER_SIZE + getBlockLength();
    }

    /**
     * Writes the message header.
     */
    @Override
    public void writeHeader() {
        MutableDirectBuffer buf = (MutableDirectBuffer) buffer;
        int headerOffset = offset - MSG_HEADER_SIZE;

        // Write message type
        buf.putShort(headerOffset + MSG_TYPE_OFFSET, (short) getMessageType().getTemplateId(), BYTE_ORDER);

        // Write message length (includes header)
        buf.putShort(headerOffset + MSG_LENGTH_OFFSET, (short) getEncodedLength(), BYTE_ORDER);
    }

    /**
     * Completes the message for sending.
     *
     * @return the total encoded length including header
     */
    @Override
    public int complete() {
        this.length = getEncodedLength();
        return this.length;
    }

    /**
     * Gets the message type for this Pillar message.
     *
     * @return the message type
     */
    @Override
    public abstract PillarMessageType getMessageType();

    // ==================== Helper Methods ====================

    /**
     * Gets a 16-bit unsigned integer from the buffer.
     */
    protected int getUInt16(int fieldOffset) {
        return buffer.getShort(offset + fieldOffset, BYTE_ORDER) & 0xFFFF;
    }

    /**
     * Puts a 16-bit unsigned integer to the buffer.
     */
    protected void putUInt16(int fieldOffset, int value) {
        ((MutableDirectBuffer) buffer).putShort(offset + fieldOffset, (short) value, BYTE_ORDER);
    }

    /**
     * Gets a 32-bit unsigned integer from the buffer.
     */
    protected long getUInt32(int fieldOffset) {
        return buffer.getInt(offset + fieldOffset, BYTE_ORDER) & 0xFFFFFFFFL;
    }

    /**
     * Puts a 32-bit unsigned integer to the buffer.
     */
    protected void putUInt32(int fieldOffset, long value) {
        ((MutableDirectBuffer) buffer).putInt(offset + fieldOffset, (int) value, BYTE_ORDER);
    }

    /**
     * Gets a 64-bit signed integer from the buffer.
     */
    protected long getInt64(int fieldOffset) {
        return buffer.getLong(offset + fieldOffset, BYTE_ORDER);
    }

    /**
     * Puts a 64-bit signed integer to the buffer.
     */
    protected void putInt64(int fieldOffset, long value) {
        ((MutableDirectBuffer) buffer).putLong(offset + fieldOffset, value, BYTE_ORDER);
    }

    /**
     * Gets a 64-bit unsigned integer from the buffer.
     */
    protected long getUInt64(int fieldOffset) {
        return buffer.getLong(offset + fieldOffset, BYTE_ORDER);
    }

    /**
     * Puts a 64-bit unsigned integer to the buffer.
     */
    protected void putUInt64(int fieldOffset, long value) {
        ((MutableDirectBuffer) buffer).putLong(offset + fieldOffset, value, BYTE_ORDER);
    }

    /**
     * Gets a price value (scaled by 10^8).
     */
    protected double getPrice(int fieldOffset) {
        return (double) getInt64(fieldOffset) / PRICE_SCALE;
    }

    /**
     * Puts a price value (scaled by 10^8).
     */
    protected void putPrice(int fieldOffset, double price) {
        putInt64(fieldOffset, (long) (price * PRICE_SCALE));
    }

    /**
     * Gets a raw price value (unscaled).
     */
    protected long getRawPrice(int fieldOffset) {
        return getInt64(fieldOffset);
    }

    /**
     * Puts a raw price value (unscaled).
     */
    protected void putRawPrice(int fieldOffset, long rawPrice) {
        putInt64(fieldOffset, rawPrice);
    }

    /**
     * Gets an ASCII string from the buffer.
     */
    protected String getString(int fieldOffset, int length) {
        byte[] bytes = new byte[length];
        buffer.getBytes(offset + fieldOffset, bytes);
        // Find null terminator
        int end = 0;
        while (end < length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Puts an ASCII string to the buffer (null-padded).
     */
    protected void putString(int fieldOffset, String value, int length) {
        MutableDirectBuffer buf = (MutableDirectBuffer) buffer;
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);

        // Clear field first
        for (int i = 0; i < length; i++) {
            buf.putByte(offset + fieldOffset + i, (byte) 0);
        }

        // Copy string
        buf.putBytes(offset + fieldOffset, bytes, 0, copyLen);
    }

    /**
     * Gets a single byte from the buffer.
     */
    protected byte getByte(int fieldOffset) {
        return buffer.getByte(offset + fieldOffset);
    }

    /**
     * Puts a single byte to the buffer.
     */
    protected void putByte(int fieldOffset, byte value) {
        ((MutableDirectBuffer) buffer).putByte(offset + fieldOffset, value);
    }

    /**
     * Gets a single character from the buffer.
     */
    protected char getChar(int fieldOffset) {
        return (char) buffer.getByte(offset + fieldOffset);
    }

    /**
     * Puts a single character to the buffer.
     */
    protected void putChar(int fieldOffset, char value) {
        ((MutableDirectBuffer) buffer).putByte(offset + fieldOffset, (byte) value);
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return getClass().getSimpleName() + "[not wrapped]";
        }
        return getClass().getSimpleName() + "[msgType=" + getMessageType() +
               ", length=" + getEncodedLength() + "]";
    }
}
