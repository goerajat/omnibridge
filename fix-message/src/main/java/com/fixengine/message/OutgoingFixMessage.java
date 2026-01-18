package com.fixengine.message;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * Pre-allocated FIX message with direct byte encoding for latency-optimized outgoing messaging.
 *
 * <p>This class implements a pre-allocated message pooling approach where:</p>
 * <ul>
 *   <li>The buffer is allocated once and reused</li>
 *   <li>Header fields (BeginString, SenderCompID, TargetCompID) are pre-populated</li>
 *   <li>Dynamic fields (SeqNum, SendingTime, BodyLength, CheckSum) are filled at send time</li>
 *   <li>BitSet provides O(1) duplicate tag detection</li>
 *   <li>All encoding is done directly to bytes without String allocations</li>
 *   <li>Field values can be set using CharSequence for zero-copy operations</li>
 * </ul>
 *
 * <h2>Memory Layout</h2>
 * <pre>
 * Position:  0         12       17        26              46              66
 * Buffer:    8=FIX.4.4|9=XXXXX|35=X...|34=NNNNNNNN|49=SENDER......|56=TARGET......|52=YYYYMMDD-HH:MM:SS.sss|body...|10=XXX|
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * OutgoingFixMessage msg = pool.acquire();
 * msg.setMsgType("D");
 * msg.setField(11, "ORDER-001");
 * msg.setField(55, "AAPL");
 * msg.setField(54, '1');
 * msg.setField(38, 100);
 * msg.setField(44, 150.25, 2);
 * byte[] encoded = msg.prepareForSend(seqNum, System.currentTimeMillis());
 * // send encoded bytes
 * msg.release(); // returns to pool
 * }</pre>
 */
public class OutgoingFixMessage {

    public static final byte SOH = 0x01;
    private static final byte EQUALS = '=';

    // Pre-allocated buffer
    private final byte[] buffer;
    private final int maxMessageLength;

    // Header field positions (calculated at construction)
    private final int bodyLengthValuePos;
    private final int bodyLengthDigits;
    private final int msgTypePos;
    private int msgTypeEndPos;
    private final int seqNumValuePos;
    private final int seqNumDigits;
    private final int senderCompIdPos;
    private final int targetCompIdPos;
    private final int sendingTimeValuePos;
    private final int bodyStartPos;

    // Dynamic state
    private int writePos;
    private final BitSet tagSet;
    private final int maxTagNumber;
    private String msgType;

    // Pool management
    private volatile boolean inUse;
    private MessagePool ownerPool;

    /**
     * Create a new OutgoingFixMessage with the given configuration.
     *
     * @param config the pool configuration
     */
    public OutgoingFixMessage(MessagePoolConfig config) {
        this.maxMessageLength = config.getMaxMessageLength();
        this.buffer = new byte[maxMessageLength];
        this.maxTagNumber = config.getMaxTagNumber();
        this.tagSet = new BitSet(maxTagNumber);
        this.bodyLengthDigits = config.getBodyLengthDigits();
        this.seqNumDigits = config.getSeqNumDigits();

        // Calculate header positions and pre-populate fixed fields
        int pos = 0;

        // 8=BeginString|
        pos = writeField(pos, FixTags.BeginString, config.getBeginString());

        // 9=XXXXX| (placeholder for body length)
        pos = writeTag(pos, FixTags.BodyLength);
        this.bodyLengthValuePos = pos;
        for (int i = 0; i < bodyLengthDigits; i++) {
            buffer[pos++] = '0';
        }
        buffer[pos++] = SOH;

        // 35=X| (placeholder for msg type, will be filled per message)
        pos = writeTag(pos, FixTags.MsgType);
        this.msgTypePos = pos;
        // Leave space for msg type (max 2 chars typically)
        buffer[pos++] = ' ';
        buffer[pos++] = SOH;
        this.msgTypeEndPos = pos;

        // 34=NNNNNNNN| (placeholder for sequence number)
        pos = writeTag(pos, FixTags.MsgSeqNum);
        this.seqNumValuePos = pos;
        for (int i = 0; i < seqNumDigits; i++) {
            buffer[pos++] = '0';
        }
        buffer[pos++] = SOH;

        // 49=SENDER| (pre-populated)
        pos = writeTag(pos, FixTags.SenderCompID);
        this.senderCompIdPos = pos;
        byte[] senderBytes = config.getSenderCompId().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(senderBytes, 0, buffer, pos, senderBytes.length);
        pos += senderBytes.length;
        buffer[pos++] = SOH;

        // 56=TARGET| (pre-populated)
        pos = writeTag(pos, FixTags.TargetCompID);
        this.targetCompIdPos = pos;
        byte[] targetBytes = config.getTargetCompId().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(targetBytes, 0, buffer, pos, targetBytes.length);
        pos += targetBytes.length;
        buffer[pos++] = SOH;

        // 52=YYYYMMDD-HH:MM:SS.sss| (placeholder for sending time)
        pos = writeTag(pos, FixTags.SendingTime);
        this.sendingTimeValuePos = pos;
        // Pre-fill with placeholder timestamp (21 bytes)
        for (int i = 0; i < FastTimestampEncoder.TIMESTAMP_LENGTH; i++) {
            buffer[pos++] = '0';
        }
        buffer[pos++] = SOH;

        // Body starts here
        this.bodyStartPos = pos;
        this.writePos = pos;
    }

