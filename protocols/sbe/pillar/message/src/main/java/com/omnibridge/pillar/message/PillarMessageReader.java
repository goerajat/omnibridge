package com.omnibridge.pillar.message;

import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.sbe.message.SbeMessageReader;
import com.omnibridge.sbe.message.SbeMessageType;
import com.omnibridge.sbe.message.SbeSchema;
import org.agrona.DirectBuffer;

/**
 * Reader for NYSE Pillar messages.
 * <p>
 * Parses incoming binary messages and wraps them in flyweight instances.
 * NYSE Pillar uses a 4-byte message header (2-byte type + 2-byte length)
 * unlike standard SBE's 8-byte header.
 */
public class PillarMessageReader extends SbeMessageReader {

    private static final ThreadLocal<PillarMessagePool> POOL =
            ThreadLocal.withInitial(PillarMessagePool::new);

    /**
     * Reads a message from the buffer.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @param length the total message length
     * @return the parsed message, or null if unknown type
     */
    @Override
    public SbeMessage read(DirectBuffer buffer, int offset, int length) {
        int templateId = peekTemplateId(buffer, offset);
        PillarMessagePool pool = POOL.get();
        PillarMessage message = pool.getMessageByTemplateId(templateId);

        if (message != null) {
            message.wrapForReading(buffer, offset, length);
        }

        return message;
    }

    /**
     * Peeks the message type from the buffer without parsing.
     *
     * @param buffer the buffer
     * @param offset the offset of the message header
     * @return the template ID (message type)
     */
    @Override
    public int peekTemplateId(DirectBuffer buffer, int offset) {
        return buffer.getShort(offset + PillarMessage.MSG_TYPE_OFFSET, PillarMessage.BYTE_ORDER) & 0xFFFF;
    }

    /**
     * Gets the expected message length from the header.
     *
     * @param buffer the buffer
     * @param offset the offset of the message header
     * @param availableLength the available bytes in buffer
     * @return the expected message length, or -1 if incomplete header
     */
    @Override
    public int getExpectedLength(DirectBuffer buffer, int offset, int availableLength) {
        if (availableLength < PillarMessage.MSG_HEADER_SIZE) {
            return -1; // Need more data
        }

        return buffer.getShort(offset + PillarMessage.MSG_LENGTH_OFFSET, PillarMessage.BYTE_ORDER) & 0xFFFF;
    }

    @Override
    protected int calculateTotalLength(DirectBuffer buffer, int offset, int availableLength) {
        // For Pillar, the message length includes the header
        return buffer.getShort(offset + PillarMessage.MSG_LENGTH_OFFSET, PillarMessage.BYTE_ORDER) & 0xFFFF;
    }

    /**
     * Gets the message type enum for a template ID.
     *
     * @param templateId the template ID
     * @return the message type
     */
    @Override
    public SbeMessageType getMessageType(int templateId) {
        return PillarMessageType.fromTemplateId(templateId);
    }

    @Override
    public SbeSchema getSchema() {
        return PillarSchema.INSTANCE;
    }

    /**
     * Checks if a template ID is supported.
     *
     * @param templateId the template ID
     * @return true if supported
     */
    public boolean supportsTemplateId(int templateId) {
        return POOL.get().supportsTemplateId(templateId);
    }
}
