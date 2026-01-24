package com.omnibridge.network;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A buffer implementation that combines Java NIO ByteBuffer with Agrona's UnsafeBuffer.
 *
 * <p>This class provides:</p>
 * <ul>
 *   <li>ByteBuffer compatibility for Java NIO operations (SocketChannel.read/write)</li>
 *   <li>DirectBuffer interface for high-performance memory access</li>
 *   <li>Synchronized position/limit state between both views</li>
 * </ul>
 *
 * <p>The underlying memory is allocated once as a direct ByteBuffer, and the UnsafeBuffer
 * wraps the same memory region. This provides zero-copy access through either interface.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * DirectByteBuffer buffer = new DirectByteBuffer(64 * 1024);
 *
 * // Use ByteBuffer for socket I/O
 * socketChannel.read(buffer.byteBuffer());
 *
 * // Use DirectBuffer for efficient parsing
 * int value = buffer.getInt(offset);
 * buffer.getBytes(offset, destArray, 0, length);
 *
 * // Position/limit are synchronized
 * buffer.flip();  // Affects both views
 * }</pre>
 *
 * <p>Thread Safety: This class is NOT thread-safe. All operations should be performed
 * from a single thread.</p>
 */
public class DirectByteBuffer implements MutableDirectBuffer {

    private final ByteBuffer byteBuffer;
    private final UnsafeBuffer unsafeBuffer;
    private final int capacity;

    /**
     * Allocate a new direct buffer with the specified capacity.
     *
     * @param capacity the buffer capacity in bytes
     */
    public DirectByteBuffer(int capacity) {
        this.capacity = capacity;
        this.byteBuffer = ByteBuffer.allocateDirect(capacity);
        this.unsafeBuffer = new UnsafeBuffer(byteBuffer);
    }

    /**
     * Wrap an existing ByteBuffer.
     *
     * <p>The ByteBuffer should be a direct buffer for best performance.
     * The UnsafeBuffer will wrap the same memory.</p>
     *
     * @param byteBuffer the ByteBuffer to wrap
     */
    public DirectByteBuffer(ByteBuffer byteBuffer) {
        this.byteBuffer = byteBuffer;
        this.capacity = byteBuffer.capacity();
        this.unsafeBuffer = new UnsafeBuffer(byteBuffer);
    }

    // ==================== ByteBuffer Access ====================

    /**
     * Get the underlying ByteBuffer for NIO operations.
     *
     * <p>Use this for SocketChannel.read() and SocketChannel.write() operations.
     * Position and limit changes on the returned ByteBuffer are visible to this buffer.</p>
     *
     * @return the underlying ByteBuffer
     */
    public ByteBuffer byteBuffer() {
        return byteBuffer;
    }

    /**
     * Get the underlying UnsafeBuffer for direct memory access.
     *
     * @return the underlying UnsafeBuffer
     */
    public UnsafeBuffer unsafeBuffer() {
        return unsafeBuffer;
    }

    // ==================== Position/Limit Management ====================

    /**
     * Get the current position.
     *
     * @return the current position
     */
    public int position() {
        return byteBuffer.position();
    }

    /**
     * Set the position.
     *
     * @param newPosition the new position
     * @return this buffer
     */
    public DirectByteBuffer position(int newPosition) {
        byteBuffer.position(newPosition);
        return this;
    }

    /**
     * Get the current limit.
     *
     * @return the current limit
     */
    public int limit() {
        return byteBuffer.limit();
    }

    /**
     * Set the limit.
     *
     * @param newLimit the new limit
     * @return this buffer
     */
    public DirectByteBuffer limit(int newLimit) {
        byteBuffer.limit(newLimit);
        return this;
    }

    /**
     * Get the remaining bytes between position and limit.
     *
     * @return the number of remaining bytes
     */
    public int remaining() {
        return byteBuffer.remaining();
    }

    /**
     * Check if there are remaining bytes.
     *
     * @return true if position < limit
     */
    public boolean hasRemaining() {
        return byteBuffer.hasRemaining();
    }

    /**
     * Flip the buffer (limit = position, position = 0).
     *
     * @return this buffer
     */
    public DirectByteBuffer flip() {
        byteBuffer.flip();
        return this;
    }

    /**
     * Clear the buffer (position = 0, limit = capacity).
     *
     * @return this buffer
     */
    public DirectByteBuffer clear() {
        byteBuffer.clear();
        return this;
    }

