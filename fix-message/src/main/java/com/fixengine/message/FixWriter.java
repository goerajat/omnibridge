package com.fixengine.message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Builder for creating FIX messages.
 * Writes fields directly to a FixMessage buffer.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * FixMessage msg = new FixMessage();
 * FixWriter writer = new FixWriter(msg);
 * writer.startMessage("FIX.4.4", "D")
 *       .setSenderCompId("SENDER")
 *       .setTargetCompId("TARGET")
 *       .setMsgSeqNum(1)
 *       .setString(FixTags.ClOrdID, "ORDER-001")
 *       .setString(FixTags.Symbol, "AAPL")
 *       .setChar(FixTags.Side, FixTags.Side.Buy)
 *       .setInt(FixTags.OrderQty, 100)
 *       .setDouble(FixTags.Price, 150.25)
 *       .finishMessage();
 * }</pre>
 */
public class FixWriter {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static final byte SOH = FixMessage.SOH;
    private static final byte EQUALS = FixMessage.EQUALS;

    private final FixMessage message;
    private final byte[] buffer;

    private int bodyStart;
    private int position;
    private String beginString;
    private String msgType;

    /**
     * Create a new FixWriter that writes to the given message.
     */
    public FixWriter(FixMessage message) {
        this.message = message;
        this.buffer = message.getBuffer();
    }

    /**
     * Create a new FixWriter with an internal message buffer.
     */
    public FixWriter() {
        this(new FixMessage());
    }

    // ==================== Alias methods for FixSession compatibility ====================

    /**
     * Clear the writer for reuse (alias for starting fresh).
     */
    public void clear() {
        message.reset();
        position = 0;
    }

    /**
     * Begin a new message (alias for startMessage).
     */
    public FixWriter beginMessage(String beginString, String msgType) {
        return startMessage(beginString, msgType);
    }

    /**
     * Add a field (alias for setString).
     */
    public FixWriter addField(int tag, String value) {
        return setString(tag, value);
    }

    /**
     * Add a field (alias for setInt).
     */
    public FixWriter addField(int tag, int value) {
        return setInt(tag, value);
    }

    /**
     * Add a field (alias for setLong).
     */
    public FixWriter addField(int tag, long value) {
        return setLong(tag, value);
    }

    /**
     * Finish the message and return raw bytes.
     */
    public byte[] finish() {
        finishMessage();
        byte[] result = new byte[position];
        System.arraycopy(buffer, 0, result, 0, position);
        return result;
    }

    /**
     * Start a new message with the given FIX version and message type.
     *
     * @param beginString the FIX version (e.g., "FIX.4.4")
     * @param msgType the message type (e.g., "D" for NewOrderSingle)
     * @return this writer for chaining
     */
    public FixWriter startMessage(String beginString, String msgType) {
        message.reset();
        this.beginString = beginString;
        this.msgType = msgType;
        this.position = 0;

        // Write BeginString - will be at start
        writeTag(FixTags.BeginString);
        writeValue(beginString);

        // Reserve space for BodyLength (we'll fill it in at finish)
        // Format: 9=XXXX|  (4 digits is sufficient for most messages)
        writeTag(FixTags.BodyLength);
        int bodyLengthPos = position;
        writeBytes("0000".getBytes(StandardCharsets.US_ASCII)); // Placeholder
        buffer[position++] = SOH;

        bodyStart = position;

        // Write MsgType
        writeTag(FixTags.MsgType);
        writeValue(msgType);

        return this;
    }

    /**
     * Set the SenderCompID field.
     */
    public FixWriter setSenderCompId(String value) {
        return setString(FixTags.SenderCompID, value);
    }

    /**
     * Set the TargetCompID field.
     */
    public FixWriter setTargetCompId(String value) {
        return setString(FixTags.TargetCompID, value);
    }

    /**
     * Set the MsgSeqNum field.
     */
    public FixWriter setMsgSeqNum(int value) {
        return setInt(FixTags.MsgSeqNum, value);
    }

    /**
     * Set the SendingTime field to the current UTC time.
     */
    public FixWriter setSendingTime() {
        return setString(FixTags.SendingTime, TIMESTAMP_FORMAT.format(Instant.now()));
    }

    /**
     * Set a string field.
     */
    public FixWriter setString(int tag, String value) {
        if (value != null) {
            writeTag(tag);
            writeValue(value);
        }
        return this;
    }

    /**
     * Set an integer field.
     */
    public FixWriter setInt(int tag, int value) {
        writeTag(tag);
        writeIntValue(value);
        buffer[position++] = SOH;
        return this;
    }

    /**
     * Set a long field.
     */
    public FixWriter setLong(int tag, long value) {
        writeTag(tag);
        writeLongValue(value);
        buffer[position++] = SOH;
        return this;
    }

    /**
     * Set a double field.
     */
    public FixWriter setDouble(int tag, double value) {
        return setString(tag, Double.toString(value));
    }

