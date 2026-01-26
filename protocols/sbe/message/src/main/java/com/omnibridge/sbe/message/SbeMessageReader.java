package com.omnibridge.sbe.message;

import org.agrona.DirectBuffer;

/**
 * Base class for reading SBE messages from a buffer.
 * <p>
 * Subclasses implement protocol-specific message reading by providing
 * flyweight message instances for each message type. Messages are reused
 * (pooled) to avoid allocation during message processing.
 * <p>
 * Usage pattern:
 * <pre>
 * SbeMessageReader reader = new ILink3MessageReader();
 * SbeMessage msg = reader.read(buffer, offset, length);
 * if (msg != null) {
 *     switch (msg.getMessageType().getTemplateId()) {
 *         case 522: // ExecutionReport
 *             handleExecutionReport((ExecutionReportMessage) msg);
 *             break;
 *     }
 * }
 * </pre>
 *
 * @see SbeMessagePool
 * @see SbeMessageFactory
 */
public abstract class SbeMessageReader {

    /** Cached header for peeking at message metadata */
    protected final SbeMessageHeader headerDecoder = new SbeMessageHeader();

    /**
     * Reads a message from the buffer.
     * <p>
     * The returned message is a flyweight that wraps the buffer - it does not
     * copy data. The message instance is reused, so callers should not hold
     * references beyond the current processing scope.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message within the buffer
     * @param length the available length (may be larger than actual message)
     * @return the parsed message, or null if the message type is unknown
     */
    public abstract SbeMessage read(DirectBuffer buffer, int offset, int length);

    /**
     * Peeks at the template ID without fully parsing the message.
     * Useful for determining message type before allocating resources.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @return the template ID
     */
    public int peekTemplateId(DirectBuffer buffer, int offset) {
        headerDecoder.wrap(buffer, offset);
        return headerDecoder.templateId();
    }

    /**
     * Peeks at the schema ID.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @return the schema ID
     */
    public int peekSchemaId(DirectBuffer buffer, int offset) {
        headerDecoder.wrap(buffer, offset);
        return headerDecoder.schemaId();
    }

    /**
     * Peeks at the schema version.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @return the schema version
     */
    public int peekVersion(DirectBuffer buffer, int offset) {
        headerDecoder.wrap(buffer, offset);
        return headerDecoder.version();
    }

    /**
     * Peeks at the block length.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @return the block length
     */
    public int peekBlockLength(DirectBuffer buffer, int offset) {
        headerDecoder.wrap(buffer, offset);
        return headerDecoder.blockLength();
    }

    /**
     * Gets the minimum message length based on the header.
     * This includes header size + block length but not groups/var data.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @return the minimum message length
     */
    public int getMinimumMessageLength(DirectBuffer buffer, int offset) {
        headerDecoder.wrap(buffer, offset);
        return SbeMessageHeader.ENCODED_LENGTH + headerDecoder.blockLength();
    }

    /**
     * Gets the expected total message length including groups and var data.
     * <p>
     * For fixed-size messages, this returns header + block length.
     * For variable-size messages, implementations must parse the message
     * structure to determine the actual length.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @param availableLength the available bytes in the buffer
     * @return the expected message length, or -1 if more data is needed
     */
    public int getExpectedLength(DirectBuffer buffer, int offset, int availableLength) {
        if (availableLength < SbeMessageHeader.ENCODED_LENGTH) {
            return -1; // Need more data for header
        }

        headerDecoder.wrap(buffer, offset);
        int minLength = SbeMessageHeader.ENCODED_LENGTH + headerDecoder.blockLength();

        if (availableLength < minLength) {
            return -1; // Need more data for message body
        }

        // Subclasses override for variable-length messages
        return calculateTotalLength(buffer, offset, availableLength);
    }

    /**
     * Calculates the total message length including repeating groups and var data.
     * <p>
     * Default implementation returns header + block length.
     * Subclasses override for messages with groups or variable-length fields.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @param availableLength the available bytes
     * @return the total message length
     */
    protected int calculateTotalLength(DirectBuffer buffer, int offset, int availableLength) {
        return SbeMessageHeader.ENCODED_LENGTH + headerDecoder.blockLength();
    }

    /**
     * Gets the message type for a given template ID.
     *
     * @param templateId the template ID
     * @return the message type, or null if unknown
     */
    public abstract SbeMessageType getMessageType(int templateId);

    /**
     * Gets the schema this reader handles.
     *
     * @return the schema
     */
    public abstract SbeSchema getSchema();
}
