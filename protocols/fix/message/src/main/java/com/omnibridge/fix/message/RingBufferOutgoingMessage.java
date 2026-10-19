package com.omnibridge.fix.message;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * FIX message that encodes directly into a ring buffer region for zero-copy sending.
 *
 * <p>This class wraps a claimed region from a {@link org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer}
 * and provides a field-setting API for building FIX messages. The message is encoded
 * directly into the ring buffer without intermediate copies.</p>
 *
 * <h2>Memory Layout</h2>
 * <pre>
 * Ring Buffer Message:
 * ┌────────────────┬────────────────┬────────────────┬─────────────────────┐
 * │ Record Len (4B)│ Msg Type ID(4B)│ FIX Len (4B)   │ FIX Message (N B)   │
 * └────────────────┴────────────────┴────────────────┴─────────────────────┘
 *      (Agrona)        (Agrona)       (Our prefix)     (Encoded payload)
 *                                          │                    │
 *                                          │                    └─► SENT TO SOCKET
 *                                          └─► Used internally to know payload length
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * RingBufferOutgoingMessage msg = session.tryClaimMessage("D");
 * if (msg != null) {
 *     msg.setField(11, "ORDER-001");
 *     msg.setField(55, "AAPL");
 *     msg.setField(54, '1');
 *     msg.setField(38, 100);
 *     msg.setField(44, 150.25, 2);
 *     session.commitMessage(msg);  // MsgSeqNum is assigned when the message is sent
 * } else {
 *     // Ring buffer full - retry or handle backpressure
 * }
 * }</pre>
 */
public class RingBufferOutgoingMessage {

    public static final byte SOH = 0x01;
    private static final byte EQUALS = '=';

    // Length prefix size (4 bytes for FIX message length)
    public static final int LENGTH_PREFIX_SIZE = 4;

    // The buffer we're writing into (from ring buffer claim)
    private MutableDirectBuffer buffer;
    private int bufferOffset;  // Offset within the buffer where our region starts
    private int maxLength;     // Maximum message length

    // Header field positions (relative to bufferOffset + LENGTH_PREFIX_SIZE)
    private int bodyLengthValuePos;
    private int bodyLengthDigits;
    private int msgTypePos;
    private int msgTypeEndPos;  // End position after writing msg type
    private int seqNumValuePos;
    private int seqNumDigits;
    private int sendingTimeValuePos;
    private int bodyStartPos;

    // Dynamic state
    private int writePos;
    private IntHashSet tagSet;
    private int maxTagNumber;
    private String msgType;

    // Claim index for commit/abort
    private int claimIndex;
    private int seqNum;
    // Whatever the claim was made on (the session's channel), so commit/abort reach the same one
    private Object claimOwner;

    // Configuration from session
    private String beginString;
    private String senderCompId;
    private String targetCompId;

    // Header bytes per MsgType length, built once for the strings above
    private HeaderTemplate[] headerTemplates;
    private HeaderTemplate header;

    private static final class HeaderTemplate {
        final DirectBuffer bytes;
        final int length;
        final int bodyLengthValueOffset;
        final int msgTypeOffset;
        final int msgTypeLength;
        final int seqNumValueOffset;
        final int sendingTimeValueOffset;
        // Byte sum of the header excluding the four values filled in per message
        final int constantSum;

        HeaderTemplate(DirectBuffer bytes, int length, int bodyLengthValueOffset, int msgTypeOffset,
                       int msgTypeLength, int seqNumValueOffset, int sendingTimeValueOffset, int constantSum) {
            this.bytes = bytes;
            this.length = length;
            this.bodyLengthValueOffset = bodyLengthValueOffset;
            this.msgTypeOffset = msgTypeOffset;
            this.msgTypeLength = msgTypeLength;
            this.seqNumValueOffset = seqNumValueOffset;
            this.sendingTimeValueOffset = sendingTimeValueOffset;
            this.constantSum = constantSum;
        }
    }

    /**
     * Create a new RingBufferOutgoingMessage with default configuration.
     */
    public RingBufferOutgoingMessage() {
        this.bodyLengthDigits = 5;
        this.seqNumDigits = 8;
        this.maxTagNumber = 1000;
        this.tagSet = new IntHashSet(64);  // Initial capacity, will grow as needed
    }

