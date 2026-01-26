package com.omnibridge.ouch.message;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Base abstract class for all OUCH protocol messages.
 *
 * <p>OUCH messages are binary formatted with fixed-length fields.
 * All numeric fields are big-endian. Alpha fields are left-justified
 * and space-padded on the right.</p>
 *
 * <p>This unified message model supports both reading (receiving) and
 * writing (sending) operations using a flyweight pattern over DirectBuffer.</p>
 *
 * <p>Usage for receiving messages:</p>
 * <pre>{@code
 * OrderAcceptedMessage msg = pool.getMessage(OrderAcceptedMessage.class);
 * msg.wrapForReading(buffer, offset, length);
 * String token = msg.getOrderToken();
 * int shares = msg.getShares();
 * }</pre>
 *
 * <p>Usage for sending messages (with tryClaim pattern):</p>
 * <pre>{@code
 * EnterOrderMessage msg = session.tryClaim(EnterOrderMessage.class);
 * if (msg != null) {
 *     msg.setOrderToken("ORDER001")
 *        .setSide(Side.BUY)
 *        .setShares(100);
 *     session.commit(msg);
 * }
 * }</pre>
 */
public abstract class OuchMessage {

    protected MutableDirectBuffer buffer;
    protected int offset;
    protected int length;
    protected int claimIndex = -1;

    /**
     * Wrap a buffer region for reading (receiving messages).
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return this message for chaining
     */
    public OuchMessage wrapForReading(DirectBuffer buffer, int offset, int length) {
        // DirectBuffer can be cast to MutableDirectBuffer for unified handling
        // but we only use read operations when wrapped for reading
        if (buffer instanceof MutableDirectBuffer) {
            this.buffer = (MutableDirectBuffer) buffer;
        } else {
            throw new IllegalArgumentException("Buffer must be MutableDirectBuffer");
        }
        this.offset = offset;
        this.length = length;
        this.claimIndex = -1;
        return this;
    }

    /**
     * Wrap a buffer region for writing (sending messages).
     *
     * @param buffer the target buffer
     * @param offset the starting offset
     * @param maxLength the maximum available length
     * @param claimIndex the claim index for commit/abort (-1 if not using tryClaim)
     * @return this message for chaining
     */
    public OuchMessage wrapForWriting(MutableDirectBuffer buffer, int offset, int maxLength, int claimIndex) {
        this.buffer = buffer;
        this.offset = offset;
        this.length = maxLength;
        this.claimIndex = claimIndex;
        writeMessageType();
        return this;
    }

    /**
     * Wrap a buffer region for writing (without claim index).
     *
     * @param buffer the target buffer
     * @param offset the starting offset
     * @return this message for chaining
     */
    public OuchMessage wrap(MutableDirectBuffer buffer, int offset) {
        return wrapForWriting(buffer, offset, getMessageLength(), -1);
    }

    /**
     * Write the message type code at the start of the message.
     */
    protected void writeMessageType() {
        buffer.putByte(offset, getMessageTypeCode());
    }

    /**
     * Reset the message state for reuse.
     */
    public void reset() {
        this.buffer = null;
        this.offset = 0;
        this.length = 0;
        this.claimIndex = -1;
    }

    /**
     * Get the underlying buffer.
     */
    public MutableDirectBuffer buffer() {
        return buffer;
    }

    /**
     * Get the buffer offset.
     */
    public int offset() {
        return offset;
    }

    /**
     * Get the available length.
     */
    public int length() {
        return length;
    }

    /**
     * Get the claim index (for tryClaim pattern).
     * Returns -1 if not using tryClaim.
     */
    public int getClaimIndex() {
        return claimIndex;
    }

    /**
     * Check if this message was claimed via tryClaim.
     */
    public boolean isClaimed() {
        return claimIndex >= 0;
    }

    /**
     * Complete the message and return the encoded length.
     * This should be called after all fields are set.
     *
     * @return the total encoded length in bytes
     */
    public int complete() {
        return getMessageLength();
    }

    /**
     * Get the OUCH protocol version for this message.
     * Default implementation returns V42 for backward compatibility.
     */
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    /**
     * Get the message type.
     */
    public abstract OuchMessageType getMessageType();

    /**
     * Get the message type code byte.
     */
    public byte getMessageTypeCode() {
        return getMessageType().getCodeByte();
    }

    /**
     * Get the total message length in bytes.
     */
    public abstract int getMessageLength();

    /**
     * Check if this is an inbound message (client to exchange).
     */
    public boolean isInbound() {
        return getMessageType().isInbound();
    }

    /**
     * Check if this is an outbound message (exchange to client).
     */
    public boolean isOutbound() {
        return getMessageType().isOutbound();
    }

    // =====================================================
    // Field reading utilities (for receiving messages)
    // =====================================================

    /**
     * Read a single byte at the specified offset from message start.
     */
    protected byte getByte(int fieldOffset) {
        return buffer.getByte(offset + fieldOffset);
    }

    /**
     * Read an unsigned byte at the specified offset.
     */
    protected int getUnsignedByte(int fieldOffset) {
        return buffer.getByte(offset + fieldOffset) & 0xFF;
    }