    /**
     * Set the message type.
     *
     * @param msgType the message type (e.g., "D" for NewOrderSingle)
     */
    public void setMsgType(String msgType) {
        if (msgType == null || msgType.isEmpty()) {
            throw new IllegalArgumentException("Message type cannot be null or empty");
        }

        this.msgType = msgType;
        byte[] msgTypeBytes = msgType.getBytes(StandardCharsets.US_ASCII);

        // Update the msg type in the buffer
        int pos = msgTypePos;
        System.arraycopy(msgTypeBytes, 0, buffer, pos, msgTypeBytes.length);
        pos += msgTypeBytes.length;
        buffer[pos++] = SOH;
        this.msgTypeEndPos = pos;

        // Recalculate body start position if msg type length changed
        rebuildHeaderAfterMsgType();
    }

    /**
     * Rebuild header fields after MsgType position.
     */
    private void rebuildHeaderAfterMsgType() {
        // For now, we keep the structure fixed with the initial layout
        // The variable-length msg type is handled by the msgTypeEndPos marker
    }

    /**
     * Set a CharSequence field value (zero-allocation when using ByteBufferCharSequence).
     *
     * @param tag the FIX tag number
     * @param value the field value as CharSequence
     * @throws DuplicateTagException if the tag has already been set
     */
    public void setField(int tag, CharSequence value) {
        if (value == null) {
            return;
        }
        checkDuplicate(tag);

        int valueLength = value.length();
        ensureCapacity(writePos + 10 + valueLength);

        writePos = writeTag(writePos, tag);

        // Write CharSequence directly to buffer
        for (int i = 0; i < valueLength; i++) {
            buffer[writePos++] = (byte) value.charAt(i);
        }
        buffer[writePos++] = SOH;
    }

    /**
     * Set a String field value.
     *
     * @param tag the FIX tag number
     * @param value the field value
     * @throws DuplicateTagException if the tag has already been set
     */
    public void setField(int tag, String value) {
        if (value == null) {
            return;
        }
        // Delegate to CharSequence method
        setField(tag, (CharSequence) value);
    }

    /**
     * Set an integer field value (zero-allocation).
     *
     * @param tag the FIX tag number
     * @param value the field value
     * @throws DuplicateTagException if the tag has already been set
     */
    public void setField(int tag, int value) {
        checkDuplicate(tag);
        ensureCapacity(writePos + 20);

        writePos = writeTag(writePos, tag);
        writePos += FastIntEncoder.encode(value, buffer, writePos);
        buffer[writePos++] = SOH;
    }

    /**
     * Set a long field value (zero-allocation).
     *
     * @param tag the FIX tag number
     * @param value the field value
     * @throws DuplicateTagException if the tag has already been set
     */
    public void setField(int tag, long value) {
        checkDuplicate(tag);
        ensureCapacity(writePos + 25);

        writePos = writeTag(writePos, tag);
        writePos += FastIntEncoder.encode(value, buffer, writePos);
        buffer[writePos++] = SOH;
    }