    /**
     * Compact the buffer (copy remaining data to start).
     *
     * @return this buffer
     */
    public DirectByteBuffer compact() {
        byteBuffer.compact();
        return this;
    }

    /**
     * Rewind the buffer (position = 0, limit unchanged).
     *
     * @return this buffer
     */
    public DirectByteBuffer rewind() {
        byteBuffer.rewind();
        return this;
    }

    /**
     * Mark the current position.
     *
     * @return this buffer
     */
    public DirectByteBuffer mark() {
        byteBuffer.mark();
        return this;
    }

    /**
     * Reset position to the previously marked position.
     *
     * @return this buffer
     */
    public DirectByteBuffer resetToMark() {
        byteBuffer.reset();
        return this;
    }

    // ==================== Relative Read Operations (use position) ====================

    /**
     * Read a byte at the current position and advance position.
     *
     * @return the byte value
     */
    public byte get() {
        return byteBuffer.get();
    }

    /**
     * Read bytes into the destination array and advance position.
     *
     * @param dst the destination array
     * @return this buffer
     */
    public DirectByteBuffer get(byte[] dst) {
        byteBuffer.get(dst);
        return this;
    }

    /**
     * Read bytes into the destination array and advance position.
     *
     * @param dst the destination array
     * @param offset the offset in the destination array
     * @param length the number of bytes to read
     * @return this buffer
     */
    public DirectByteBuffer get(byte[] dst, int offset, int length) {
        byteBuffer.get(dst, offset, length);
        return this;
    }

    /**
     * Read a short at the current position and advance position.
     *
     * @return the short value
     */
    public short getShort() {
        return byteBuffer.getShort();
    }

    /**
     * Read an int at the current position and advance position.
     *
     * @return the int value
     */
    public int getInt() {
        return byteBuffer.getInt();
    }

    /**
     * Read a long at the current position and advance position.
     *
     * @return the long value
     */
    public long getLong() {
        return byteBuffer.getLong();
    }

    /**
     * Read a float at the current position and advance position.
     *
     * @return the float value
     */
    public float getFloat() {
        return byteBuffer.getFloat();
    }

    /**
     * Read a double at the current position and advance position.
     *
     * @return the double value
     */
    public double getDouble() {
        return byteBuffer.getDouble();
    }

    // ==================== Relative Write Operations (use position) ====================

    /**
     * Write a byte at the current position and advance position.
     *
     * @param value the byte value
     * @return this buffer
     */
    public DirectByteBuffer put(byte value) {
        byteBuffer.put(value);
        return this;
    }

    /**
     * Write bytes from the source array and advance position.
     *
     * @param src the source array
     * @return this buffer
     */
    public DirectByteBuffer put(byte[] src) {
        byteBuffer.put(src);
        return this;
    }

    /**
     * Write bytes from the source array and advance position.
     *
     * @param src the source array
     * @param offset the offset in the source array
     * @param length the number of bytes to write
     * @return this buffer
     */
    public DirectByteBuffer put(byte[] src, int offset, int length) {
        byteBuffer.put(src, offset, length);
        return this;
    }

    /**
     * Write bytes from another ByteBuffer and advance position.
     *
     * @param src the source ByteBuffer
     * @return this buffer
     */
    public DirectByteBuffer put(ByteBuffer src) {
        byteBuffer.put(src);
        return this;
    }

    /**
     * Write a short at the current position and advance position.
     *
     * @param value the short value
     * @return this buffer
     */
    public DirectByteBuffer putShort(short value) {
        byteBuffer.putShort(value);
        return this;
    }

    /**
     * Write an int at the current position and advance position.
     *
     * @param value the int value
     * @return this buffer
     */
    public DirectByteBuffer putInt(int value) {
        byteBuffer.putInt(value);
        return this;
    }

    /**
     * Write a long at the current position and advance position.
     *
     * @param value the long value
     * @return this buffer
     */
    public DirectByteBuffer putLong(long value) {
        byteBuffer.putLong(value);
        return this;
    }

    /**
     * Write a float at the current position and advance position.
     *
     * @param value the float value
     * @return this buffer
     */
    public DirectByteBuffer putFloat(float value) {
        byteBuffer.putFloat(value);
        return this;
    }

