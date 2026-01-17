package com.fixengine.message;

import java.nio.ByteBuffer;

/**
 * FIX message parser for extracting complete messages from a byte stream.
 *
 * <p>This class handles the framing of FIX messages, extracting complete messages
 * from a potentially incomplete byte stream. It handles:</p>
 * <ul>
 *   <li>Finding the BeginString (tag 8)</li>
 *   <li>Parsing the BodyLength (tag 9)</li>
 *   <li>Validating the CheckSum (tag 10)</li>
 *   <li>Handling partial messages</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * FixReader reader = new FixReader();
 * FixMessage msg = new FixMessage();
 *
 * while (buffer.hasRemaining()) {
 *     int result = reader.read(buffer, msg);
 *     if (result > 0) {
 *         // Complete message available in msg
 *         processMessage(msg);
 *     } else if (result == 0) {
 *         // Need more data
 *         break;
 *     } else {
 *         // Error - invalid message
 *         handleError();
 *     }
 * }
 * }</pre>
 */
public class FixReader {

    private static final byte SOH = FixMessage.SOH;
    private static final byte EQUALS = FixMessage.EQUALS;

    // BeginString prefix: "8=FIX"
    private static final byte[] BEGIN_STRING_PREFIX = {'8', '=', 'F', 'I', 'X'};

    // State
    private boolean inMessage = false;
    private int expectedLength = -1;
    private int bodyStart = -1;
    private int checksumStart = -1;

