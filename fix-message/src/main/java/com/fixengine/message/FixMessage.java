package com.fixengine.message;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Mutable FIX message container.
 * Stores the raw message bytes and provides methods to read/write fields.
 *
 * <p>This class is designed for high-performance scenarios where object allocation
 * should be minimized. The same instance can be reused by calling {@link #reset()}.</p>
 */
public class FixMessage {

    public static final byte SOH = 0x01; // Field delimiter
    public static final byte EQUALS = '=';

    private static final int INITIAL_CAPACITY = 4096;
    private static final int MAX_FIELDS = 256;

    private byte[] buffer;
    private int length;

    // Field index for fast lookup: [tag] -> position in buffer (or -1)
    private final int[] fieldIndex;

    // Parsed field positions: [fieldNum][0]=tagStart, [fieldNum][1]=valueStart, [fieldNum][2]=valueEnd
    private final int[][] fieldPositions;
    private int fieldCount;

    // Cached header values
    private String msgType;
    private int msgSeqNum;
    private String senderCompId;
    private String targetCompId;

    /**
     * Create a new empty FIX message.
     */
    public FixMessage() {
        this(INITIAL_CAPACITY);
    }

    /**
     * Create a new empty FIX message with specified initial capacity.
     */
    public FixMessage(int initialCapacity) {
        this.buffer = new byte[initialCapacity];
        this.length = 0;
        this.fieldIndex = new int[10000]; // Support tags up to 9999
        Arrays.fill(fieldIndex, -1);
        this.fieldPositions = new int[MAX_FIELDS][3];
        this.fieldCount = 0;
    }

    /**
     * Reset this message for reuse.
     */
    public void reset() {
        length = 0;
        fieldCount = 0;
        msgType = null;
        msgSeqNum = 0;
        senderCompId = null;
        targetCompId = null;
        Arrays.fill(fieldIndex, -1);
    }

    /**
     * Wrap existing message bytes.
     * The bytes are copied into this message's internal buffer.
     *
     * @param data the message bytes
     * @param offset the offset in the array
     * @param len the length of the message
     */
    public void wrap(byte[] data, int offset, int len) {
        reset();
        ensureCapacity(len);
        System.arraycopy(data, offset, buffer, 0, len);
        this.length = len;
        parseFields();
    }

    /**
     * Wrap existing message bytes from a ByteBuffer.
     */
    public void wrap(ByteBuffer data) {
        int len = data.remaining();
        reset();
        ensureCapacity(len);
        data.get(buffer, 0, len);
        this.length = len;
        parseFields();
    }

    /**
     * Get the raw message bytes.
     */
    public byte[] getBuffer() {
        return buffer;
    }

    /**
     * Get the message length.
     */
    public int getLength() {
        return length;
    }

    /**
     * Set the message length (used by FixWriter).
     */
    void setLength(int length) {
        this.length = length;
    }

    /**
     * Ensure buffer has at least the specified capacity.
     */
    void ensureCapacity(int capacity) {
        if (buffer.length < capacity) {
            buffer = Arrays.copyOf(buffer, Math.max(capacity, buffer.length * 2));
        }
    }

    /**
     * Parse fields from the raw buffer.
     */
    private void parseFields() {
        fieldCount = 0;
        int pos = 0;

        while (pos < length && fieldCount < MAX_FIELDS) {
            int tagStart = pos;

            // Find '='
            while (pos < length && buffer[pos] != EQUALS) {
                pos++;
            }
            if (pos >= length) break;

            // Parse tag number
            int tag = parseTagNumber(tagStart, pos);
            pos++; // Skip '='

            int valueStart = pos;

            // Find SOH
            while (pos < length && buffer[pos] != SOH) {
                pos++;
            }

            int valueEnd = pos;
            pos++; // Skip SOH

            // Store field position
            fieldPositions[fieldCount][0] = tagStart;
            fieldPositions[fieldCount][1] = valueStart;
            fieldPositions[fieldCount][2] = valueEnd;
            fieldCount++;

            // Index the field
            if (tag >= 0 && tag < fieldIndex.length) {
                fieldIndex[tag] = valueStart;
            }

            // Cache commonly accessed fields
            cacheField(tag, valueStart, valueEnd);
        }
    }

    private int parseTagNumber(int start, int end) {
        int tag = 0;
        for (int i = start; i < end; i++) {
            byte b = buffer[i];
            if (b >= '0' && b <= '9') {
                tag = tag * 10 + (b - '0');
            } else {
                return -1; // Invalid tag
            }
        }
        return tag;
    }

    private void cacheField(int tag, int valueStart, int valueEnd) {
        switch (tag) {
            case FixTags.MsgType:
                msgType = new String(buffer, valueStart, valueEnd - valueStart, StandardCharsets.US_ASCII);
                break;
            case FixTags.MsgSeqNum:
                msgSeqNum = parseIntValue(valueStart, valueEnd);
                break;
            case FixTags.SenderCompID:
                senderCompId = new String(buffer, valueStart, valueEnd - valueStart, StandardCharsets.US_ASCII);
                break;
            case FixTags.TargetCompID:
                targetCompId = new String(buffer, valueStart, valueEnd - valueStart, StandardCharsets.US_ASCII);
                break;
        }
    }

    private int parseIntValue(int start, int end) {
        int value = 0;
        boolean negative = false;
        int i = start;

        if (i < end && buffer[i] == '-') {
            negative = true;
            i++;
        }

        while (i < end) {
            byte b = buffer[i++];
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            }
        }

        return negative ? -value : value;
    }

    // ==================== Field Accessors ====================

    /**
     * Check if the message contains a field.
     */
    public boolean hasField(int tag) {
        // Check pending fields first
        if (pendingFields != null && pendingFields.containsKey(tag)) {
            return true;
        }
        // Then check parsed buffer
        return tag >= 0 && tag < fieldIndex.length && fieldIndex[tag] >= 0;
    }

    /**
     * Get a field value as a String.
     */
    public String getString(int tag) {
        // First check pending fields (for messages being built)
        if (pendingFields != null && pendingFields.containsKey(tag)) {
            return pendingFields.get(tag);
        }

        // Then check parsed buffer (for messages received/parsed)
        if (tag < 0 || tag >= fieldIndex.length) return null;
        int valueStart = fieldIndex[tag];
        if (valueStart < 0) return null;

        int valueEnd = valueStart;
        while (valueEnd < length && buffer[valueEnd] != SOH) {
            valueEnd++;
        }

        return new String(buffer, valueStart, valueEnd - valueStart, StandardCharsets.US_ASCII);
    }

    /**
     * Get a field value as an int.
     */
    public int getInt(int tag) {
        // First check pending fields (for messages being built)
        if (pendingFields != null && pendingFields.containsKey(tag)) {
            try {
                return Integer.parseInt(pendingFields.get(tag));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // Then check parsed buffer
        if (tag < 0 || tag >= fieldIndex.length) return 0;
        int valueStart = fieldIndex[tag];
        if (valueStart < 0) return 0;

        int valueEnd = valueStart;
        while (valueEnd < length && buffer[valueEnd] != SOH) {
            valueEnd++;
        }

        return parseIntValue(valueStart, valueEnd);
    }

    /**
     * Get a field value as a long.
     */
    public long getLong(int tag) {
        // First check pending fields (for messages being built)
        if (pendingFields != null && pendingFields.containsKey(tag)) {
            try {
                return Long.parseLong(pendingFields.get(tag));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // Then check parsed buffer
        if (tag < 0 || tag >= fieldIndex.length) return 0;
        int valueStart = fieldIndex[tag];
        if (valueStart < 0) return 0;

        int valueEnd = valueStart;
        while (valueEnd < length && buffer[valueEnd] != SOH) {
            valueEnd++;
        }

        long value = 0;
        boolean negative = false;
        int i = valueStart;

        if (i < valueEnd && buffer[i] == '-') {
            negative = true;
            i++;
        }

        while (i < valueEnd) {
            byte b = buffer[i++];
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            }
        }

        return negative ? -value : value;
    }

    /**
     * Get a field value as a double.
     */
    public double getDouble(int tag) {
        String str = getString(tag);
        if (str == null || str.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Get a field value as a char.
     */
    public char getChar(int tag) {
        // First check pending fields (for messages being built)
        if (pendingFields != null && pendingFields.containsKey(tag)) {
            String val = pendingFields.get(tag);
            return (val != null && !val.isEmpty()) ? val.charAt(0) : '\0';
        }

        // Then check parsed buffer
        if (tag < 0 || tag >= fieldIndex.length) return '\0';
        int valueStart = fieldIndex[tag];
        if (valueStart < 0 || valueStart >= length) return '\0';
        return (char) buffer[valueStart];
    }

    // ==================== Cached Header Accessors ====================

    /**
     * Get the message type (tag 35).
     */
    public String getMsgType() {
        return msgType;
    }

    /**
     * Get the message sequence number (tag 34).
     */
    public int getMsgSeqNum() {
        return msgSeqNum;
    }

    /**
     * Get the sender comp ID (tag 49).
     */
    public String getSenderCompId() {
        return senderCompId;
    }

    /**
     * Get the target comp ID (tag 56).
     */
    public String getTargetCompId() {
        return targetCompId;
    }

    /**
     * Check if this is an admin message.
     */
    public boolean isAdmin() {
        return msgType != null && FixTags.MsgTypes.isAdmin(msgType);
    }

    // ==================== Aliases for FixSession compatibility ====================

    /**
     * Get the sequence number (alias for getMsgSeqNum).
     */
    public int getSeqNum() {
        return msgSeqNum;
    }

    /**
     * Get a field as a String (alias for getString).
     */
    public String getStringField(int tag) {
        return getString(tag);
    }

    /**
     * Get a field as an int (alias for getInt).
     */
    public int getIntField(int tag) {
        return getInt(tag);
    }

    /**
     * Get a field as a boolean (Y/N or true/false).
     */
    public boolean getBoolField(int tag) {
        String val = getString(tag);
        return "Y".equalsIgnoreCase(val) || "true".equalsIgnoreCase(val) || "1".equals(val);
    }

    /**
     * Get the raw message bytes as a copy.
     */
    public byte[] getRawMessage() {
        return Arrays.copyOf(buffer, length);
    }

    // ==================== Field Setting (for building messages) ====================

    // Field storage for messages being built (not parsed from bytes)
    private java.util.Map<Integer, String> pendingFields;

    /**
     * Set the message type.
     */
    public void setMsgType(String msgType) {
        this.msgType = msgType;
        setField(FixTags.MsgType, msgType);
    }

    /**
     * Set a field value (String).
     */
    public void setField(int tag, String value) {
        if (pendingFields == null) {
            pendingFields = new java.util.LinkedHashMap<>();
        }
        pendingFields.put(tag, value);
        // Update field index for immediate lookup
        if (tag >= 0 && tag < fieldIndex.length) {
            fieldIndex[tag] = 0; // Mark as present
        }
    }

    /**
     * Set a field value (int).
     */
    public void setField(int tag, int value) {
        setField(tag, String.valueOf(value));
    }

    /**
     * Set a field value (long).
     */
    public void setField(int tag, long value) {
        setField(tag, String.valueOf(value));
    }

    /**
     * Set a field value (double).
     */
    public void setField(int tag, double value) {
        setField(tag, String.valueOf(value));
    }

    /**
     * Set a field value (char).
     */
    public void setField(int tag, char value) {
        setField(tag, String.valueOf(value));
    }

    /**
     * Get all tags present in this message.
     */
    public int[] getTags() {
        int count = 0;
        int[] tags = new int[fieldCount + (pendingFields != null ? pendingFields.size() : 0)];

        // Add tags from parsed message
        for (int i = 0; i < fieldIndex.length && count < tags.length; i++) {
            if (fieldIndex[i] >= 0) {
                tags[count++] = i;
            }
        }

        // Add tags from pending fields not already in fieldIndex
        if (pendingFields != null) {
            for (int tag : pendingFields.keySet()) {
                boolean found = false;
                for (int i = 0; i < count; i++) {
                    if (tags[i] == tag) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    tags[count++] = tag;
                }
            }
        }

        return Arrays.copyOf(tags, count);
    }

    /**
     * Get pending fields map (for FixWriter to access when building messages).
     */
    public java.util.Map<Integer, String> getPendingFields() {
        return pendingFields;
    }

    // ==================== Utility ====================

    /**
     * Get a human-readable representation of the message.
     * Replaces SOH with '|' for readability.
     */
    @Override
    public String toString() {
        if (length == 0) return "";
        return new String(buffer, 0, length, StandardCharsets.US_ASCII)
                .replace((char) SOH, '|');
    }

    /**
     * Get the raw message as a String (with actual SOH characters).
     */
    public String toRawString() {
        if (length == 0) return "";
        return new String(buffer, 0, length, StandardCharsets.US_ASCII);
    }

    /**
     * Copy the message to a ByteBuffer.
     */
    public void copyTo(ByteBuffer dest) {
        dest.put(buffer, 0, length);
    }
}