    /**
     * Write a double at the current position and advance position.
     *
     * @param value the double value
     * @return this buffer
     */
    public DirectByteBuffer putDouble(double value) {
        byteBuffer.putDouble(value);
        return this;
    }

    // ==================== MutableDirectBuffer Implementation ====================
    // These are absolute (indexed) operations that don't affect position

    @Override
    public void wrap(byte[] buffer) {
        unsafeBuffer.wrap(buffer);
    }

    @Override
    public void wrap(byte[] buffer, int offset, int length) {
        unsafeBuffer.wrap(buffer, offset, length);
    }

    @Override
    public void wrap(ByteBuffer buffer) {
        unsafeBuffer.wrap(buffer);
    }

    @Override
    public void wrap(ByteBuffer buffer, int offset, int length) {
        unsafeBuffer.wrap(buffer, offset, length);
    }

    @Override
    public void wrap(DirectBuffer buffer) {
        unsafeBuffer.wrap(buffer);
    }

    @Override
    public void wrap(DirectBuffer buffer, int offset, int length) {
        unsafeBuffer.wrap(buffer, offset, length);
    }

    @Override
    public void wrap(long address, int length) {
        unsafeBuffer.wrap(address, length);
    }

    @Override
    public long addressOffset() {
        return unsafeBuffer.addressOffset();
    }

    @Override
    public byte[] byteArray() {
        return unsafeBuffer.byteArray();
    }

    // Note: byteBuffer() is already defined above as a convenience method
    // and satisfies the DirectBuffer interface requirement