    /**
     * Set a double field value with specified decimal places (zero-allocation).
     *
     * @param tag the FIX tag number
     * @param value the field value
     * @param decimalPlaces number of digits after decimal point
     * @throws DuplicateTagException if the tag has already been set
     */
    public void setField(int tag, double value, int decimalPlaces) {
        checkDuplicate(tag);
        ensureCapacity(writePos + 30);

        writePos = writeTag(writePos, tag);
        writePos += FastIntEncoder.encode(value, buffer, writePos, decimalPlaces);
        buffer[writePos++] = SOH;
    }

    /**
     * Set a char field value.
     *
     * @param tag the FIX tag number
     * @param value the field value
     * @throws DuplicateTagException if the tag has already been set
     */
    public void setField(int tag, char value) {
        checkDuplicate(tag);
        ensureCapacity(writePos + 10);

        writePos = writeTag(writePos, tag);
        buffer[writePos++] = (byte) value;
        buffer[writePos++] = SOH;
    }

    /**
     * Set a boolean field value (Y/N format).
     *
     * @param tag the FIX tag number
     * @param value the boolean value
     * @throws DuplicateTagException if the tag has already been set
     */
    public void setField(int tag, boolean value) {
        setField(tag, value ? 'Y' : 'N');
    }

    /**
     * Prepare the message for sending by filling in dynamic header fields.
     *
     * <p>This method fills in:</p>
     * <ul>
     *   <li>SeqNum (tag 34) - the provided sequence number</li>
     *   <li>SendingTime (tag 52) - the provided timestamp</li>
     *   <li>BodyLength (tag 9) - calculated from message content</li>
     *   <li>CheckSum (tag 10) - calculated from all preceding bytes</li>
     * </ul>
     *
     * @param seqNum the sequence number to use
     * @param sendingTimeMillis the sending time in epoch milliseconds
     * @return the complete message as a byte array (view into internal buffer)
     */
    public byte[] prepareForSend(int seqNum, long sendingTimeMillis) {
        // 1. Encode SeqNum (fixed width, zero-padded)
        FastIntEncoder.encodeFixedWidth(seqNum, buffer, seqNumValuePos, seqNumDigits, '0');

        // 2. Encode SendingTime
        FastTimestampEncoder.encode(sendingTimeMillis, buffer, sendingTimeValuePos);

        // 3. Calculate BodyLength
        // Body length = from after 9=XXXXX| to end of body (before checksum)
        // It includes everything from tag 35 onwards
        int bodyLengthStart = bodyLengthValuePos + bodyLengthDigits + 1; // Skip past "XXXXX|"
        int bodyLength = writePos - bodyLengthStart;
        FastIntEncoder.encodeFixedWidth(bodyLength, buffer, bodyLengthValuePos, bodyLengthDigits, '0');

        // 4. Calculate CheckSum (sum of all bytes mod 256)
        int checksum = 0;
        for (int i = 0; i < writePos; i++) {
            checksum += (buffer[i] & 0xFF);
        }
        checksum %= 256;

        // 5. Write CheckSum (10=XXX|)
        buffer[writePos++] = '1';
        buffer[writePos++] = '0';
        buffer[writePos++] = EQUALS;
        buffer[writePos++] = (byte) ('0' + checksum / 100);
        buffer[writePos++] = (byte) ('0' + (checksum / 10) % 10);
        buffer[writePos++] = (byte) ('0' + checksum % 10);
        buffer[writePos++] = SOH;

        return buffer;
    }

    /**
     * Get the total message length after prepareForSend().
     *
     * @return the message length in bytes
     */
    public int getLength() {
        return writePos;
    }

    /**
     * Get the underlying buffer.
     *
     * <p>Note: This returns the internal buffer directly. Modifications will
     * affect the message state.</p>
     *
     * @return the internal byte buffer
     */
    public byte[] getBuffer() {
        return buffer;
    }

    /**
     * Get the message type that was set.
     *
     * @return the message type, or null if not set
     */
    public String getMsgType() {
        return msgType;
    }