    // Buffer for accumulating incoming data
    private ByteBuffer accumulationBuffer;
    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    /**
     * Create a new FixReader.
     */
    public FixReader() {
        this.accumulationBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Add incoming data to the reader's internal buffer.
     * Use with readMessage() for convenient message extraction.
     *
     * @param data the incoming data
     */
    public void addData(ByteBuffer data) {
        // Ensure capacity
        if (accumulationBuffer.remaining() < data.remaining()) {
            // Grow buffer
            int newCapacity = Math.max(
                accumulationBuffer.capacity() * 2,
                accumulationBuffer.position() + data.remaining()
            );
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            accumulationBuffer.flip();
            newBuffer.put(accumulationBuffer);
            accumulationBuffer = newBuffer;
        }
        accumulationBuffer.put(data);
    }

    /**
     * Try to read a complete message from the accumulated data.
     *
     * @return a complete FixMessage, or null if no complete message is available
     */
    public FixMessage readMessage() {
        if (accumulationBuffer.position() == 0) {
            return null;
        }

        accumulationBuffer.flip();

        FixMessage message = new FixMessage();
        int result = read(accumulationBuffer, message);

        if (result > 0) {
            // Successfully read a message, compact remaining data
            accumulationBuffer.compact();
            return message;
        } else {
            // No complete message, restore position for more data
            accumulationBuffer.position(accumulationBuffer.limit());
            accumulationBuffer.limit(accumulationBuffer.capacity());
            return null;
        }
    }

    /**
     * Reset the reader state.
     */
    public void reset() {
        inMessage = false;
        expectedLength = -1;
        bodyStart = -1;
        checksumStart = -1;
    }

    /**
     * Try to read a complete FIX message from the buffer.
     *
     * @param buffer the buffer containing incoming data (position at start of unread data)
     * @param message the message to populate with the parsed message
     * @return positive value = message length (message is complete),
     *         0 = need more data,
     *         negative value = error
     */
    public int read(ByteBuffer buffer, FixMessage message) {
        int startPos = buffer.position();
        int available = buffer.remaining();

        if (available < 20) {
            // Minimum FIX message is at least ~20 bytes
            return 0;
        }

        byte[] data = new byte[available];
        buffer.mark();
        buffer.get(data);
        buffer.reset();

        // Find BeginString (8=FIX...)
        int msgStart = findBeginString(data, 0, available);
        if (msgStart < 0) {
            // No valid message start found - discard data up to this point
            buffer.position(buffer.limit());
            return -1;
        }

        // Skip any garbage before the message
        if (msgStart > 0) {
            buffer.position(startPos + msgStart);
            // Recursively try again with adjusted buffer
            return read(buffer, message);
        }

        // Parse BodyLength (9=XXX)
        int bodyLengthStart = findTag(data, 0, available, 9);
        if (bodyLengthStart < 0) {
            return 0; // Need more data
        }

        int bodyLengthValue = parseIntValue(data, bodyLengthStart, available);
        if (bodyLengthValue < 0) {
            return 0; // Need more data
        }

        // Find where body starts (after 9=XXX<SOH>)
        int bodyStartPos = bodyLengthStart;
        while (bodyStartPos < available && data[bodyStartPos] != SOH) {
            bodyStartPos++;
        }
        bodyStartPos++; // Skip SOH

        if (bodyStartPos >= available) {
            return 0; // Need more data
        }

        // Calculate expected total message length
        // Message = BeginString + BodyLength + Body + CheckSum
        // CheckSum is always "10=XXX<SOH>" = 7 bytes
        int expectedEnd = bodyStartPos + bodyLengthValue + 7;

        if (expectedEnd > available) {
            return 0; // Need more data
        }

        // Validate CheckSum position
        int checksumPos = bodyStartPos + bodyLengthValue;
        if (checksumPos + 7 > available) {
            return 0; // Need more data
        }

        // Verify "10=" at expected position
        if (data[checksumPos] != '1' || data[checksumPos + 1] != '0' || data[checksumPos + 2] != '=') {
            // CheckSum not at expected position - invalid message
            buffer.position(startPos + 1);
            return -2;
        }

        // Verify checksum value
        int calculatedChecksum = 0;
        for (int i = 0; i < checksumPos; i++) {
            calculatedChecksum += (data[i] & 0xFF);
        }
        calculatedChecksum = calculatedChecksum % 256;

        int receivedChecksum = parseChecksum(data, checksumPos + 3);
        if (receivedChecksum < 0) {
            buffer.position(startPos + 1);
            return -3; // Invalid checksum format
        }

        if (calculatedChecksum != receivedChecksum) {
            // Checksum mismatch
            buffer.position(startPos + 1);
            return -4;
        }

        // Find actual message end (SOH after checksum)
        int messageEnd = checksumPos + 3;
        while (messageEnd < available && data[messageEnd] != SOH) {
            messageEnd++;
        }
        messageEnd++; // Include final SOH

        // Valid complete message
        message.wrap(data, 0, messageEnd);

        // Advance buffer position past this message
        buffer.position(startPos + messageEnd);

        return messageEnd;
    }

    /**
     * Find the start of a FIX message (8=FIX...).
     */
    private int findBeginString(byte[] data, int offset, int length) {
        for (int i = offset; i <= length - BEGIN_STRING_PREFIX.length; i++) {
            boolean match = true;
            for (int j = 0; j < BEGIN_STRING_PREFIX.length; j++) {
                if (data[i + j] != BEGIN_STRING_PREFIX[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find a tag in the message and return the position of its value.
     */
    private int findTag(byte[] data, int offset, int length, int tag) {
        int pos = offset;
        while (pos < length) {
            int tagStart = pos;

            // Parse tag number
            int parsedTag = 0;
            while (pos < length && data[pos] != EQUALS && data[pos] != SOH) {
                if (data[pos] >= '0' && data[pos] <= '9') {
                    parsedTag = parsedTag * 10 + (data[pos] - '0');
                }
                pos++;
            }

            if (pos >= length || data[pos] != EQUALS) {
                return -1;
            }
            pos++; // Skip '='

            if (parsedTag == tag) {
                return pos; // Return position of value
            }

            // Skip to next SOH
            while (pos < length && data[pos] != SOH) {
                pos++;
            }
            pos++; // Skip SOH
        }
        return -1;
    }

    /**
     * Parse an integer value starting at the given position.
     */
    private int parseIntValue(byte[] data, int offset, int length) {
        int value = 0;
        int pos = offset;

        while (pos < length && data[pos] != SOH) {
            byte b = data[pos];
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            } else if (b != '-' && b != '+') {
                break;
            }
            pos++;
        }

        return value;
    }

    /**
     * Parse the 3-digit checksum value.
     */
    private int parseChecksum(byte[] data, int offset) {
        if (offset + 3 > data.length) {
            return -1;
        }

        int value = 0;
        for (int i = 0; i < 3; i++) {
            byte b = data[offset + i];
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            } else {
                return -1;
            }
        }
        return value;
    }
}