    @Override
    public void setMemory(int index, int length, byte value) {
        unsafeBuffer.setMemory(index, length, value);
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public boolean isExpandable() {
        return false;
    }

    @Override
    public void checkLimit(int limit) {
        unsafeBuffer.checkLimit(limit);
    }

    @Override
    public void boundsCheck(int index, int length) {
        unsafeBuffer.boundsCheck(index, length);
    }

    // ==================== Absolute Read Operations (DirectBuffer) ====================

    @Override
    public long getLong(int index, ByteOrder byteOrder) {
        return unsafeBuffer.getLong(index, byteOrder);
    }

    @Override
    public long getLong(int index) {
        return unsafeBuffer.getLong(index);
    }

    @Override
    public int getInt(int index, ByteOrder byteOrder) {
        return unsafeBuffer.getInt(index, byteOrder);
    }

    @Override
    public int getInt(int index) {
        return unsafeBuffer.getInt(index);
    }

    @Override
    public double getDouble(int index, ByteOrder byteOrder) {
        return unsafeBuffer.getDouble(index, byteOrder);
    }

    @Override
    public double getDouble(int index) {
        return unsafeBuffer.getDouble(index);
    }

    @Override
    public float getFloat(int index, ByteOrder byteOrder) {
        return unsafeBuffer.getFloat(index, byteOrder);
    }

    @Override
    public float getFloat(int index) {
        return unsafeBuffer.getFloat(index);
    }

    @Override
    public short getShort(int index, ByteOrder byteOrder) {
        return unsafeBuffer.getShort(index, byteOrder);
    }

    @Override
    public short getShort(int index) {
        return unsafeBuffer.getShort(index);
    }

    @Override
    public char getChar(int index, ByteOrder byteOrder) {
        return unsafeBuffer.getChar(index, byteOrder);
    }

    @Override
    public char getChar(int index) {
        return unsafeBuffer.getChar(index);
    }

    @Override
    public byte getByte(int index) {
        return unsafeBuffer.getByte(index);
    }

    @Override
    public void getBytes(int index, byte[] dst) {
        unsafeBuffer.getBytes(index, dst);
    }

    @Override
    public void getBytes(int index, byte[] dst, int offset, int length) {
        unsafeBuffer.getBytes(index, dst, offset, length);
    }

    @Override
    public void getBytes(int index, MutableDirectBuffer dstBuffer, int dstIndex, int length) {
        unsafeBuffer.getBytes(index, dstBuffer, dstIndex, length);
    }

    @Override
    public void getBytes(int index, ByteBuffer dstBuffer, int length) {
        unsafeBuffer.getBytes(index, dstBuffer, length);
    }

    @Override
    public void getBytes(int index, ByteBuffer dstBuffer, int dstOffset, int length) {
        unsafeBuffer.getBytes(index, dstBuffer, dstOffset, length);
    }

    @Override
    public String getStringAscii(int index) {
        return unsafeBuffer.getStringAscii(index);
    }

    @Override
    public int getStringAscii(int index, Appendable appendable) {
        return unsafeBuffer.getStringAscii(index, appendable);
    }

    @Override
    public String getStringAscii(int index, ByteOrder byteOrder) {
        return unsafeBuffer.getStringAscii(index, byteOrder);
    }

    @Override
    public int getStringAscii(int index, Appendable appendable, ByteOrder byteOrder) {
        return unsafeBuffer.getStringAscii(index, appendable, byteOrder);
    }

    @Override
    public String getStringAscii(int index, int length) {
        return unsafeBuffer.getStringAscii(index, length);
    }

    @Override
    public int getStringAscii(int index, int length, Appendable appendable) {
        return unsafeBuffer.getStringAscii(index, length, appendable);
    }

    @Override
    public String getStringWithoutLengthAscii(int index, int length) {
        return unsafeBuffer.getStringWithoutLengthAscii(index, length);
    }

    @Override
    public int getStringWithoutLengthAscii(int index, int length, Appendable appendable) {
        return unsafeBuffer.getStringWithoutLengthAscii(index, length, appendable);
    }

    @Override
    public String getStringUtf8(int index) {
        return unsafeBuffer.getStringUtf8(index);
    }

    @Override
    public String getStringUtf8(int index, ByteOrder byteOrder) {
        return unsafeBuffer.getStringUtf8(index, byteOrder);
    }

    @Override
    public String getStringUtf8(int index, int length) {
        return unsafeBuffer.getStringUtf8(index, length);
    }

    @Override
    public String getStringWithoutLengthUtf8(int index, int length) {
        return unsafeBuffer.getStringWithoutLengthUtf8(index, length);
    }

    @Override
    public int parseNaturalIntAscii(int index, int length) {
        return unsafeBuffer.parseNaturalIntAscii(index, length);
    }

    @Override
    public long parseNaturalLongAscii(int index, int length) {
        return unsafeBuffer.parseNaturalLongAscii(index, length);
    }

    @Override
    public int parseIntAscii(int index, int length) {
        return unsafeBuffer.parseIntAscii(index, length);
    }

    @Override
    public long parseLongAscii(int index, int length) {
        return unsafeBuffer.parseLongAscii(index, length);
    }

    @Override
    public int wrapAdjustment() {
        return unsafeBuffer.wrapAdjustment();
    }

    @Override
    public boolean equals(Object obj) {
        return unsafeBuffer.equals(obj);
    }

    @Override
    public int hashCode() {
        return unsafeBuffer.hashCode();
    }

    @Override
    public int compareTo(DirectBuffer that) {
        return unsafeBuffer.compareTo(that);
    }

    // ==================== Absolute Write Operations (MutableDirectBuffer) ====================

    @Override
    public void putLong(int index, long value, ByteOrder byteOrder) {
        unsafeBuffer.putLong(index, value, byteOrder);
    }

    @Override
    public void putLong(int index, long value) {
        unsafeBuffer.putLong(index, value);
    }

    @Override
    public void putInt(int index, int value, ByteOrder byteOrder) {
        unsafeBuffer.putInt(index, value, byteOrder);
    }

    @Override
    public void putInt(int index, int value) {
        unsafeBuffer.putInt(index, value);
    }

    @Override
    public void putDouble(int index, double value, ByteOrder byteOrder) {
        unsafeBuffer.putDouble(index, value, byteOrder);
    }

    @Override
    public void putDouble(int index, double value) {
        unsafeBuffer.putDouble(index, value);
    }

    @Override
    public void putFloat(int index, float value, ByteOrder byteOrder) {
        unsafeBuffer.putFloat(index, value, byteOrder);
    }

    @Override
    public void putFloat(int index, float value) {
        unsafeBuffer.putFloat(index, value);
    }

    @Override
    public void putShort(int index, short value, ByteOrder byteOrder) {
        unsafeBuffer.putShort(index, value, byteOrder);
    }

    @Override
    public void putShort(int index, short value) {
        unsafeBuffer.putShort(index, value);
    }

    @Override
    public void putChar(int index, char value, ByteOrder byteOrder) {
        unsafeBuffer.putChar(index, value, byteOrder);
    }

    @Override
    public void putChar(int index, char value) {
        unsafeBuffer.putChar(index, value);
    }

    @Override
    public void putByte(int index, byte value) {
        unsafeBuffer.putByte(index, value);
    }

    @Override
    public void putBytes(int index, byte[] src) {
        unsafeBuffer.putBytes(index, src);
    }

    @Override
    public void putBytes(int index, byte[] src, int offset, int length) {
        unsafeBuffer.putBytes(index, src, offset, length);
    }

    @Override
    public void putBytes(int index, ByteBuffer srcBuffer, int length) {
        unsafeBuffer.putBytes(index, srcBuffer, length);
    }

    @Override
    public void putBytes(int index, ByteBuffer srcBuffer, int srcIndex, int length) {
        unsafeBuffer.putBytes(index, srcBuffer, srcIndex, length);
    }

    @Override
    public void putBytes(int index, DirectBuffer srcBuffer, int srcIndex, int length) {
        unsafeBuffer.putBytes(index, srcBuffer, srcIndex, length);
    }

    @Override
    public int putStringAscii(int index, String value) {
        return unsafeBuffer.putStringAscii(index, value);
    }

    @Override
    public int putStringAscii(int index, CharSequence value) {
        return unsafeBuffer.putStringAscii(index, value);
    }

    @Override
    public int putStringAscii(int index, String value, ByteOrder byteOrder) {
        return unsafeBuffer.putStringAscii(index, value, byteOrder);
    }

    @Override
    public int putStringAscii(int index, CharSequence value, ByteOrder byteOrder) {
        return unsafeBuffer.putStringAscii(index, value, byteOrder);
    }

    @Override
    public int putStringWithoutLengthAscii(int index, String value) {
        return unsafeBuffer.putStringWithoutLengthAscii(index, value);
    }

    @Override
    public int putStringWithoutLengthAscii(int index, CharSequence value) {
        return unsafeBuffer.putStringWithoutLengthAscii(index, value);
    }

    @Override
    public int putStringWithoutLengthAscii(int index, String value, int valueOffset, int length) {
        return unsafeBuffer.putStringWithoutLengthAscii(index, value, valueOffset, length);
    }

    @Override
    public int putStringWithoutLengthAscii(int index, CharSequence value, int valueOffset, int length) {
        return unsafeBuffer.putStringWithoutLengthAscii(index, value, valueOffset, length);
    }

    @Override
    public int putStringUtf8(int index, String value) {
        return unsafeBuffer.putStringUtf8(index, value);
    }

    @Override
    public int putStringUtf8(int index, String value, ByteOrder byteOrder) {
        return unsafeBuffer.putStringUtf8(index, value, byteOrder);
    }

    @Override
    public int putStringUtf8(int index, String value, int maxEncodedLength) {
        return unsafeBuffer.putStringUtf8(index, value, maxEncodedLength);
    }

    @Override
    public int putStringUtf8(int index, String value, ByteOrder byteOrder, int maxEncodedLength) {
        return unsafeBuffer.putStringUtf8(index, value, byteOrder, maxEncodedLength);
    }

    @Override
    public int putStringWithoutLengthUtf8(int index, String value) {
        return unsafeBuffer.putStringWithoutLengthUtf8(index, value);
    }

    @Override
    public int putNaturalIntAscii(int index, int value) {
        return unsafeBuffer.putNaturalIntAscii(index, value);
    }

    @Override
    public void putNaturalPaddedIntAscii(int index, int length, int value) {
        unsafeBuffer.putNaturalPaddedIntAscii(index, length, value);
    }

    @Override
    public int putNaturalIntAsciiFromEnd(int value, int endExclusive) {
        return unsafeBuffer.putNaturalIntAsciiFromEnd(value, endExclusive);
    }

    @Override
    public int putNaturalLongAscii(int index, long value) {
        return unsafeBuffer.putNaturalLongAscii(index, value);
    }

    @Override
    public int putIntAscii(int index, int value) {
        return unsafeBuffer.putIntAscii(index, value);
    }

    @Override
    public int putLongAscii(int index, long value) {
        return unsafeBuffer.putLongAscii(index, value);
    }

    @Override
    public String toString() {
        return "DirectByteBuffer{" +
                "capacity=" + capacity +
                ", position=" + byteBuffer.position() +
                ", limit=" + byteBuffer.limit() +
                '}';
    }
}
