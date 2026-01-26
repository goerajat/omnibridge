package com.omnibridge.ilink3.message;

import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.sbe.message.SbeMessageHeader;
import com.omnibridge.sbe.message.SbeMessageReader;
import com.omnibridge.sbe.message.SbeMessageType;
import com.omnibridge.sbe.message.SbeSchema;
import org.agrona.DirectBuffer;

/**
 * Reader for iLink 3 messages.
 * <p>
 * Handles the 2-byte framing header that precedes the SBE header in iLink 3.
 */
public class ILink3MessageReader extends SbeMessageReader {

    private static final ThreadLocal<ILink3MessagePool> POOL =
            ThreadLocal.withInitial(ILink3MessagePool::new);

    /**
     * Reads a message from the buffer.
     * <p>
     * The buffer should start at the framing header (2-byte length prefix).
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the framing header
     * @param length the available length
     * @return the parsed message, or null if unknown
     */
    @Override
    public SbeMessage read(DirectBuffer buffer, int offset, int length) {
        // Read SBE header (after framing header)
        int sbeOffset = offset + ILink3Message.FRAMING_HEADER_SIZE;
        headerDecoder.wrap(buffer, sbeOffset);
        int templateId = headerDecoder.templateId();

        // Get pooled message instance
        ILink3MessagePool pool = POOL.get();
        SbeMessage message = pool.getMessageByTemplateId(templateId);

        if (message != null) {
            // Wrap the message (wrapForReading handles framing header offset)
            message.wrapForReading(buffer, offset, length);
        }

        return message;
    }

    /**
     * Peeks at the template ID from the buffer.
     * Accounts for the framing header.
     *
     * @param buffer the buffer
     * @param offset the offset of the framing header
     * @return the template ID
     */
    @Override
    public int peekTemplateId(DirectBuffer buffer, int offset) {
        // Skip framing header to get to SBE header
        int sbeOffset = offset + ILink3Message.FRAMING_HEADER_SIZE;
        headerDecoder.wrap(buffer, sbeOffset);
        return headerDecoder.templateId();
    }

    /**
     * Gets the expected message length from the framing header.
     *
     * @param buffer the buffer
     * @param offset the offset of the framing header
     * @param availableLength available bytes
     * @return the expected total length including framing header, or -1 if more data needed
     */
    @Override
    public int getExpectedLength(DirectBuffer buffer, int offset, int availableLength) {
        if (availableLength < ILink3Message.FRAMING_HEADER_SIZE) {
            return -1; // Need framing header
        }

        // Read message length from framing header (excludes framing header size)
        int messageLength = buffer.getShort(offset, SbeMessage.BYTE_ORDER) & 0xFFFF;
        int totalLength = ILink3Message.FRAMING_HEADER_SIZE + messageLength;

        if (availableLength < totalLength) {
            return -1; // Need more data
        }

        return totalLength;
    }

    @Override
    protected int calculateTotalLength(DirectBuffer buffer, int offset, int availableLength) {
        // For iLink 3, the framing header contains the message length
        int messageLength = buffer.getShort(offset, SbeMessage.BYTE_ORDER) & 0xFFFF;
        return ILink3Message.FRAMING_HEADER_SIZE + messageLength;
    }

    @Override
    public SbeMessageType getMessageType(int templateId) {
        return ILink3MessageType.fromTemplateId(templateId);
    }

    @Override
    public SbeSchema getSchema() {
        return ILink3Schema.INSTANCE;
    }
}
