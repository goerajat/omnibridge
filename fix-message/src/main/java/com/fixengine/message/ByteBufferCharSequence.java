package com.fixengine.message;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A CharSequence implementation that wraps a portion of a ByteBuffer.
 *
 * <p>This class provides zero-allocation access to string data stored in ByteBuffers,
 * typically used for FIX message field values. The bytes are assumed to be ASCII encoded.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>No memory allocation for accessing string values</li>
 *   <li>Poolable - can be reset and reused</li>
 *   <li>Proper equals/hashCode for use in collections and comparisons</li>
 *   <li>Compatible with String.equals() and other CharSequence comparisons</li>
 *   <li>Direct ByteBuffer access without intermediate byte array copies</li>
 * </ul>
 *
 * <p>Thread Safety: This class is NOT thread-safe. The backing buffer should not be
 * modified while this CharSequence is in use.</p>
 */
public final class ByteBufferCharSequence implements CharSequence, Comparable<CharSequence> {

    private ByteBuffer buffer;
    private int offset;
    private int length;

    // Cached hash code (0 means not computed)
    private int hash;

    /**
     * Create an empty ByteBufferCharSequence.
     * Must call {@link #wrap(ByteBuffer, int, int)} before use.
     */
    public ByteBufferCharSequence() {
        this.buffer = null;
        this.offset = 0;
        this.length = 0;
    }

    /**
     * Create a ByteBufferCharSequence wrapping a portion of a ByteBuffer.
     *
     * @param buffer the backing ByteBuffer
     * @param offset the start position in the buffer (absolute position)
     * @param length the number of bytes to include
     */
    public ByteBufferCharSequence(ByteBuffer buffer, int offset, int length) {
        wrap(buffer, offset, length);
    }

    /**
     * Wrap a portion of a ByteBuffer.
     *
     * <p>This method allows reuse of the CharSequence object.</p>
     *
     * @param buffer the backing ByteBuffer
     * @param offset the start position in the buffer (absolute position)
     * @param length the number of bytes to include
     */
    public void wrap(ByteBuffer buffer, int offset, int length) {
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
        this.hash = 0; // Reset cached hash
    }

    /**
     * Reset this CharSequence to empty state.
     * The object can be returned to a pool after reset.
     */
    public void reset() {
        this.buffer = null;
        this.offset = 0;
        this.length = 0;
        this.hash = 0;
    }

