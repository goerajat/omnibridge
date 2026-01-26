package com.omnibridge.optiq.message;

import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.sbe.message.SbeMessageHeader;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Base class for all Euronext Optiq messages.
 * <p>
 * Optiq uses SBE (Simple Binary Encoding) with:
 * <ul>
 *   <li>Schema ID: 0</li>
 *   <li>Schema Version: 1</li>
 *   <li>2-byte framing header (message length prefix)</li>
 *   <li>8-byte SBE header</li>
 *   <li>Little-endian byte order</li>
 * </ul>
 * <p>
 * Message structure:
 * <pre>
 * Offset  Length  Field
 * 0       2       Framing header (message length, excludes framing header size)
 * 2       2       Block Length
 * 4       2       Template ID
 * 6       2       Schema ID (0)
 * 8       2       Schema Version
 * 10      var     Message Body
 * </pre>
 */
public abstract class OptiqMessage extends SbeMessage {

    /** Euronext Optiq schema ID */
    public static final int SCHEMA_ID = 0;

    /** Euronext Optiq schema version */
    public static final int SCHEMA_VERSION = 1;

    /** Size of the framing header (2-byte length prefix) */
    public static final int FRAMING_HEADER_SIZE = 2;

    /** Total header size including framing header */
    public static final int TOTAL_HEADER_SIZE = FRAMING_HEADER_SIZE + SbeMessageHeader.ENCODED_LENGTH;

    @Override
    public int getSchemaId() {
        return SCHEMA_ID;
    }

    @Override
    public int getSchemaVersion() {
        return SCHEMA_VERSION;
    }

    /**
     * Wraps this message around a buffer for reading.
     * Assumes the buffer starts at the framing header.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the framing header
     * @param length the total message length including framing header
     * @return this message for method chaining
     */
    @Override
    public SbeMessage wrapForReading(DirectBuffer buffer, int offset, int length) {
        // Skip framing header, start at SBE header
        return super.wrapForReading(buffer, offset + FRAMING_HEADER_SIZE, length - FRAMING_HEADER_SIZE);
    }

    /**
     * Wraps this message around a buffer for writing.
     * Reserves space for the framing header.
     *
     * @param buffer the buffer to write to
     * @param offset the offset to start writing at (includes framing header)
     * @param maxLength the maximum available space
     * @param claimIndex the ring buffer claim index
     * @return this message for method chaining
     */
    @Override
    public SbeMessage wrapForWriting(MutableDirectBuffer buffer, int offset, int maxLength, int claimIndex) {
        // Skip framing header, start at SBE header
        return super.wrapForWriting(buffer, offset + FRAMING_HEADER_SIZE, maxLength - FRAMING_HEADER_SIZE, claimIndex);
    }

    /**
     * Gets the total encoded length including framing header.
     *
     * @return the total message length
     */
    @Override
    public int getEncodedLength() {
        return FRAMING_HEADER_SIZE + HEADER_SIZE + getBlockLength();
    }

    /**
     * Writes the framing header and SBE header.
     * Should be called after wrapping for writing.
     */
    @Override
    public void writeHeader() {
        // Write SBE header first
        super.writeHeader();

        // Write framing header (message length excluding framing header size)
        int messageLength = HEADER_SIZE + getBlockLength();
        MutableDirectBuffer buf = (MutableDirectBuffer) buffer;
        buf.putShort(offset - FRAMING_HEADER_SIZE, (short) messageLength, BYTE_ORDER);
    }

    /**
     * Completes the message for sending.
     *
     * @return the total encoded length including framing header
     */
    @Override
    public int complete() {
        this.length = getEncodedLength();
        return this.length;
    }

    /**
     * Gets the message type for this Optiq message.
     *
     * @return the message type
     */
    @Override
    public abstract OptiqMessageType getMessageType();

    @Override
    public String toString() {
        if (!isWrapped()) {
            return getClass().getSimpleName() + "[not wrapped]";
        }
        return getClass().getSimpleName() + "[templateId=" + getTemplateId() +
               ", length=" + getEncodedLength() + "]";
    }
}
