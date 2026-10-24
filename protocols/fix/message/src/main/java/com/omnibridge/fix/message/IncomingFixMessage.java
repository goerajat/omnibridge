package com.omnibridge.fix.message;

import org.agrona.DirectBuffer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Read-only poolable FIX message for incoming message processing.
 *
 * <p>This class is designed for high-performance parsing and access of incoming FIX messages.
 * Key features:</p>
 * <ul>
 *   <li>Read-only access - only get* methods, no setters</li>
 *   <li>Poolable - can be acquired from and released back to a pool</li>
 *   <li>Zero-allocation field access using {@link ByteBufferCharSequence}</li>
 *   <li>Direct ByteBuffer access without intermediate byte array copies</li>
 *   <li>Internal pool of CharSequence wrappers that are recycled</li>
 * </ul>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * // Acquire from pool (done by FixReader)
 * IncomingFixMessage msg = pool.acquire();
 * msg.wrap(byteBuffer, length);
 *
 * // Access fields
 * CharSequence clOrdId = msg.getCharSequence(11);
 * int qty = msg.getInt(38);
 *
 * // Release back to pool (done by FixSession after callback)
 * msg.release();
 * }</pre>
 *
 * <p>Thread Safety: This class is NOT thread-safe. A message should only be
 * accessed by one thread at a time.</p>
 */
public final class IncomingFixMessage {

    public static final byte SOH = 0x01;
    public static final byte EQUALS = '=';

    private static final int MAX_FIELDS = 256;
    private static final int CHAR_SEQ_POOL_SIZE = 32;
    private static final int MISSING_VALUE = -1;

    /** Interned one- and two-character MsgTypes (printable ASCII), so getMsgType() does not allocate. */
    private static final String[] ONE_CHAR_MSG_TYPES = new String[128];
    private static final String[][] TWO_CHAR_MSG_TYPES = new String[128][];

    static {
        for (char c = 0x21; c < 0x7F; c++) {
            ONE_CHAR_MSG_TYPES[c] = String.valueOf(c).intern();
        }
    }

    /** Powers of ten that are exact doubles: a decimal with up to 15 digits divides exactly once. */
    private static final double[] POWERS_OF_TEN = {
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10,
            1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22
    };
    private static final long MAX_EXACT_MANTISSA = 1L << 53;

    // Message buffer - direct reference to the provided DirectBuffer
    private DirectBuffer buffer;
    private int startOffset;  // Starting offset in the buffer
    private int length;

    // Field index for fast lookup: tag -> index in fieldPositions
    private final Int2IntHashMap fieldIndex;

    // Parsed field positions: [fieldNum][0]=valueStart, [fieldNum][1]=valueEnd
    private final int[][] fieldPositions;
    private int fieldCount;

    // Cached header values (avoid repeated parsing)
    private final ByteBufferCharSequence cachedMsgType;
    private int cachedMsgSeqNum;
    private final ByteBufferCharSequence cachedSenderCompId;
    private final ByteBufferCharSequence cachedTargetCompId;

    // FIX 5.0+ cached values
    private final ByteBufferCharSequence cachedApplVerID;
    private final ByteBufferCharSequence cachedDefaultApplVerID;

    // Pool of CharSequence wrappers for returning field values
    private final ByteBufferCharSequence[] charSeqPool;
    private int charSeqPoolIndex;

    // Pool management
    private volatile boolean inUse;
    private IncomingMessagePool ownerPool;

    /**
     * Create a new IncomingFixMessage with default settings.
     */
    public IncomingFixMessage() {
        this.buffer = null;
        this.startOffset = 0;
        this.length = 0;
        // Initial capacity for ~64 fields with 0.65 load factor
        this.fieldIndex = new Int2IntHashMap(128, 0.65f, MISSING_VALUE);
        this.fieldPositions = new int[MAX_FIELDS][2];
        this.fieldCount = 0;

        // Initialize cached header values
        this.cachedMsgType = new ByteBufferCharSequence();
        this.cachedSenderCompId = new ByteBufferCharSequence();
        this.cachedTargetCompId = new ByteBufferCharSequence();

        // Initialize FIX 5.0+ cached values
        this.cachedApplVerID = new ByteBufferCharSequence();
        this.cachedDefaultApplVerID = new ByteBufferCharSequence();

        // Initialize CharSequence pool
        this.charSeqPool = new ByteBufferCharSequence[CHAR_SEQ_POOL_SIZE];
        for (int i = 0; i < CHAR_SEQ_POOL_SIZE; i++) {
            charSeqPool[i] = new ByteBufferCharSequence();
        }
        this.charSeqPoolIndex = 0;
    }