    /**
     * Read a short (2 bytes, big-endian) at the specified offset.
     */
    protected short getShort(int fieldOffset) {
        return buffer.getShort(offset + fieldOffset, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Read an unsigned short at the specified offset.
     */
    protected int getUnsignedShort(int fieldOffset) {
        return buffer.getShort(offset + fieldOffset, ByteOrder.BIG_ENDIAN) & 0xFFFF;
    }

    /**
     * Read an int (4 bytes, big-endian) at the specified offset.
     */
    protected int getInt(int fieldOffset) {
        return buffer.getInt(offset + fieldOffset, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Read an unsigned int at the specified offset.
     */
    protected long getUnsignedInt(int fieldOffset) {
        return buffer.getInt(offset + fieldOffset, ByteOrder.BIG_ENDIAN) & 0xFFFFFFFFL;
    }

    /**
     * Read a long (8 bytes, big-endian) at the specified offset.
     */
    protected long getLong(int fieldOffset) {
        return buffer.getLong(offset + fieldOffset, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Read an alpha field (fixed-width, trimmed) at the specified offset.
     *
     * @param fieldOffset offset from message start
     * @param fieldLength the fixed field length
     * @return the trimmed string value
     */
    protected String getAlpha(int fieldOffset, int fieldLength) {
        byte[] bytes = new byte[fieldLength];
        buffer.getBytes(offset + fieldOffset, bytes, 0, fieldLength);

        // Find the end (trim trailing spaces)
        int end = fieldLength;
        while (end > 0 && bytes[end - 1] == ' ') {
            end--;
        }

        return new String(bytes, 0, end, StandardCharsets.US_ASCII);
    }

    /**
     * Read a character field at the specified offset.
     */
    protected char getChar(int fieldOffset) {
        return (char) (buffer.getByte(offset + fieldOffset) & 0xFF);
    }

    /**
     * Read a price field (4 bytes, signed integer representing price * 10000).
     * Returns price in micros (1/10000 of a dollar).
     */
    protected long getPrice(int fieldOffset) {
        return buffer.getInt(offset + fieldOffset, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Copy raw bytes from the message.
     *
     * @param fieldOffset offset from message start
     * @param dest destination array
     * @param destOffset destination offset
     * @param length number of bytes to copy
     */
    protected void getBytes(int fieldOffset, byte[] dest, int destOffset, int length) {
        buffer.getBytes(offset + fieldOffset, dest, destOffset, length);
    }

    // =====================================================
    // Field writing utilities (for sending messages)
    // =====================================================

    /**
     * Write a single byte at the specified offset from message start.
     */
    protected void putByte(int fieldOffset, byte value) {
        buffer.putByte(offset + fieldOffset, value);
    }

    /**
     * Write a short (2 bytes, big-endian) at the specified offset.
     */
    protected void putShort(int fieldOffset, short value) {
        buffer.putShort(offset + fieldOffset, value, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Write an int (4 bytes, big-endian) at the specified offset.
     */
    protected void putInt(int fieldOffset, int value) {
        buffer.putInt(offset + fieldOffset, value, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Write a long (8 bytes, big-endian) at the specified offset.
     */
    protected void putLong(int fieldOffset, long value) {
        buffer.putLong(offset + fieldOffset, value, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Write an alpha field (fixed-width, space-padded) at the specified offset.
     *
     * @param fieldOffset offset from message start
     * @param value the string value
     * @param fieldLength the fixed field length
     */
    protected void putAlpha(int fieldOffset, String value, int fieldLength) {
        byte[] bytes = value != null ? value.getBytes(StandardCharsets.US_ASCII) : new byte[0];
        int copyLen = Math.min(bytes.length, fieldLength);

        // Copy the value
        buffer.putBytes(offset + fieldOffset, bytes, 0, copyLen);

        // Pad with spaces
        for (int i = copyLen; i < fieldLength; i++) {
            buffer.putByte(offset + fieldOffset + i, (byte) ' ');
        }
    }

    /**
     * Write a character field at the specified offset.
     */
    protected void putChar(int fieldOffset, char value) {
        buffer.putByte(offset + fieldOffset, (byte) value);
    }

    /**
     * Write a price field (4 bytes, signed integer representing price * 10000).
     */
    protected void putPrice(int fieldOffset, long priceInMicros) {
        buffer.putInt(offset + fieldOffset, (int) priceInMicros, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Copy raw bytes to the message.
     *
     * @param fieldOffset offset from message start
     * @param src source array
     * @param srcOffset source offset
     * @param length number of bytes to copy
     */
    protected void putBytes(int fieldOffset, byte[] src, int srcOffset, int length) {
        buffer.putBytes(offset + fieldOffset, src, srcOffset, length);
    }

    // =====================================================
    // Legacy interface compatibility
    // =====================================================

    /**
     * Encode the message to a buffer.
     * @deprecated Use wrap() and field setters instead
     */
    @Deprecated
    public int encode(MutableDirectBuffer buffer, int offset) {
        throw new UnsupportedOperationException("Use wrap() and field setters instead");
    }

    /**
     * Decode the message from a buffer.
     * @deprecated Use wrapForReading() instead
     */
    @Deprecated
    public int decode(DirectBuffer buffer, int offset, int length) {
        wrapForReading(buffer, offset, length);
        return getMessageLength();
    }
}