    /**
     * Check if a tag has been set on this message.
     *
     * @param tag the tag to check
     * @return true if the tag has been set
     */
    public boolean hasTag(int tag) {
        return tag >= 0 && tag < maxTagNumber && tagSet.get(tag);
    }

    /**
     * Reset this message for reuse.
     *
     * <p>Clears the body content and tag tracking, but preserves the
     * pre-populated header structure.</p>
     */
    public void reset() {
        writePos = bodyStartPos;
        tagSet.clear();
        msgType = null;

        // Reset msg type placeholder
        buffer[msgTypePos] = ' ';
        buffer[msgTypePos + 1] = SOH;
        msgTypeEndPos = msgTypePos + 2;
    }

    /**
     * Release this message back to its pool.
     *
     * <p>This method resets the message and returns it to the pool for reuse.
     * After calling release(), the message should not be used further.</p>
     */
    public void release() {
        if (ownerPool != null) {
            reset();
            ownerPool.release(this);
        }
        inUse = false;
    }

    /**
     * Check if this message is currently in use.
     *
     * @return true if the message is in use
     */
    public boolean isInUse() {
        return inUse;
    }

    /**
     * Mark this message as in use (called by MessagePool).
     */
    void markInUse() {
        inUse = true;
    }

    /**
     * Set the owner pool (called by MessagePool).
     *
     * @param pool the owning pool
     */
    void setOwnerPool(MessagePool pool) {
        this.ownerPool = pool;
    }

    /**
     * Get a copy of the message bytes.
     *
     * @return a copy of the message content
     */
    public byte[] toByteArray() {
        byte[] result = new byte[writePos];
        System.arraycopy(buffer, 0, result, 0, writePos);
        return result;
    }

    /**
     * Get a string representation of the message (with | instead of SOH).
     *
     * @return human-readable message string
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(writePos);
        for (int i = 0; i < writePos; i++) {
            byte b = buffer[i];
            if (b == SOH) {
                sb.append('|');
            } else {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    // ==================== Private Helper Methods ====================

    /**
     * Check for duplicate tag and mark it as used.
     */
    private void checkDuplicate(int tag) {
        if (tag >= 0 && tag < maxTagNumber) {
            if (tagSet.get(tag)) {
                throw new DuplicateTagException(tag);
            }
            tagSet.set(tag);
        }
    }

    /**
     * Write a tag number followed by '='.
     *
     * @return the new position after writing
     */
    private int writeTag(int pos, int tag) {
        if (tag < 10) {
            buffer[pos++] = (byte) ('0' + tag);
        } else if (tag < 100) {
            buffer[pos++] = (byte) ('0' + tag / 10);
            buffer[pos++] = (byte) ('0' + tag % 10);
        } else if (tag < 1000) {
            buffer[pos++] = (byte) ('0' + tag / 100);
            buffer[pos++] = (byte) ('0' + (tag / 10) % 10);
            buffer[pos++] = (byte) ('0' + tag % 10);
        } else if (tag < 10000) {
            buffer[pos++] = (byte) ('0' + tag / 1000);
            buffer[pos++] = (byte) ('0' + (tag / 100) % 10);
            buffer[pos++] = (byte) ('0' + (tag / 10) % 10);
            buffer[pos++] = (byte) ('0' + tag % 10);
        } else {
            // 5+ digit tags
            String tagStr = String.valueOf(tag);
            for (int i = 0; i < tagStr.length(); i++) {
                buffer[pos++] = (byte) tagStr.charAt(i);
            }
        }
        buffer[pos++] = EQUALS;
        return pos;
    }

    /**
     * Write a field (tag=value|) during header construction.
     *
     * @return the new position after writing
     */
    private int writeField(int pos, int tag, String value) {
        pos = writeTag(pos, tag);
        byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(valueBytes, 0, buffer, pos, valueBytes.length);
        pos += valueBytes.length;
        buffer[pos++] = SOH;
        return pos;
    }

    /**
     * Ensure the buffer has capacity for additional bytes.
     */
    private void ensureCapacity(int required) {
        if (required > maxMessageLength) {
            throw new IllegalStateException("Message exceeds maximum length: " + required + " > " + maxMessageLength);
        }
    }
}
