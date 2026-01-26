package com.omnibridge.sbe.message;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Base class for all SBE (Simple Binary Encoding) messages.
 * <p>
 * Implements the flyweight pattern for zero-copy message handling. Messages wrap
 * raw byte buffers without copying data, enabling ultra-low latency processing.
 * <p>
 * SBE messages have a standard header format:
 * <pre>
 * Offset  Length  Field
 * 0       2       Block Length (message body length excluding header)
 * 2       2       Template ID (identifies message type)
 * 4       2       Schema ID (identifies the schema)
 * 6       2       Schema Version
 * 8       var     Message Body
 * </pre>
 * <p>
 * SBE uses little-endian byte order by default.
 * <p>
 * Subclasses should define field offsets as static constants and provide
 * typed getter/setter methods for each field.
 *
 * @see SbeMessageHeader
 * @see SbeMessageFactory
 */
public abstract class SbeMessage {

    /** Standard SBE header size in bytes */
    public static final int HEADER_SIZE = SbeMessageHeader.ENCODED_LENGTH;

    /** Default byte order for SBE (little-endian) */
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    /** The buffer containing the message data */
    protected DirectBuffer buffer;

    /** Offset within the buffer where this message starts */
    protected int offset;

    /** Total length of the message including header */
    protected int length;

    /** Claim index for ring buffer integration (used with tryClaim pattern) */
    protected int claimIndex = -1;

    /** Cached header for reading header fields */
    protected final SbeMessageHeader header = new SbeMessageHeader();

    /**
     * Wraps this message around a buffer for reading.
     * The message header will be parsed to determine message type and length.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset within the buffer
     * @param length the total message length including header
     * @return this message for method chaining
     */
    public SbeMessage wrapForReading(DirectBuffer buffer, int offset, int length) {
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
        this.claimIndex = -1;
        this.header.wrap(buffer, offset);
        return this;
    }

    /**
     * Wraps this message around a buffer for writing.
     * The caller is responsible for writing the header and setting the length.
     *
     * @param buffer the buffer to write to
     * @param offset the offset within the buffer
     * @param maxLength the maximum available space
     * @param claimIndex the ring buffer claim index (or -1 if not using ring buffer)
     * @return this message for method chaining
     */
    public SbeMessage wrapForWriting(MutableDirectBuffer buffer, int offset,
                                      int maxLength, int claimIndex) {
        this.buffer = buffer;
        this.offset = offset;
        this.length = maxLength;
        this.claimIndex = claimIndex;
        this.header.wrap(buffer, offset);
        return this;
    }

    /**
     * Simplified wrap for writing without ring buffer integration.
     *
     * @param buffer the buffer to write to
     * @param offset the offset within the buffer
     * @return this message for method chaining
     */
    public SbeMessage wrap(MutableDirectBuffer buffer, int offset) {
        return wrapForWriting(buffer, offset, buffer.capacity() - offset, -1);
    }

    /**
     * Resets this message for reuse in a message pool.
     * Clears all buffer references.
     */
    public void reset() {
        this.buffer = null;
        this.offset = 0;
        this.length = 0;
        this.claimIndex = -1;
    }

    /**
     * Gets the template ID that identifies this message type.
     * Must be implemented by subclasses.
     *
     * @return the SBE template ID
     */
    public abstract int getTemplateId();

    /**
     * Gets the schema ID for this message.
     * Must be implemented by subclasses.
     *
     * @return the SBE schema ID
     */
    public abstract int getSchemaId();

    /**
     * Gets the schema version for this message.
     * Must be implemented by subclasses.
     *
     * @return the SBE schema version
     */
    public abstract int getSchemaVersion();

    /**
     * Gets the block length (message body size excluding groups and var data).
     * Must be implemented by subclasses.
     *
     * @return the block length in bytes
     */
    public abstract int getBlockLength();

    /**
     * Gets the total encoded length of this message including header.
     * For fixed-size messages, this is HEADER_SIZE + getBlockLength().
     * For messages with groups or var data, subclasses must override.
     *
     * @return the total message length in bytes
     */
    public int getEncodedLength() {
        return HEADER_SIZE + getBlockLength();
    }

    /**
     * Gets the message type for dispatch purposes.
     * Subclasses should return their specific message type enum value.
     *
     * @return the message type
     */
    public abstract SbeMessageType getMessageType();

    /**
     * Checks if this message is currently wrapping a buffer.
     *
     * @return true if wrapped, false otherwise
     */
    public boolean isWrapped() {
        return buffer != null;
    }

    /**
     * Gets the underlying buffer.
     *
     * @return the buffer or null if not wrapped
     */
    public DirectBuffer getBuffer() {
        return buffer;
    }

    /**
     * Gets the offset within the buffer.
     *
     * @return the offset
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Gets the total message length.
     *
     * @return the length
     */
    public int getLength() {
        return length;
    }

    /**
     * Gets the ring buffer claim index.
     *
     * @return the claim index or -1 if not using ring buffer
     */
    public int getClaimIndex() {
        return claimIndex;
    }

    /**
     * Gets the message header.
     *
     * @return the header
     */
    public SbeMessageHeader getHeader() {
        return header;
    }

    // ==========================================================================
    // Buffer Access Utilities (Little-Endian)
    // ==========================================================================

    /**
     * Gets a byte from the message body at the specified offset.
     *
     * @param fieldOffset offset from the start of message body (after header)
     * @return the byte value
     */
    protected byte getByte(int fieldOffset) {
        return buffer.getByte(offset + HEADER_SIZE + fieldOffset);
    }