    /**
     * Check if this CharSequence has been initialized with data.
     *
     * @return true if wrapping valid data
     */
    public boolean isValid() {
        return buffer != null;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Length: " + length);
        }
        return (char) (buffer.get(offset + index) & 0xFF);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException("start=" + start + ", end=" + end + ", length=" + length);
        }
        return new ByteBufferCharSequence(buffer, offset + start, end - start);
    }

    /**
     * Copy the contents to a String.
     * This allocates a new String object.
     *
     * @return a new String with the same content
     */
    @Override
    public String toString() {
        if (buffer == null || length == 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        // Use absolute get to avoid modifying buffer position
        for (int i = 0; i < length; i++) {
            bytes[i] = buffer.get(offset + i);
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    /**
     * Compare this CharSequence with another for equality.
     *
     * <p>This implementation properly compares with String and other CharSequence
     * implementations, making it safe to use in collections alongside Strings.</p>
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof CharSequence) {
            return contentEquals((CharSequence) obj);
        }
        return false;
    }

    /**
     * Check if this CharSequence has the same content as another.
     *
     * @param cs the CharSequence to compare with
     * @return true if the content is identical
     */
    public boolean contentEquals(CharSequence cs) {
        if (cs == null) {
            return false;
        }
        if (cs.length() != length) {
            return false;
        }
        if (cs == this) {
            return true;
        }

        // Optimized path for ByteBufferCharSequence
        if (cs instanceof ByteBufferCharSequence) {
            ByteBufferCharSequence other = (ByteBufferCharSequence) cs;
            if (other.buffer == this.buffer && other.offset == this.offset && other.length == this.length) {
                return true;
            }
            // Compare byte by byte
            for (int i = 0; i < length; i++) {
                if (buffer.get(offset + i) != other.buffer.get(other.offset + i)) {
                    return false;
                }
            }
            return true;
        }

        // General CharSequence comparison
        for (int i = 0; i < length; i++) {
            if (charAt(i) != cs.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a hash code compatible with String's hashCode algorithm.
     *
     * <p>This ensures that a ByteBufferCharSequence with content "ABC" has the
     * same hash code as the String "ABC", enabling proper HashMap/HashSet usage.</p>
     */
    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0 && length > 0) {
            // Use same algorithm as String.hashCode()
            for (int i = 0; i < length; i++) {
                h = 31 * h + (buffer.get(offset + i) & 0xFF);
            }
            hash = h;
        }
        return h;
    }

    @Override
    public int compareTo(CharSequence other) {
        int len1 = this.length;
        int len2 = other.length();
        int minLen = Math.min(len1, len2);

        for (int i = 0; i < minLen; i++) {
            char c1 = charAt(i);
            char c2 = other.charAt(i);
            if (c1 != c2) {
                return c1 - c2;
            }
        }
        return len1 - len2;
    }

    /**
     * Compare ignoring case.
     *
     * @param other the CharSequence to compare with
     * @return true if equal ignoring case
     */
    public boolean equalsIgnoreCase(CharSequence other) {
        if (other == null || other.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c1 = Character.toUpperCase(charAt(i));
            char c2 = Character.toUpperCase(other.charAt(i));
            if (c1 != c2) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if this CharSequence starts with the given prefix.
     *
     * @param prefix the prefix to check
     * @return true if this starts with the prefix
     */
    public boolean startsWith(CharSequence prefix) {
        if (prefix.length() > length) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parse this CharSequence as an integer.
     *
     * @return the parsed integer value
     * @throws NumberFormatException if not a valid integer
     */
    public int parseAsInt() {
        if (length == 0) {
            throw new NumberFormatException("Empty string");
        }

        int result = 0;
        boolean negative = false;
        int i = 0;

        byte firstByte = buffer.get(offset);
        if (firstByte == '-') {
            negative = true;
            i = 1;
        } else if (firstByte == '+') {
            i = 1;
        }

        for (; i < length; i++) {
            byte b = buffer.get(offset + i);
            if (b >= '0' && b <= '9') {
                result = result * 10 + (b - '0');
            } else {
                throw new NumberFormatException("Invalid character: " + (char) b);
            }
        }

        return negative ? -result : result;
    }

    /**
     * Parse this CharSequence as a long.
     *
     * @return the parsed long value
     * @throws NumberFormatException if not a valid long
     */
    public long parseAsLong() {
        if (length == 0) {
            throw new NumberFormatException("Empty string");
        }

        long result = 0;
        boolean negative = false;
        int i = 0;

        byte firstByte = buffer.get(offset);
        if (firstByte == '-') {
            negative = true;
            i = 1;
        } else if (firstByte == '+') {
            i = 1;
        }

        for (; i < length; i++) {
            byte b = buffer.get(offset + i);
            if (b >= '0' && b <= '9') {
                result = result * 10 + (b - '0');
            } else {
                throw new NumberFormatException("Invalid character: " + (char) b);
            }
        }

        return negative ? -result : result;
    }

    /**
     * Parse this CharSequence as a double.
     *
     * @return the parsed double value
     * @throws NumberFormatException if not a valid double
     */
    public double parseAsDouble() {
        // For double parsing, we need to use standard parsing
        // This does allocate, but double parsing is complex
        return Double.parseDouble(toString());
    }

    /**
     * Get the first character, or '\0' if empty.
     *
     * @return the first character
     */
    public char firstChar() {
        return length > 0 ? charAt(0) : '\0';
    }

    /**
     * Check if this CharSequence is empty.
     *
     * @return true if length is 0
     */
    public boolean isEmpty() {
        return length == 0;
    }

    /**
     * Get the backing buffer.
     *
     * @return the backing ByteBuffer
     */
    public ByteBuffer getBuffer() {
        return buffer;
    }

    /**
     * Get the offset in the backing buffer.
     *
     * @return the offset
     */
    public int getOffset() {
        return offset;
    }
}