    /**
     * Create a new IncomingFixMessage.
     *
     * @param maxTagNumber ignored, kept for API compatibility
     * @deprecated Use the no-arg constructor instead
     */
    @Deprecated
    public IncomingFixMessage(int maxTagNumber) {
        this();
    }

    /**
     * Wrap message bytes for parsing.
     *
     * <p>The bytes are wrapped in an UnsafeBuffer for internal use.</p>
     *
     * @param data the message bytes
     * @param offset the offset in the array
     * @param len the length of the message
     */
    public void wrap(byte[] data, int offset, int len) {
        reset();
        this.buffer = new UnsafeBuffer(data, offset, len);
        this.startOffset = 0;  // UnsafeBuffer wraps from offset, so internal offset is 0
        this.length = len;
        parseFields();
    }

    /**
     * Wrap message bytes from a DirectBuffer with explicit offset and length.
     *
     * <p>This is the preferred method for zero-allocation parsing with Agrona buffers.</p>
     *
     * @param data the DirectBuffer containing the message
     * @param offset the offset in the buffer where the message starts
     * @param len the length of the message in bytes
     */
    public void wrap(DirectBuffer data, int offset, int len) {
        reset();
        this.buffer = data;
        this.startOffset = offset;
        this.length = len;
        parseFields();
    }

    /**
     * Wrap message bytes from a ByteBuffer.
     *
     * <p>The ByteBuffer is wrapped in an UnsafeBuffer. The buffer's position
     * and limit define the message boundaries.</p>
     *
     * @param data the ByteBuffer containing the message (position to limit)
     * @deprecated Use {@link #wrap(DirectBuffer, int, int)} instead
     */
    @Deprecated
    public void wrap(ByteBuffer data) {
        reset();
        this.buffer = new UnsafeBuffer(data, data.position(), data.remaining());
        this.startOffset = 0;
        this.length = data.remaining();
        parseFields();
    }

    /**
     * Wrap message bytes from a ByteBuffer with explicit length.
     *
     * <p>The ByteBuffer is wrapped in an UnsafeBuffer.</p>
     *
     * @param data the ByteBuffer positioned at the start of the message
     * @param len the length of the message in bytes
     * @deprecated Use {@link #wrap(DirectBuffer, int, int)} instead
     */
    @Deprecated
    public void wrap(ByteBuffer data, int len) {
        reset();
        this.buffer = new UnsafeBuffer(data, data.position(), len);
        this.startOffset = 0;
        this.length = len;
        parseFields();
    }

    /**
     * Reset this message for reuse.
     * All CharSequence wrappers are also reset.
     */
    public void reset() {
        buffer = null;
        startOffset = 0;
        length = 0;
        fieldCount = 0;
        cachedMsgSeqNum = 0;
        fieldIndex.clear();

        // Reset CharSequence pool
        for (int i = 0; i < charSeqPoolIndex; i++) {
            charSeqPool[i].reset();
        }
        charSeqPoolIndex = 0;
    }

    /**
     * Release this message back to its pool.
     *
     * <p>After calling release(), the message and all CharSequence values
     * obtained from it should not be used further.</p>
     */
    public void release() {
        if (ownerPool != null) {
            reset();
            inUse = false;
            ownerPool.release(this);
        } else {
            reset();
            inUse = false;
        }
    }

    // ==================== Field Accessors ====================

    /**
     * Check if the message contains a field.
     *
     * @param tag the tag number
     * @return true if the field is present
     */
    public boolean hasField(int tag) {
        return fieldIndex.containsKey(tag);
    }

    /**
     * Get a field value as a CharSequence (zero-allocation).
     *
     * <p>The returned CharSequence is valid until this message is reset or released.
     * If you need to keep the value longer, call toString() on it.</p>
     *
     * @param tag the tag number
     * @return the field value, or null if not present
     */
    public CharSequence getCharSequence(int tag) {
        int fieldIdx = fieldIndex.get(tag);
        if (fieldIdx == MISSING_VALUE) {
            return null;
        }

        int valueStart = fieldPositions[fieldIdx][0];
        int valueEnd = fieldPositions[fieldIdx][1];

        return acquireCharSequence(valueStart, valueEnd - valueStart);
    }

    /**
     * Get a field value as an int (zero-allocation).
     *
     * @param tag the tag number
     * @return the field value, or 0 if not present or not a valid integer
     */
    public int getInt(int tag) {
        int fieldIdx = fieldIndex.get(tag);
        if (fieldIdx == MISSING_VALUE) {
            return 0;
        }

        return parseIntValue(fieldPositions[fieldIdx][0], fieldPositions[fieldIdx][1]);
    }