    /**
     * Puts a byte into the message body at the specified offset.
     *
     * @param fieldOffset offset from the start of message body
     * @param value the byte value
     */
    protected void putByte(int fieldOffset, byte value) {
        ((MutableDirectBuffer) buffer).putByte(offset + HEADER_SIZE + fieldOffset, value);
    }

    /**
     * Gets a short (16-bit) from the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @return the short value
     */
    protected short getShort(int fieldOffset) {
        return buffer.getShort(offset + HEADER_SIZE + fieldOffset, BYTE_ORDER);
    }

    /**
     * Puts a short into the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @param value the short value
     */
    protected void putShort(int fieldOffset, short value) {
        ((MutableDirectBuffer) buffer).putShort(offset + HEADER_SIZE + fieldOffset, value, BYTE_ORDER);
    }

    /**
     * Gets an unsigned short (16-bit) as int from the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @return the unsigned short value as int
     */
    protected int getUnsignedShort(int fieldOffset) {
        return buffer.getShort(offset + HEADER_SIZE + fieldOffset, BYTE_ORDER) & 0xFFFF;
    }

    /**
     * Gets an int (32-bit) from the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @return the int value
     */
    protected int getInt(int fieldOffset) {
        return buffer.getInt(offset + HEADER_SIZE + fieldOffset, BYTE_ORDER);
    }

    /**
     * Puts an int into the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @param value the int value
     */
    protected void putInt(int fieldOffset, int value) {
        ((MutableDirectBuffer) buffer).putInt(offset + HEADER_SIZE + fieldOffset, value, BYTE_ORDER);
    }

    /**
     * Gets an unsigned int (32-bit) as long from the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @return the unsigned int value as long
     */
    protected long getUnsignedInt(int fieldOffset) {
        return buffer.getInt(offset + HEADER_SIZE + fieldOffset, BYTE_ORDER) & 0xFFFFFFFFL;
    }

    /**
     * Gets a long (64-bit) from the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @return the long value
     */
    protected long getLong(int fieldOffset) {
        return buffer.getLong(offset + HEADER_SIZE + fieldOffset, BYTE_ORDER);
    }

    /**
     * Puts a long into the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @param value the long value
     */
    protected void putLong(int fieldOffset, long value) {
        ((MutableDirectBuffer) buffer).putLong(offset + HEADER_SIZE + fieldOffset, value, BYTE_ORDER);
    }

    /**
     * Gets a char (single ASCII character) from the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @return the char value
     */
    protected char getChar(int fieldOffset) {
        return (char) buffer.getByte(offset + HEADER_SIZE + fieldOffset);
    }

    /**
     * Puts a char into the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @param value the char value
     */
    protected void putChar(int fieldOffset, char value) {
        ((MutableDirectBuffer) buffer).putByte(offset + HEADER_SIZE + fieldOffset, (byte) value);
    }

    /**
     * Gets a fixed-length string from the message body.
     * Strings are null-terminated or space-padded.
     *
     * @param fieldOffset offset from the start of message body
     * @param length the fixed field length
     * @return the string value (trimmed)
     */
    protected String getString(int fieldOffset, int length) {
        byte[] bytes = new byte[length];
        buffer.getBytes(offset + HEADER_SIZE + fieldOffset, bytes);

        // Find null terminator or end
        int end = length;
        for (int i = 0; i < length; i++) {
            if (bytes[i] == 0) {
                end = i;
                break;
            }
        }

        return new String(bytes, 0, end).trim();
    }

    /**
     * Puts a fixed-length string into the message body.
     * Pads with nulls if shorter than field length.
     *
     * @param fieldOffset offset from the start of message body
     * @param length the fixed field length
     * @param value the string value
     */
    protected void putString(int fieldOffset, int length, String value) {
        MutableDirectBuffer mutableBuffer = (MutableDirectBuffer) buffer;
        int absOffset = offset + HEADER_SIZE + fieldOffset;

        byte[] bytes = value != null ? value.getBytes() : new byte[0];
        int copyLen = Math.min(bytes.length, length);

        mutableBuffer.putBytes(absOffset, bytes, 0, copyLen);

        // Null-pad remaining bytes
        for (int i = copyLen; i < length; i++) {
            mutableBuffer.putByte(absOffset + i, (byte) 0);
        }
    }

    /**
     * Gets raw bytes from the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @param dest destination array
     * @param destOffset offset in destination
     * @param length number of bytes to copy
     */
    protected void getBytes(int fieldOffset, byte[] dest, int destOffset, int length) {
        buffer.getBytes(offset + HEADER_SIZE + fieldOffset, dest, destOffset, length);
    }

    /**
     * Puts raw bytes into the message body.
     *
     * @param fieldOffset offset from the start of message body
     * @param src source array
     * @param srcOffset offset in source
     * @param length number of bytes to copy
     */
    protected void putBytes(int fieldOffset, byte[] src, int srcOffset, int length) {
        ((MutableDirectBuffer) buffer).putBytes(offset + HEADER_SIZE + fieldOffset, src, srcOffset, length);
    }

    // ==========================================================================
    // Header Writing
    // ==========================================================================

    /**
     * Writes the SBE message header.
     * Should be called after wrapping for writing and before writing body fields.
     */
    public void writeHeader() {
        header.blockLength(getBlockLength())
              .templateId(getTemplateId())
              .schemaId(getSchemaId())
              .version(getSchemaVersion());
    }

    /**
     * Completes the message for sending.
     * Updates the length field and prepares for transmission.
     *
     * @return the total encoded length
     */
    public int complete() {
        this.length = getEncodedLength();
        return this.length;
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return getClass().getSimpleName() + "[not wrapped]";
        }
        return getClass().getSimpleName() + "[templateId=" + getTemplateId() +
               ", length=" + getEncodedLength() + "]";
    }
}