    /**
     * Create a new RingBufferOutgoingMessage with configuration.
     *
     * @param config the message pool configuration
     */
    public RingBufferOutgoingMessage(MessagePoolConfig config) {
        this.bodyLengthDigits = config.getBodyLengthDigits();
        this.seqNumDigits = config.getSeqNumDigits();
        this.maxTagNumber = config.getMaxTagNumber();
        this.beginString = config.getBeginString();
        this.senderCompId = config.getSenderCompId();
        this.targetCompId = config.getTargetCompId();
        this.tagSet = new IntHashSet(64);  // Initial capacity, will grow as needed
    }

    /**
     * Wrap this message around a claimed ring buffer region and initialize the header.
     *
     * @param buffer the ring buffer
     * @param offset the offset within the buffer where our region starts
     * @param length the maximum length available for this message
     * @param seqNum the sequence number to use for this message
     * @param claimIndex the claim index for commit/abort
     * @param beginString the FIX protocol version
     * @param senderCompId the sender comp ID
     * @param targetCompId the target comp ID
     */
    public void wrap(MutableDirectBuffer buffer, int offset, int length,
                     int seqNum, int claimIndex,
                     String beginString, String senderCompId, String targetCompId) {
        wrapRegion(buffer, offset, length, seqNum, claimIndex, beginString, senderCompId, targetCompId);
        this.msgType = null;
        applyHeaderTemplate(1);
    }

    /**
     * Wrap this message around a claimed ring buffer region and write its header,
     * including the message type, in a single copy from a cached header template.
     *
     * @param msgType the message type (e.g., "D" or "AE")
     */
    public void wrap(MutableDirectBuffer buffer, int offset, int length,
                     int seqNum, int claimIndex,
                     String beginString, String senderCompId, String targetCompId,
                     String msgType) {
        checkMsgType(msgType);
        wrapRegion(buffer, offset, length, seqNum, claimIndex, beginString, senderCompId, targetCompId);
        applyHeaderTemplate(msgType.length());
        writeMsgType(msgType);
    }

    private void wrapRegion(MutableDirectBuffer buffer, int offset, int length, int seqNum, int claimIndex,
                            String beginString, String senderCompId, String targetCompId) {
        this.buffer = buffer;
        this.bufferOffset = offset;
        this.maxLength = length;
        this.seqNum = seqNum;
        this.claimIndex = claimIndex;
        if (!sameHeaderStrings(beginString, senderCompId, targetCompId)) {
            this.beginString = beginString;
            this.senderCompId = senderCompId;
            this.targetCompId = targetCompId;
            this.headerTemplates = new HeaderTemplate[3];
        }
        this.tagSet.clear();
    }

    private boolean sameHeaderStrings(String begin, String sender, String target) {
        return headerTemplates != null
                && (begin == beginString || begin.equals(beginString))
                && (sender == senderCompId || sender.equals(senderCompId))
                && (target == targetCompId || target.equals(targetCompId));
    }

    /**
     * Copy the header for a message type of the given length into the claimed region
     * and point the header field positions at it.
     */
    private void applyHeaderTemplate(int msgTypeLength) {
        HeaderTemplate[] templates = headerTemplates;
        if (msgTypeLength >= templates.length) {
            templates = java.util.Arrays.copyOf(templates, msgTypeLength + 1);
            headerTemplates = templates;
        }
        HeaderTemplate t = templates[msgTypeLength];
        if (t == null) {
            t = buildHeaderTemplate(msgTypeLength);
            templates[msgTypeLength] = t;
        }

        int base = bufferOffset + LENGTH_PREFIX_SIZE;
        buffer.putBytes(base, t.bytes, 0, t.length);
        this.header = t;
        this.bodyLengthValuePos = base + t.bodyLengthValueOffset;
        this.msgTypePos = base + t.msgTypeOffset;
        this.msgTypeEndPos = msgTypePos + msgTypeLength + 1;
        this.seqNumValuePos = base + t.seqNumValueOffset;
        this.sendingTimeValuePos = base + t.sendingTimeValueOffset;
        this.bodyStartPos = base + t.length;
        this.writePos = bodyStartPos;
    }