    /**
     * Set a double field with specific decimal places.
     */
    public FixWriter setDouble(int tag, double value, int decimalPlaces) {
        return setString(tag, String.format("%." + decimalPlaces + "f", value));
    }

    /**
     * Set a char field.
     */
    public FixWriter setChar(int tag, char value) {
        writeTag(tag);
        buffer[position++] = (byte) value;
        buffer[position++] = SOH;
        return this;
    }

    /**
     * Set a boolean field (Y/N).
     */
    public FixWriter setBoolean(int tag, boolean value) {
        return setChar(tag, value ? 'Y' : 'N');
    }

    /**
     * Set the PossDupFlag field.
     */
    public FixWriter setPossDupFlag(boolean value) {
        return setBoolean(FixTags.PossDupFlag, value);
    }

    /**
     * Copy raw bytes to the message (for advanced use).
     */
    public FixWriter writeRaw(byte[] data, int offset, int length) {
        ensureCapacity(position + length);
        System.arraycopy(data, offset, buffer, position, length);
        position += length;
        return this;
    }

    /**
     * Finish the message by calculating and writing BodyLength and CheckSum.
     *
     * @return the FixMessage with complete content
     */
    public FixMessage finishMessage() {
        int bodyEnd = position;
        int bodyLength = bodyEnd - bodyStart;

        // Update BodyLength field
        // Find the position after "9="
        int blPos = 0;
        while (blPos < bodyStart && !(buffer[blPos] == '9' && buffer[blPos + 1] == '=')) {
            blPos++;
        }
        if (blPos < bodyStart) {
            blPos += 2; // Skip "9="
            // Write the body length (4 digits, zero-padded)
            String blStr = String.format("%04d", bodyLength);
            byte[] blBytes = blStr.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(blBytes, 0, buffer, blPos, 4);
        }

        // Calculate CheckSum
        int checksum = 0;
        for (int i = 0; i < position; i++) {
            checksum += (buffer[i] & 0xFF);
        }
        checksum = checksum % 256;

        // Write CheckSum
        writeTag(FixTags.CheckSum);
        buffer[position++] = (byte) ('0' + (checksum / 100));
        buffer[position++] = (byte) ('0' + ((checksum / 10) % 10));
        buffer[position++] = (byte) ('0' + (checksum % 10));
        buffer[position++] = SOH;

        message.setLength(position);
        return message;
    }

    // ==================== Private Helper Methods ====================

    private void ensureCapacity(int required) {
        message.ensureCapacity(required);
    }

    private void writeTag(int tag) {
        ensureCapacity(position + 10);
        if (tag < 10) {
            buffer[position++] = (byte) ('0' + tag);
        } else if (tag < 100) {
            buffer[position++] = (byte) ('0' + tag / 10);
            buffer[position++] = (byte) ('0' + tag % 10);
        } else if (tag < 1000) {
            buffer[position++] = (byte) ('0' + tag / 100);
            buffer[position++] = (byte) ('0' + (tag / 10) % 10);
            buffer[position++] = (byte) ('0' + tag % 10);
        } else {
            buffer[position++] = (byte) ('0' + tag / 1000);
            buffer[position++] = (byte) ('0' + (tag / 100) % 10);
            buffer[position++] = (byte) ('0' + (tag / 10) % 10);
            buffer[position++] = (byte) ('0' + tag % 10);
        }
        buffer[position++] = EQUALS;
    }

    private void writeValue(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        ensureCapacity(position + bytes.length + 1);
        System.arraycopy(bytes, 0, buffer, position, bytes.length);
        position += bytes.length;
        buffer[position++] = SOH;
    }

    private void writeBytes(byte[] bytes) {
        ensureCapacity(position + bytes.length);
        System.arraycopy(bytes, 0, buffer, position, bytes.length);
        position += bytes.length;
    }

    private void writeIntValue(int value) {
        if (value < 0) {
            buffer[position++] = '-';
            value = -value;
        }

        if (value == 0) {
            buffer[position++] = '0';
            return;
        }

        // Find number of digits
        int temp = value;
        int digits = 0;
        while (temp > 0) {
            digits++;
            temp /= 10;
        }

        position += digits;
        int pos = position - 1;
        while (value > 0) {
            buffer[pos--] = (byte) ('0' + value % 10);
            value /= 10;
        }
    }

    private void writeLongValue(long value) {
        if (value < 0) {
            buffer[position++] = '-';
            value = -value;
        }

        if (value == 0) {
            buffer[position++] = '0';
            return;
        }

        long temp = value;
        int digits = 0;
        while (temp > 0) {
            digits++;
            temp /= 10;
        }

        position += digits;
        int pos = position - 1;
        while (value > 0) {
            buffer[pos--] = (byte) ('0' + value % 10);
            value /= 10;
        }
    }
}