    /**
     * Get a field value as a long (zero-allocation).
     *
     * @param tag the tag number
     * @return the field value, or 0 if not present or not a valid long
     */
    public long getLong(int tag) {
        int fieldIdx = fieldIndex.get(tag);
        if (fieldIdx == MISSING_VALUE) {
            return 0;
        }

        return parseLongValue(fieldPositions[fieldIdx][0], fieldPositions[fieldIdx][1]);
    }

    /**
     * Get a field value as a double.
     *
     * @param tag the tag number
     * @return the field value, or 0.0 if not present or not a valid double
     */
    public double getDouble(int tag) {
        int fieldIdx = fieldIndex.get(tag);
        if (fieldIdx == MISSING_VALUE) {
            return 0.0;
        }
        int start = fieldPositions[fieldIdx][0];
        int end = fieldPositions[fieldIdx][1];
        if (start >= end) {
            return 0.0;
        }
        double value = parseDecimal(start, end);
        if (!Double.isNaN(value)) {
            return value;
        }
        // Not a plain decimal of up to 15 digits: let the JDK parse it
        try {
            return Double.parseDouble(acquireCharSequence(start, end - start).toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Parse a FIX decimal ([-]digits[.digits]) without allocating. The result is the same as
     * Double.parseDouble: an integer mantissa below 2^53 and a power of ten up to 10^22 are both
     * exact doubles, so the single division is correctly rounded.
     *
     * @return the value, or NaN when the field is not such a decimal
     */
    private double parseDecimal(int start, int end) {
        int i = start;
        boolean negative = false;
        byte b = buffer.getByte(i);
        if (b == '-' || b == '+') {
            negative = b == '-';
            i++;
        }
        long mantissa = 0;
        int scale = -1;
        int digits = 0;
        for (; i < end; i++) {
            b = buffer.getByte(i);
            if (b >= '0' && b <= '9') {
                mantissa = mantissa * 10 + (b - '0');
                if (mantissa >= MAX_EXACT_MANTISSA) {
                    return Double.NaN;
                }
                digits++;
                if (scale >= 0) {
                    scale++;
                }
            } else if (b == '.' && scale < 0) {
                scale = 0;
            } else {
                return Double.NaN;
            }
        }
        if (digits == 0 || scale >= POWERS_OF_TEN.length) {
            return Double.NaN;
        }
        double value = scale > 0 ? mantissa / POWERS_OF_TEN[scale] : (double) mantissa;
        return negative ? -value : value;
    }

    /**
     * Get a field value as a char (zero-allocation).
     *
     * @param tag the tag number
     * @return the first character of the field value, or '\0' if not present
     */
    public char getChar(int tag) {
        int fieldIdx = fieldIndex.get(tag);
        if (fieldIdx == MISSING_VALUE) {
            return '\0';
        }

        int valueStart = fieldPositions[fieldIdx][0];
        if (valueStart >= startOffset + length) {
            return '\0';
        }
        return (char) (buffer.getByte(valueStart) & 0xFF);
    }

    /**
     * Get a field as a boolean (Y/N or true/false).
     *
     * @param tag the tag number
     * @return true if the value is "Y", "true", or "1"
     */
    public boolean getBool(int tag) {
        char c = getChar(tag);
        return c == 'Y' || c == 'y' || c == '1';
    }

    // ==================== Cached Header Accessors ====================

    /**
     * Get the message type (tag 35) as a CharSequence (zero-allocation).
     *
     * @return the message type
     */
    public CharSequence getMsgTypeCharSeq() {
        return cachedMsgType.isValid() ? cachedMsgType : null;
    }

    /**
     * Get the message type (tag 35) as a String.
     *
     * @return the message type
     */
    public String getMsgType() {
        if (!cachedMsgType.isValid()) {
            return null;
        }
        int len = cachedMsgType.length();
        if (len == 1) {
            char c = cachedMsgType.charAt(0);
            if (c < 128 && ONE_CHAR_MSG_TYPES[c] != null) {
                return ONE_CHAR_MSG_TYPES[c];
            }
        } else if (len == 2) {
            char c1 = cachedMsgType.charAt(0);
            char c2 = cachedMsgType.charAt(1);
            if (c1 < 128 && c2 < 128 && ONE_CHAR_MSG_TYPES[c1] != null && ONE_CHAR_MSG_TYPES[c2] != null) {
                return twoCharMsgType(c1, c2);
            }
        }
        return cachedMsgType.toString();
    }

    /**
     * First use of a two-character MsgType makes its String; later ones return the same instance.
     * Threads may race to fill a slot: each stores an interned String, so they agree.
     */
    private static String twoCharMsgType(char c1, char c2) {
        String[] row = TWO_CHAR_MSG_TYPES[c1];
        if (row == null) {
            row = new String[128];
            TWO_CHAR_MSG_TYPES[c1] = row;
        }
        String type = row[c2];
        if (type == null) {
            type = new String(new char[] {c1, c2}).intern();
            row[c2] = type;
        }
        return type;
    }

    /**
     * Get the message sequence number (tag 34).
     *
     * @return the sequence number
     */
    public int getSeqNum() {
        return cachedMsgSeqNum;
    }

    /**
     * Alias for getSeqNum().
     */
    public int getMsgSeqNum() {
        return cachedMsgSeqNum;
    }

    /**
     * Get the sender comp ID (tag 49) as a CharSequence (zero-allocation).
     *
     * @return the sender comp ID
     */
    public CharSequence getSenderCompIdCharSeq() {
        return cachedSenderCompId.isValid() ? cachedSenderCompId : null;
    }

    /**
     * Get the sender comp ID (tag 49) as a String.
     *
     * @return the sender comp ID
     */
    public String getSenderCompId() {
        return cachedSenderCompId.isValid() ? cachedSenderCompId.toString() : null;
    }

    /**
     * Get the target comp ID (tag 56) as a CharSequence (zero-allocation).
     *
     * @return the target comp ID
     */
    public CharSequence getTargetCompIdCharSeq() {
        return cachedTargetCompId.isValid() ? cachedTargetCompId : null;
    }

    /**
     * Get the target comp ID (tag 56) as a String.
     *
     * @return the target comp ID
     */
    public String getTargetCompId() {
        return cachedTargetCompId.isValid() ? cachedTargetCompId.toString() : null;
    }

    /**
     * Check if this is an admin message.
     *
     * @return true if this is an admin (session-level) message
     */
    public boolean isAdmin() {
        if (!cachedMsgType.isValid()) {
            return false;
        }
        return FixTags.MsgTypes.isAdmin(cachedMsgType);
    }

    // ==================== FIX 5.0+ Accessors ====================

    /**
     * Get the ApplVerID (tag 1128) as a CharSequence (zero-allocation).
     * This is present in application messages when different from the default.
     *
     * @return the ApplVerID, or null if not present
     */
    public CharSequence getApplVerIDCharSeq() {
        return cachedApplVerID.isValid() ? cachedApplVerID : null;
    }

    /**
     * Get the ApplVerID (tag 1128) as a String.
     *
     * @return the ApplVerID, or null if not present
     */
    public String getApplVerID() {
        return cachedApplVerID.isValid() ? cachedApplVerID.toString() : null;
    }

    /**
     * Get the DefaultApplVerID (tag 1137) as a CharSequence (zero-allocation).
     * This is present in Logon messages for FIX 5.0+.
     *
     * @return the DefaultApplVerID, or null if not present
     */
    public CharSequence getDefaultApplVerIDCharSeq() {
        return cachedDefaultApplVerID.isValid() ? cachedDefaultApplVerID : null;
    }

    /**
     * Get the DefaultApplVerID (tag 1137) as a String.
     *
     * @return the DefaultApplVerID, or null if not present
     */
    public String getDefaultApplVerID() {
        return cachedDefaultApplVerID.isValid() ? cachedDefaultApplVerID.toString() : null;
    }

    /**
     * Check if this message has ApplVerID (tag 1128).
     *
     * @return true if ApplVerID is present
     */
    public boolean hasApplVerID() {
        return cachedApplVerID.isValid();
    }

    // ==================== Compatibility Methods ====================

    /**
     * Alias for getInt (FixSession compatibility).
     */
    public int getIntField(int tag) {
        return getInt(tag);
    }

    /**
     * Alias for getBool (FixSession compatibility).
     */
    public boolean getBoolField(int tag) {
        return getBool(tag);
    }

    // ==================== Buffer Access ====================

    /**
     * Get the raw message bytes.
     *
     * @return a copy of the message bytes
     */
    public byte[] getRawMessage() {
        if (buffer == null) {
            return new byte[0];
        }
        byte[] result = new byte[length];
        buffer.getBytes(startOffset, result, 0, length);
        return result;
    }

    /**
     * Get the internal buffer.
     *
     * @return the internal DirectBuffer
     */
    public DirectBuffer getDirectBuffer() {
        return buffer;
    }

    /**
     * Get the start offset within the buffer.
     *
     * @return the start offset
     */
    public int getStartOffset() {
        return startOffset;
    }

    /**
     * Get the message length.
     *
     * @return the length in bytes
     */
    public int getLength() {
        return length;
    }

    // ==================== Pool Management ====================

    /**
     * Check if this message is currently in use.
     *
     * @return true if in use
     */
    public boolean isInUse() {
        return inUse;
    }

    /**
     * Mark this message as in use (called by pool).
     */
    void markInUse() {
        inUse = true;
    }

    /**
     * Set the owner pool (called by pool).
     *
     * @param pool the owning pool
     */
    void setOwnerPool(IncomingMessagePool pool) {
        this.ownerPool = pool;
    }

    // ==================== Private Methods ====================

    /**
     * Acquire a CharSequence wrapper from the internal pool.
     */
    private ByteBufferCharSequence acquireCharSequence(int start, int length) {
        ByteBufferCharSequence cs;
        if (charSeqPoolIndex < CHAR_SEQ_POOL_SIZE) {
            cs = charSeqPool[charSeqPoolIndex++];
        } else {
            // Pool exhausted, create new (will not be pooled)
            cs = new ByteBufferCharSequence();
        }
        cs.wrap(buffer, start, length);
        return cs;
    }

    /**
     * Parse fields from the raw buffer.
     */
    private void parseFields() {
        fieldCount = 0;
        int pos = startOffset;
        int endPos = startOffset + length;

        while (pos < endPos && fieldCount < MAX_FIELDS) {
            int tagStart = pos;

            // Find '='
            while (pos < endPos && buffer.getByte(pos) != EQUALS) {
                pos++;
            }
            if (pos >= endPos) break;

            // Parse tag number
            int tag = parseTagNumber(tagStart, pos);
            pos++; // Skip '='

            int valueStart = pos;

            // Find SOH
            while (pos < endPos && buffer.getByte(pos) != SOH) {
                pos++;
            }

            int valueEnd = pos;
            pos++; // Skip SOH

            // Store field position
            if (fieldCount < MAX_FIELDS) {
                fieldPositions[fieldCount][0] = valueStart;
                fieldPositions[fieldCount][1] = valueEnd;

                // Index the field
                if (tag >= 0) {
                    fieldIndex.put(tag, fieldCount);
                }

                fieldCount++;
            }

            // Cache commonly accessed fields
            cacheField(tag, valueStart, valueEnd);
        }
    }

    private int parseTagNumber(int start, int end) {
        int tag = 0;
        for (int i = start; i < end; i++) {
            byte b = buffer.getByte(i);
            if (b >= '0' && b <= '9') {
                tag = tag * 10 + (b - '0');
            } else {
                return -1;
            }
        }
        return tag;
    }

    private void cacheField(int tag, int valueStart, int valueEnd) {
        switch (tag) {
            case FixTags.MsgType -> cachedMsgType.wrap(buffer, valueStart, valueEnd - valueStart);
            case FixTags.MsgSeqNum -> cachedMsgSeqNum = parseIntValue(valueStart, valueEnd);
            case FixTags.SenderCompID -> cachedSenderCompId.wrap(buffer, valueStart, valueEnd - valueStart);
            case FixTags.TargetCompID -> cachedTargetCompId.wrap(buffer, valueStart, valueEnd - valueStart);
            case FixTags.ApplVerID -> cachedApplVerID.wrap(buffer, valueStart, valueEnd - valueStart);
            case FixTags.DefaultApplVerID -> cachedDefaultApplVerID.wrap(buffer, valueStart, valueEnd - valueStart);
        }
    }

    private int parseIntValue(int start, int end) {
        int value = 0;
        boolean negative = false;
        int i = start;

        if (i < end && buffer.getByte(i) == '-') {
            negative = true;
            i++;
        }

        while (i < end) {
            byte b = buffer.getByte(i++);
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            }
        }

        return negative ? -value : value;
    }

    private long parseLongValue(int start, int end) {
        long value = 0;
        boolean negative = false;
        int i = start;

        if (i < end && buffer.getByte(i) == '-') {
            negative = true;
            i++;
        }

        while (i < end) {
            byte b = buffer.getByte(i++);
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            }
        }

        return negative ? -value : value;
    }

    @Override
    public String toString() {
        if (buffer == null || length == 0) return "";
        byte[] bytes = new byte[length];
        buffer.getBytes(startOffset, bytes, 0, length);
        return new String(bytes, 0, length, StandardCharsets.US_ASCII)
                .replace((char) SOH, '|');
    }
}