    /**
     * Build the header bytes for one MsgType length.
     * Layout: 8=FIX.4.4|9=XXXXX|35=X|34=NNNNNNNN|49=SENDER|56=TARGET|52=YYYYMMDD-HH:MM:SS.sss|
     */
    private HeaderTemplate buildHeaderTemplate(int msgTypeLength) {
        UnsafeBuffer b = new UnsafeBuffer(new byte[beginString.length() + senderCompId.length()
                + targetCompId.length() + bodyLengthDigits + seqNumDigits + msgTypeLength
                + FastTimestampEncoder.TIMESTAMP_LENGTH + 32]);
        int pos = putField(b, 0, FixTags.BeginString, beginString);

        pos = putTag(b, pos, FixTags.BodyLength);
        int bodyLengthOffset = pos;
        pos = putFill(b, pos, '0', bodyLengthDigits);
        b.putByte(pos++, SOH);

        pos = putTag(b, pos, FixTags.MsgType);
        int msgTypeOffset = pos;
        pos = putFill(b, pos, ' ', msgTypeLength);
        b.putByte(pos++, SOH);

        pos = putTag(b, pos, FixTags.MsgSeqNum);
        int seqNumOffset = pos;
        pos = putFill(b, pos, '0', seqNumDigits);
        b.putByte(pos++, SOH);

        pos = putField(b, pos, FixTags.SenderCompID, senderCompId);
        pos = putField(b, pos, FixTags.TargetCompID, targetCompId);

        pos = putTag(b, pos, FixTags.SendingTime);
        int sendingTimeOffset = pos;
        pos = putFill(b, pos, '0', FastTimestampEncoder.TIMESTAMP_LENGTH);
        b.putByte(pos++, SOH);

        int constantSum = sum(b, 0, pos)
                - sum(b, bodyLengthOffset, bodyLengthDigits)
                - sum(b, msgTypeOffset, msgTypeLength)
                - sum(b, seqNumOffset, seqNumDigits)
                - sum(b, sendingTimeOffset, FastTimestampEncoder.TIMESTAMP_LENGTH);

        return new HeaderTemplate(b, pos, bodyLengthOffset, msgTypeOffset, msgTypeLength,
                seqNumOffset, sendingTimeOffset, constantSum);
    }

    /**
     * Set the message type.
     *
     * <p>Prefer {@link #wrap(MutableDirectBuffer, int, int, int, int, String, String, String, String)},
     * which writes the type with the header. A type of a different length can only be set
     * before any body field, since it moves every field after it.</p>
     *
     * @param msgType the message type (e.g., "D" for NewOrderSingle)
     * @throws IllegalStateException if the length changes after body fields were written
     */
    public void setMsgType(String msgType) {
        checkMsgType(msgType);
        if (msgType.length() != header.msgTypeLength) {
            if (writePos != bodyStartPos) {
                throw new IllegalStateException("MsgType '" + msgType
                        + "' changes the header length after body fields were written");
            }
            applyHeaderTemplate(msgType.length());
        }
        writeMsgType(msgType);
    }

    private static void checkMsgType(String msgType) {
        if (msgType == null || msgType.isEmpty()) {
            throw new IllegalArgumentException("Message type cannot be null or empty");
        }
    }

    private void writeMsgType(String msgType) {
        this.msgType = msgType;
        for (int i = 0, n = msgType.length(); i < n; i++) {
            buffer.putByte(msgTypePos + i, (byte) msgType.charAt(i));
        }
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
            buffer.putByte(writePos++, (byte) value.charAt(i));
        }
        buffer.putByte(writePos++, SOH);
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
        writePos = encodeInt(value, writePos);
        buffer.putByte(writePos++, SOH);
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
        writePos = encodeLong(value, writePos);
        buffer.putByte(writePos++, SOH);
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
        writePos = encodeDouble(value, writePos, decimalPlaces);
        buffer.putByte(writePos++, SOH);
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
        buffer.putByte(writePos++, (byte) value);
        buffer.putByte(writePos++, SOH);
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
     *   <li>SeqNum (tag 34) - the sequence number assigned at claim time</li>
     *   <li>SendingTime (tag 52) - the provided timestamp</li>
     *   <li>BodyLength (tag 9) - calculated from message content</li>
     *   <li>CheckSum (tag 10) - calculated from all preceding bytes</li>
     *   <li>Length prefix - written at the start for socket drain</li>
     * </ul>
     *
     * @param sendingTimeMillis the sending time in epoch milliseconds
     * @return the total message length (excluding length prefix)
     */
    public int prepareForSend(long sendingTimeMillis) {
        // 1. Encode SeqNum (fixed width, zero-padded)
        encodeIntFixedWidth(seqNum, seqNumValuePos, seqNumDigits);

        // 2. Encode SendingTime
        encodeSendingTime(sendingTimeMillis);

        // 3. Calculate BodyLength
        // Body length = from after 9=XXXXX| to end of body (before checksum)
        int bodyLengthStart = bodyLengthValuePos + bodyLengthDigits + 1; // Skip past "XXXXX|"
        int bodyLength = writePos - bodyLengthStart;
        encodeIntFixedWidth(bodyLength, bodyLengthValuePos, bodyLengthDigits);

        // 4. Calculate CheckSum (sum of all bytes mod 256); the header's fixed bytes are pre-summed
        int checksum = header.constantSum
                + sum(buffer, bodyLengthValuePos, bodyLengthDigits)
                + sum(buffer, msgTypePos, header.msgTypeLength)
                + sum(buffer, seqNumValuePos, seqNumDigits)
                + sum(buffer, sendingTimeValuePos, FastTimestampEncoder.TIMESTAMP_LENGTH)
                + sum(buffer, bodyStartPos, writePos - bodyStartPos);
        checksum &= 0xFF;

        // 5. Write CheckSum (10=XXX|)
        buffer.putByte(writePos++, (byte) '1');
        buffer.putByte(writePos++, (byte) '0');
        buffer.putByte(writePos++, EQUALS);
        buffer.putByte(writePos++, (byte) ('0' + checksum / 100));
        buffer.putByte(writePos++, (byte) ('0' + (checksum / 10) % 10));
        buffer.putByte(writePos++, (byte) ('0' + checksum % 10));
        buffer.putByte(writePos++, SOH);

        // 6. Write length prefix at start
        int messageLength = writePos - (bufferOffset + LENGTH_PREFIX_SIZE);
        buffer.putInt(bufferOffset, messageLength);

        return messageLength;
    }

