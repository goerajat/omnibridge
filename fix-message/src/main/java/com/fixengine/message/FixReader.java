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
 * <p>This implementation works directly with ByteBuffer without creating
 * temporary byte arrays, minimizing allocations for high-performance parsing.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * FixReader reader = new FixReader();
 * IncomingFixMessage msg = new IncomingFixMessage();
 *
 * reader.addData(buffer);
 * while (reader.readIncomingMessage(msg)) {
 *     // Complete message available in msg
 *     processMessage(msg);
 *     msg.reset();
 * }
 * }</pre>
 */
public class FixReader {

    private static final byte SOH = 0x01; // Field delimiter
    private static final byte EQUALS = '=';

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
     * Try to read a complete message from the accumulated data into an IncomingFixMessage.
     *
     * <p>This is the preferred method for latency-optimized message processing.
     * The message should be released back to its pool after processing.</p>
     *
     * @param message the IncomingFixMessage to populate (typically from a pool)
     * @return true if a message was successfully read, false if more data is needed
     */
    public boolean readIncomingMessage(IncomingFixMessage message) {
        if (accumulationBuffer.position() == 0) {
            return false;
        }

        accumulationBuffer.flip();

        int result = read(accumulationBuffer, message);

        if (result > 0) {
            // Successfully read a message, compact remaining data
            accumulationBuffer.compact();
            return true;
        } else {
            // No complete message, restore position for more data
            accumulationBuffer.position(accumulationBuffer.limit());
            accumulationBuffer.limit(accumulationBuffer.capacity());
            return false;
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
     * Try to read a complete FIX message from the buffer into an IncomingFixMessage.
     *
     * <p>This is the preferred method for latency-optimized message processing.</p>
     *
     * @param buffer the buffer containing incoming data (position at start of unread data)
     * @param message the incoming message to populate
     * @return positive value = message length (message is complete),
     *         0 = need more data,
     *         negative value = error
     */
    public int read(ByteBuffer buffer, IncomingFixMessage message) {
        return readInternal(buffer, (buf, msgLen) -> message.wrap(buf, msgLen));
    }

    /**
     * Functional interface for wrapping message data from ByteBuffer.
     */
    @FunctionalInterface
    private interface MessageWrapper {
        /**
         * Wrap message data from a ByteBuffer.
         *
         * @param buffer the ByteBuffer positioned at the start of the message
         * @param length the length of the message in bytes
         */
        void wrap(ByteBuffer buffer, int length);
    }

    /**
     * Internal read implementation shared by both FixMessage and IncomingFixMessage.
     * Works directly with ByteBuffer without creating temporary byte arrays.
     */
    private int readInternal(ByteBuffer buffer, MessageWrapper wrapper) {
        int startPos = buffer.position();
        int available = buffer.remaining();

        if (available < 20) {
            // Minimum FIX message is at least ~20 bytes
            return 0;
        }

        // Find BeginString (8=FIX...) - work directly with ByteBuffer
        int msgStart = findBeginString(buffer, startPos, available);
        if (msgStart < 0) {
            // No valid message start found - discard data up to this point
            buffer.position(buffer.limit());
            return -1;
        }

        // Skip any garbage before the message
        if (msgStart > 0) {
            buffer.position(startPos + msgStart);
            // Recursively try again with adjusted buffer
            return readInternal(buffer, wrapper);
        }

        // Parse BodyLength (9=XXX)
        int bodyLengthStart = findTag(buffer, startPos, available, 9);
        if (bodyLengthStart < 0) {
            return 0; // Need more data
        }

        int bodyLengthValue = parseIntValue(buffer, bodyLengthStart, startPos + available);
        if (bodyLengthValue < 0) {
            return 0; // Need more data
        }

        // Find where body starts (after 9=XXX<SOH>)
        int bodyStartPos = bodyLengthStart;
        while (bodyStartPos < startPos + available && buffer.get(bodyStartPos) != SOH) {
            bodyStartPos++;
        }
        bodyStartPos++; // Skip SOH

        if (bodyStartPos >= startPos + available) {
            return 0; // Need more data
        }

        // Calculate expected total message length
        // Message = BeginString + BodyLength + Body + CheckSum
        // CheckSum is always "10=XXX<SOH>" = 7 bytes
        int expectedEnd = bodyStartPos + bodyLengthValue + 7;

        if (expectedEnd > startPos + available) {
            return 0; // Need more data
        }

        // Validate CheckSum position
        int checksumPos = bodyStartPos + bodyLengthValue;
        if (checksumPos + 7 > startPos + available) {
            return 0; // Need more data
        }

        // Verify "10=" at expected position
        if (buffer.get(checksumPos) != '1' || buffer.get(checksumPos + 1) != '0' || buffer.get(checksumPos + 2) != '=') {
            // CheckSum not at expected position - invalid message
            buffer.position(startPos + 1);
            return -2;
        }

        // Verify checksum value
        int calculatedChecksum = 0;
        for (int i = startPos; i < checksumPos; i++) {
            calculatedChecksum += (buffer.get(i) & 0xFF);
        }
        calculatedChecksum = calculatedChecksum % 256;

        int receivedChecksum = parseChecksum(buffer, checksumPos + 3);
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
        while (messageEnd < startPos + available && buffer.get(messageEnd) != SOH) {
            messageEnd++;
        }
        messageEnd++; // Include final SOH

        int messageLength = messageEnd - startPos;

        // Valid complete message - wrap using the provided wrapper
        // Create a slice of the buffer for the message
        int savedLimit = buffer.limit();
        buffer.position(startPos);
        buffer.limit(messageEnd);
        wrapper.wrap(buffer, messageLength);

        // Restore limit and advance position past this message
        buffer.limit(savedLimit);
        buffer.position(messageEnd);

        return messageLength;
    }

    /**
     * Find the start of a FIX message (8=FIX...) in the ByteBuffer.
     *
     * @param buffer the ByteBuffer to search
     * @param offset the starting position in the buffer
     * @param length the number of bytes to search
     * @return the offset (relative to start) of the message, or -1 if not found
     */
    private int findBeginString(ByteBuffer buffer, int offset, int length) {
        int searchEnd = offset + length - BEGIN_STRING_PREFIX.length;
        for (int i = offset; i <= searchEnd; i++) {
            boolean match = true;
            for (int j = 0; j < BEGIN_STRING_PREFIX.length; j++) {
                if (buffer.get(i + j) != BEGIN_STRING_PREFIX[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i - offset; // Return relative offset
            }
        }
        return -1;
    }

    /**
     * Find a tag in the message and return the absolute position of its value.
     *
     * @param buffer the ByteBuffer to search
     * @param offset the starting position in the buffer
     * @param length the number of bytes to search
     * @param tag the tag number to find
     * @return the absolute position of the value, or -1 if not found
     */
    private int findTag(ByteBuffer buffer, int offset, int length, int tag) {
        int end = offset + length;
        int pos = offset;

        while (pos < end) {
            // Parse tag number
            int parsedTag = 0;
            while (pos < end && buffer.get(pos) != EQUALS && buffer.get(pos) != SOH) {
                byte b = buffer.get(pos);
                if (b >= '0' && b <= '9') {
                    parsedTag = parsedTag * 10 + (b - '0');
                }
                pos++;
            }

            if (pos >= end || buffer.get(pos) != EQUALS) {
                return -1;
            }
            pos++; // Skip '='

            if (parsedTag == tag) {
                return pos; // Return absolute position of value
            }

            // Skip to next SOH
            while (pos < end && buffer.get(pos) != SOH) {
                pos++;
            }
            pos++; // Skip SOH
        }
        return -1;
    }

    /**
     * Parse an integer value starting at the given absolute position.
     *
     * @param buffer the ByteBuffer
     * @param offset the absolute position to start parsing
     * @param limit the limit of valid data
     * @return the parsed integer value
     */
    private int parseIntValue(ByteBuffer buffer, int offset, int limit) {
        int value = 0;
        int pos = offset;

        while (pos < limit && buffer.get(pos) != SOH) {
            byte b = buffer.get(pos);
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
     *
     * @param buffer the ByteBuffer
     * @param offset the absolute position to start parsing
     * @return the parsed checksum value, or -1 if invalid
     */
    private int parseChecksum(ByteBuffer buffer, int offset) {
        if (offset + 3 > buffer.limit()) {
            return -1;
        }

        int value = 0;
        for (int i = 0; i < 3; i++) {
            byte b = buffer.get(offset + i);
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            } else {
                return -1;
            }
        }
        return value;
    }
}
