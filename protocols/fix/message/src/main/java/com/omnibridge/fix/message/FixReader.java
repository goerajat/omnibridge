package com.omnibridge.fix.message;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

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
 * <p>This implementation works directly with DirectBuffer without creating
 * temporary byte arrays, minimizing allocations for high-performance parsing.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * FixReader reader = new FixReader();
 * IncomingFixMessage msg = new IncomingFixMessage();
 *
 * reader.addData(buffer, offset, length);
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

    /**
     * Number of bytes needed to read the FIX header and determine message length.
     * This is enough for: "8=FIX.4.2|9=XXXXX|" (BeginString + BodyLength)
     */
    public static final int HEADER_READ_SIZE = 25;

    // State
    private boolean inMessage = false;
    private int expectedLength = -1;
    private int bodyStart = -1;
    private int checksumStart = -1;

    // Total expected message length (set after parsing header)
    private int expectedTotalMessageLength = -1;

    // Buffer for accumulating incoming data (uses UnsafeBuffer for efficient access)
    private MutableDirectBuffer accumulationBuffer;
    private int accumulationPosition;
    // Start of the bytes not yet returned as a message; everything before it has been read
    private int readPosition;
    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    // Latency stage timing: when on, the time each message was found complete and valid
    private boolean frameTiming;
    private long lastFramedNanos;

    /**
     * Create a new FixReader.
     */
    public FixReader() {
        this(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Create a FixReader whose buffer starts at the given size. If it holds the largest
     * partial message plus one read, it never has to grow.
     */
    public FixReader(int initialCapacity) {
        this.accumulationBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(initialCapacity));
        this.accumulationPosition = 0;
    }

    /**
     * Record {@link System#nanoTime()} when each message has been framed and its checksum
     * verified, just before its fields are parsed (off by default).
     */
    public void setFrameTiming(boolean frameTiming) {
        this.frameTiming = frameTiming;
    }

    /**
     * When the last message was framed; valid only with {@link #setFrameTiming} on.
     */
    public long getLastFramedNanos() {
        return lastFramedNanos;
    }

    /**
     * Get the number of bytes needed for the next read operation.
     *
     * <p>This method enables efficient network I/O by allowing the caller to
     * read exactly the right number of bytes:</p>
     * <ul>
     *   <li>If no message is in progress, returns {@link #HEADER_READ_SIZE} bytes
     *       (enough to parse the header and determine message length)</li>
     *   <li>If the header has been parsed and we know the message length,
     *       returns the remaining bytes needed to complete the message</li>
     * </ul>
     *
     * @return the number of bytes needed for the next read
     */
    public int getBytesNeeded() {
        int currentBytes = accumulationPosition - readPosition;

        if (expectedTotalMessageLength > 0) {
            // We know the total message length, return remaining bytes needed
            int remaining = expectedTotalMessageLength - currentBytes;
            // If message is complete (remaining <= 0), return HEADER_READ_SIZE
            // to allow reading the next message. Never return 0 as that would
            // prevent the network layer from reading any data.
            return remaining > 0 ? remaining : HEADER_READ_SIZE;
        }

        // No message length determined yet, request header bytes
        if (currentBytes < HEADER_READ_SIZE) {
            return HEADER_READ_SIZE - currentBytes;
        }

        // We have header bytes but haven't parsed the length yet
        // Try to parse the header to determine message length
        tryParseHeader();

        if (expectedTotalMessageLength > 0) {
            int remaining = expectedTotalMessageLength - currentBytes;
            // Same as above - never return 0
            return remaining > 0 ? remaining : HEADER_READ_SIZE;
        }

        // Couldn't parse header, request more header bytes
        return HEADER_READ_SIZE;
    }

    /**
     * Try to parse the header to determine the expected total message length.
     * This is called internally when we have enough bytes but haven't parsed yet.
     */
    private void tryParseHeader() {
        if (accumulationPosition - readPosition < 20) {
            return; // Not enough data for minimal header
        }

        int startPos = readPosition;
        int available = accumulationPosition - readPosition;

        // Find BeginString
        int msgStart = findBeginString(accumulationBuffer, startPos, available);
        if (msgStart < 0) {
            return;
        }

        // Parse BodyLength (9=XXX)
        int bodyLengthStart = findTag(accumulationBuffer, startPos, available, 9);
        if (bodyLengthStart < 0) {
            return;
        }

        int bodyLengthValue = parseIntValue(accumulationBuffer, bodyLengthStart, startPos + available);
        if (bodyLengthValue < 0) {
            return;
        }

        // Find where body starts (after 9=XXX<SOH>)
        int bodyStartPos = bodyLengthStart;
        while (bodyStartPos < startPos + available && accumulationBuffer.getByte(bodyStartPos) != SOH) {
            bodyStartPos++;
        }
        bodyStartPos++; // Skip SOH

        if (bodyStartPos >= startPos + available) {
            return;
        }

        // Calculate expected total message length
        // Message = BeginString + BodyLength + Body + CheckSum
        // CheckSum is always "10=XXX<SOH>" = 7 bytes
        expectedTotalMessageLength = bodyStartPos - startPos + bodyLengthValue + 7;
    }

    /**
     * Add incoming data to the reader's internal buffer.
     *
     * <p>Bytes already returned as messages are discarded here, so a message from
     * {@link #readIncomingMessage} stays valid until the next call to this method.</p>
     *
     * @param data the DirectBuffer containing incoming data
     * @param offset the offset in the buffer where data starts
     * @param length the number of bytes to add
     */
    public void addData(DirectBuffer data, int offset, int length) {
        discardRead();
        ensureCapacity(length);
        accumulationBuffer.putBytes(accumulationPosition, data, offset, length);
        accumulationPosition += length;
    }

    /**
     * Add incoming data from a ByteBuffer to the reader's internal buffer.
     * This is a convenience method for backward compatibility.
     *
     * @param data the incoming data
     * @deprecated Use {@link #addData(DirectBuffer, int, int)} instead
     */
    @Deprecated
    public void addData(ByteBuffer data) {
        int length = data.remaining();
        discardRead();
        ensureCapacity(length);
        // Copy from ByteBuffer to accumulation buffer
        for (int i = 0; i < length; i++) {
            accumulationBuffer.putByte(accumulationPosition + i, data.get());
        }
        accumulationPosition += length;
    }

    private void discardRead() {
        if (readPosition > 0) {
            int remaining = accumulationPosition - readPosition;
            if (remaining > 0) {
                accumulationBuffer.putBytes(0, accumulationBuffer, readPosition, remaining);
            }
            accumulationPosition = remaining;
            readPosition = 0;
        }
    }

    /**
     * Ensure the accumulation buffer has enough capacity.
     */
    private void ensureCapacity(int additionalBytes) {
        int required = accumulationPosition + additionalBytes;
        if (required > accumulationBuffer.capacity()) {
            // Grow buffer
            int newCapacity = Math.max(accumulationBuffer.capacity() * 2, required);
            MutableDirectBuffer newBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(newCapacity));
            newBuffer.putBytes(0, accumulationBuffer, 0, accumulationPosition);
            accumulationBuffer = newBuffer;
        }
    }

    /**
     * Try to read a complete message from the accumulated data into an IncomingFixMessage.
     *
     * <p>This is the preferred method for latency-optimized message processing.
     * The message should be released back to its pool after processing.</p>
     *
     * <p>The message is a view over the reader's buffer. It stays valid while further
     * messages are read from the same data, until the next {@link #addData} call.</p>
     *
     * @param message the IncomingFixMessage to populate (typically from a pool)
     * @return true if a message was successfully read, false if more data is needed
     */
    public boolean readIncomingMessage(IncomingFixMessage message) {
        int available = accumulationPosition - readPosition;
        if (available == 0) {
            return false;
        }

        int result = read(accumulationBuffer, readPosition, available, message);

        if (result > 0) {
            // Advance past the message (and any garbage skipped before it); no bytes move
            readPosition = message.getStartOffset() + result;
            if (readPosition == accumulationPosition) {
                readPosition = 0;
                accumulationPosition = 0;
            }
            expectedTotalMessageLength = -1;
            return true;
        }
        return false;
    }

    /**
     * Reset the reader state.
     */
    public void reset() {
        inMessage = false;
        expectedLength = -1;
        bodyStart = -1;
        checksumStart = -1;
        expectedTotalMessageLength = -1;
        // Clear the accumulation buffer - critical for reconnection scenarios
        // where old data from a previous connection should not be mixed with new data
        accumulationPosition = 0;
        readPosition = 0;
    }

    /**
     * Try to read a complete FIX message from the buffer into an IncomingFixMessage.
     *
     * @param buffer the DirectBuffer containing incoming data
     * @param offset the offset where data starts
     * @param length the number of bytes available
     * @param message the incoming message to populate
     * @return positive value = message length (message is complete),
     *         0 = need more data,
     *         negative value = error
     */
    public int read(DirectBuffer buffer, int offset, int length, IncomingFixMessage message) {
        int startPos = offset;
        int available = length;

        if (available < 20) {
            // Minimum FIX message is at least ~20 bytes
            return 0;
        }

        // Find BeginString (8=FIX...)
        int msgStart = findBeginString(buffer, startPos, available);
        if (msgStart < 0) {
            // No valid message start found - discard data up to this point
            return -1;
        }

        // Skip any garbage before the message
        if (msgStart > 0) {
            // Recursively try again with adjusted offset
            return read(buffer, startPos + msgStart, available - msgStart, message);
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
        while (bodyStartPos < startPos + available && buffer.getByte(bodyStartPos) != SOH) {
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
        if (buffer.getByte(checksumPos) != '1' || buffer.getByte(checksumPos + 1) != '0' || buffer.getByte(checksumPos + 2) != '=') {
            // CheckSum not at expected position - invalid message
            return -2;
        }

        // Verify checksum value
        int calculatedChecksum = 0;
        for (int i = startPos; i < checksumPos; i++) {
            calculatedChecksum += (buffer.getByte(i) & 0xFF);
        }
        calculatedChecksum = calculatedChecksum % 256;

        int receivedChecksum = parseChecksum(buffer, checksumPos + 3, startPos + available);
        if (receivedChecksum < 0) {
            return -3; // Invalid checksum format
        }

        if (calculatedChecksum != receivedChecksum) {
            // Checksum mismatch
            return -4;
        }

        // Find actual message end (SOH after checksum)
        int messageEnd = checksumPos + 3;
        while (messageEnd < startPos + available && buffer.getByte(messageEnd) != SOH) {
            messageEnd++;
        }
        messageEnd++; // Include final SOH

        int messageLength = messageEnd - startPos;

        // Valid complete message - wrap in IncomingFixMessage
        if (frameTiming) {
            lastFramedNanos = System.nanoTime();
        }
        message.wrap(buffer, startPos, messageLength);

        return messageLength;
    }

    /**
     * Find the start of a FIX message (8=FIX...) in the DirectBuffer.
     *
     * @param buffer the DirectBuffer to search
     * @param offset the starting position in the buffer
     * @param length the number of bytes to search
     * @return the offset (relative to start) of the message, or -1 if not found
     */
    private int findBeginString(DirectBuffer buffer, int offset, int length) {
        int searchEnd = offset + length - BEGIN_STRING_PREFIX.length;
        for (int i = offset; i <= searchEnd; i++) {
            boolean match = true;
            for (int j = 0; j < BEGIN_STRING_PREFIX.length; j++) {
                if (buffer.getByte(i + j) != BEGIN_STRING_PREFIX[j]) {
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
     * @param buffer the DirectBuffer to search
     * @param offset the starting position in the buffer
     * @param length the number of bytes to search
     * @param tag the tag number to find
     * @return the absolute position of the value, or -1 if not found
     */
    private int findTag(DirectBuffer buffer, int offset, int length, int tag) {
        int end = offset + length;
        int pos = offset;

        while (pos < end) {
            // Parse tag number
            int parsedTag = 0;
            while (pos < end && buffer.getByte(pos) != EQUALS && buffer.getByte(pos) != SOH) {
                byte b = buffer.getByte(pos);
                if (b >= '0' && b <= '9') {
                    parsedTag = parsedTag * 10 + (b - '0');
                }
                pos++;
            }

            if (pos >= end || buffer.getByte(pos) != EQUALS) {
                return -1;
            }
            pos++; // Skip '='

            if (parsedTag == tag) {
                return pos; // Return absolute position of value
            }

            // Skip to next SOH
            while (pos < end && buffer.getByte(pos) != SOH) {
                pos++;
            }
            pos++; // Skip SOH
        }
        return -1;
    }

    /**
     * Parse an integer value starting at the given absolute position.
     *
     * @param buffer the DirectBuffer
     * @param offset the absolute position to start parsing
     * @param limit the limit of valid data
     * @return the parsed integer value
     */
    private int parseIntValue(DirectBuffer buffer, int offset, int limit) {
        int value = 0;
        int pos = offset;

        while (pos < limit && buffer.getByte(pos) != SOH) {
            byte b = buffer.getByte(pos);
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
     * @param buffer the DirectBuffer
     * @param offset the absolute position to start parsing
     * @param limit the limit of valid data
     * @return the parsed checksum value, or -1 if invalid
     */
    private int parseChecksum(DirectBuffer buffer, int offset, int limit) {
        if (offset + 3 > limit) {
            return -1;
        }

        int value = 0;
        for (int i = 0; i < 3; i++) {
            byte b = buffer.getByte(offset + i);
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            } else {
                return -1;
            }
        }
        return value;
    }
}