    /**
     * Get the total message length after prepareForSend().
     *
     * @return the message length in bytes (excluding length prefix)
     */
    public int getLength() {
        return writePos - (bufferOffset + LENGTH_PREFIX_SIZE);
    }

    /**
     * Get the total buffer usage including length prefix.
     *
     * @return the total buffer usage in bytes
     */
    public int getTotalLength() {
        return writePos - bufferOffset;
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
     * Get the sequence number written by {@link #prepareForSend}.
     *
     * <p>0 when the session assigns MsgSeqNum as the message is sent; the number
     * then appears only in the bytes that reach the wire and the message log.</p>
     *
     * @return the sequence number, or 0 if assigned at send time
     */
    public int getSeqNum() {
        return seqNum;
    }

    public Object getClaimOwner() {
        return claimOwner;
    }

    public void setClaimOwner(Object claimOwner) {
        this.claimOwner = claimOwner;
    }

    /**
     * Set the sequence number for this message.
     * Used for gap fills and resends that require a specific sequence number.
     *
     * @param seqNum the sequence number to use
     */
    public void setSeqNum(int seqNum) {
        this.seqNum = seqNum;
    }

    /**
     * Get the claim index for commit/abort operations.
     *
     * @return the claim index
     */
    public int getClaimIndex() {
        return claimIndex;
    }

    /**
     * Check if a tag has been set on this message.
     *
     * @param tag the tag to check
     * @return true if the tag has been set
     */
    public boolean hasTag(int tag) {
        return tag >= 0 && tag < maxTagNumber && tagSet.contains(tag);
    }

    /**
     * Get the underlying buffer.
     *
     * @return the buffer
     */
    public MutableDirectBuffer getBuffer() {
        return buffer;
    }

    /**
     * Get the buffer offset.
     *
     * @return the offset within the buffer where this message starts
     */
    public int getBufferOffset() {
        return bufferOffset;
    }

    /**
     * Get a copy of the message bytes (for logging).
     *
     * @return a copy of the message content
     */
    public byte[] toByteArray() {
        int length = writePos - (bufferOffset + LENGTH_PREFIX_SIZE);
        byte[] result = new byte[length];
        buffer.getBytes(bufferOffset + LENGTH_PREFIX_SIZE, result, 0, length);
        return result;
    }

    /**
     * Get a string representation of the message (with | instead of SOH).
     *
     * @return human-readable message string
     */
    @Override
    public String toString() {
        int messageStart = bufferOffset + LENGTH_PREFIX_SIZE;
        int length = writePos - messageStart;
        StringBuilder sb = new StringBuilder(length);
        for (int i = messageStart; i < writePos; i++) {
            byte b = buffer.getByte(i);
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
            if (!tagSet.add(tag)) {
                throw new DuplicateTagException(tag);
            }
        }
    }

    /**
     * Write a tag number followed by '='.
     *
     * @return the new position after writing
     */
    private int writeTag(int pos, int tag) {
        return putTag(buffer, pos, tag);
    }

    private static int putTag(MutableDirectBuffer buffer, int pos, int tag) {
        if (tag < 10) {
            buffer.putByte(pos++, (byte) ('0' + tag));
        } else if (tag < 100) {
            buffer.putByte(pos++, (byte) ('0' + tag / 10));
            buffer.putByte(pos++, (byte) ('0' + tag % 10));
        } else if (tag < 1000) {
            buffer.putByte(pos++, (byte) ('0' + tag / 100));
            buffer.putByte(pos++, (byte) ('0' + (tag / 10) % 10));
            buffer.putByte(pos++, (byte) ('0' + tag % 10));
        } else if (tag < 10000) {
            buffer.putByte(pos++, (byte) ('0' + tag / 1000));
            buffer.putByte(pos++, (byte) ('0' + (tag / 100) % 10));
            buffer.putByte(pos++, (byte) ('0' + (tag / 10) % 10));
            buffer.putByte(pos++, (byte) ('0' + tag % 10));
        } else {
            // 5+ digit tags
            String tagStr = String.valueOf(tag);
            for (int i = 0; i < tagStr.length(); i++) {
                buffer.putByte(pos++, (byte) tagStr.charAt(i));
            }
        }
        buffer.putByte(pos++, EQUALS);
        return pos;
    }

    /**
     * Write a field (tag=value|) during header template construction.
     *
     * @return the new position after writing
     */
    private static int putField(MutableDirectBuffer buffer, int pos, int tag, String value) {
        pos = putTag(buffer, pos, tag);
        for (int i = 0, n = value.length(); i < n; i++) {
            buffer.putByte(pos++, (byte) value.charAt(i));
        }
        buffer.putByte(pos++, SOH);
        return pos;
    }

    private static int putFill(MutableDirectBuffer buffer, int pos, char c, int count) {
        for (int i = 0; i < count; i++) {
            buffer.putByte(pos++, (byte) c);
        }
        return pos;
    }

    private static int sum(DirectBuffer buffer, int pos, int length) {
        int s = 0;
        for (int i = pos, end = pos + length; i < end; i++) {
            s += buffer.getByte(i) & 0xFF;
        }
        return s;
    }

    /**
     * Ensure the buffer has capacity for additional bytes.
     */
    private void ensureCapacity(int required) {
        if (required > bufferOffset + maxLength) {
            throw new IllegalStateException("Message exceeds maximum length: " +
                    (required - bufferOffset) + " > " + maxLength);
        }
    }

    /**
     * Encode an integer to the buffer.
     *
     * @return the new position after encoding
     */
    private int encodeInt(int value, int pos) {
        if (value < 0) {
            buffer.putByte(pos++, (byte) '-');
            value = -value;
        }

        if (value == 0) {
            buffer.putByte(pos++, (byte) '0');
            return pos;
        }

        // Find number of digits
        int digits = FastIntEncoder.digitCount(value);
        int endPos = pos + digits;

        // Write digits from right to left
        int writePos = endPos - 1;
        while (value > 0) {
            buffer.putByte(writePos--, (byte) ('0' + value % 10));
            value /= 10;
        }

        return endPos;
    }

    /**
     * Encode a long to the buffer.
     *
     * @return the new position after encoding
     */
    private int encodeLong(long value, int pos) {
        if (value < 0) {
            buffer.putByte(pos++, (byte) '-');
            value = -value;
        }

        if (value == 0) {
            buffer.putByte(pos++, (byte) '0');
            return pos;
        }

        // Find number of digits
        int digits = FastIntEncoder.digitCount(value);
        int endPos = pos + digits;

        // Write digits from right to left
        int writePos = endPos - 1;
        while (value > 0) {
            buffer.putByte(writePos--, (byte) ('0' + value % 10));
            value /= 10;
        }

        return endPos;
    }

    /**
     * Encode a double to the buffer with specified decimal places.
     *
     * @return the new position after encoding
     */
    private int encodeDouble(double value, int pos, int decimalPlaces) {
        if (value < 0) {
            buffer.putByte(pos++, (byte) '-');
            value = -value;
        }

        // Scale to get the decimal places
        long multiplier = 1;
        for (int i = 0; i < decimalPlaces; i++) {
            multiplier *= 10;
        }

        // Round and convert to long
        long scaled = Math.round(value * multiplier);

        // Extract integer and fractional parts
        long intPart = scaled / multiplier;
        long fracPart = scaled % multiplier;

        // Write integer part
        if (intPart == 0) {
            buffer.putByte(pos++, (byte) '0');
        } else {
            pos = encodeLong(intPart, pos);
        }

        // Write decimal point and fractional part
        if (decimalPlaces > 0) {
            buffer.putByte(pos++, (byte) '.');
            // Write fractional part with leading zeros
            pos = encodeIntFixedWidthReturn(fracPart, pos, decimalPlaces);
        }

        return pos;
    }

    /**
     * Encode an integer with fixed width, zero-padded.
     */
    private void encodeIntFixedWidth(int value, int pos, int width) {
        // Fill with '0' padding first
        for (int i = 0; i < width; i++) {
            buffer.putByte(pos + i, (byte) '0');
        }

        // Write digits from right to left
        int writePos = pos + width - 1;
        if (value == 0) {
            buffer.putByte(writePos, (byte) '0');
        } else {
            while (value > 0 && writePos >= pos) {
                buffer.putByte(writePos--, (byte) ('0' + value % 10));
                value /= 10;
            }
        }
    }

    /**
     * Encode a long with fixed width, zero-padded, returning new position.
     */
    private int encodeIntFixedWidthReturn(long value, int pos, int width) {
        // Fill with '0' padding first
        for (int i = 0; i < width; i++) {
            buffer.putByte(pos + i, (byte) '0');
        }

        // Write digits from right to left
        int writePos = pos + width - 1;
        if (value == 0) {
            buffer.putByte(writePos, (byte) '0');
        } else {
            while (value > 0 && writePos >= pos) {
                buffer.putByte(writePos--, (byte) ('0' + value % 10));
                value /= 10;
            }
        }

        return pos + width;
    }

    /**
     * Encode sending time into the pre-allocated slot.
     */
    private void encodeSendingTime(long epochMillis) {
        // Use FastTimestampEncoder logic but write directly to our buffer
        // Calculate day boundary
        long millisPerDay = 24L * 60 * 60 * 1000;
        long dayMillis = (epochMillis / millisPerDay) * millisPerDay;

        // Get date components using Instant (thread-safe)
        java.time.Instant instant = java.time.Instant.ofEpochMilli(epochMillis);
        java.time.ZonedDateTime zdt = instant.atZone(java.time.ZoneOffset.UTC);
        int year = zdt.getYear();
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();

        int pos = sendingTimeValuePos;

        // YYYY
        buffer.putByte(pos++, (byte) ('0' + year / 1000));
        buffer.putByte(pos++, (byte) ('0' + (year / 100) % 10));
        buffer.putByte(pos++, (byte) ('0' + (year / 10) % 10));
        buffer.putByte(pos++, (byte) ('0' + year % 10));

        // MM
        buffer.putByte(pos++, (byte) ('0' + month / 10));
        buffer.putByte(pos++, (byte) ('0' + month % 10));

        // DD
        buffer.putByte(pos++, (byte) ('0' + day / 10));
        buffer.putByte(pos++, (byte) ('0' + day % 10));

        buffer.putByte(pos++, (byte) '-');

        // Calculate time within day
        long millisInDay = epochMillis - dayMillis;
        if (millisInDay < 0) {
            millisInDay += millisPerDay;
        }

        long millisPerHour = 60L * 60 * 1000;
        long millisPerMinute = 60L * 1000;
        long millisPerSecond = 1000L;

        int hours = (int) (millisInDay / millisPerHour);
        millisInDay %= millisPerHour;

        int minutes = (int) (millisInDay / millisPerMinute);
        millisInDay %= millisPerMinute;

        int seconds = (int) (millisInDay / millisPerSecond);
        int millis = (int) (millisInDay % millisPerSecond);

        // HH
        buffer.putByte(pos++, (byte) ('0' + hours / 10));
        buffer.putByte(pos++, (byte) ('0' + hours % 10));

        buffer.putByte(pos++, (byte) ':');

        // MM
        buffer.putByte(pos++, (byte) ('0' + minutes / 10));
        buffer.putByte(pos++, (byte) ('0' + minutes % 10));

        buffer.putByte(pos++, (byte) ':');

        // SS
        buffer.putByte(pos++, (byte) ('0' + seconds / 10));
        buffer.putByte(pos++, (byte) ('0' + seconds % 10));

        buffer.putByte(pos++, (byte) '.');

        // sss
        buffer.putByte(pos++, (byte) ('0' + millis / 100));
        buffer.putByte(pos++, (byte) ('0' + (millis / 10) % 10));
        buffer.putByte(pos, (byte) ('0' + millis % 10));
    }
}
