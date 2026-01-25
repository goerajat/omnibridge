package com.omnibridge.sbe.message;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Represents the standard SBE (Simple Binary Encoding) message header.
 * <p>
 * The SBE header is 8 bytes with the following structure:
 * <pre>
 * Offset  Length  Field           Description
 * 0       2       blockLength     Size of the message body (excluding repeating groups and var data)
 * 2       2       templateId      Identifies the message type within the schema
 * 4       2       schemaId        Identifies the schema (e.g., CME iLink 3 = 8, Euronext Optiq = 0)
 * 6       2       version         Schema version number
 * </pre>
 * <p>
 * All fields use little-endian byte order as per SBE specification.
 * <p>
 * This class implements the flyweight pattern - it wraps a buffer region
 * without copying data.
 */
public class SbeMessageHeader {

    /** Total size of the SBE message header in bytes */
    public static final int ENCODED_LENGTH = 8;

    /** Offset of blockLength field */
    public static final int BLOCK_LENGTH_OFFSET = 0;

    /** Offset of templateId field */
    public static final int TEMPLATE_ID_OFFSET = 2;

    /** Offset of schemaId field */
    public static final int SCHEMA_ID_OFFSET = 4;

    /** Offset of version field */
    public static final int VERSION_OFFSET = 6;

    /** Byte order for SBE (little-endian) */
    private static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    /** The buffer containing the header data */
    private DirectBuffer buffer;

    /** Offset within the buffer */
    private int offset;

    /**
     * Wraps the header around a buffer for reading.
     *
     * @param buffer the buffer containing the header
     * @param offset the offset of the header within the buffer
     * @return this header for method chaining
     */
    public SbeMessageHeader wrap(DirectBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    /**
     * Wraps the header around a mutable buffer for writing.
     *
     * @param buffer the buffer to write to
     * @param offset the offset within the buffer
     * @return this header for method chaining
     */
    public SbeMessageHeader wrap(MutableDirectBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
        return this;
    }

    /**
     * Resets this header, clearing buffer references.
     */
    public void reset() {
        this.buffer = null;
        this.offset = 0;
    }

    /**
     * Checks if this header is currently wrapping a buffer.
     *
     * @return true if wrapped, false otherwise
     */
    public boolean isWrapped() {
        return buffer != null;
    }

    // ==========================================================================
    // Getters
    // ==========================================================================

    /**
     * Gets the block length - size of the message body excluding
     * repeating groups and variable-length data.
     *
     * @return the block length in bytes
     */
    public int blockLength() {
        return buffer.getShort(offset + BLOCK_LENGTH_OFFSET, BYTE_ORDER) & 0xFFFF;
    }

    /**
     * Gets the template ID which identifies the message type within the schema.
     *
     * @return the template ID
     */
    public int templateId() {
        return buffer.getShort(offset + TEMPLATE_ID_OFFSET, BYTE_ORDER) & 0xFFFF;
    }

    /**
     * Gets the schema ID which identifies the message schema.
     * <p>
     * Common schema IDs:
     * <ul>
     *   <li>CME iLink 3: 8</li>
     *   <li>Euronext Optiq: 0</li>
     * </ul>
     *
     * @return the schema ID
     */
    public int schemaId() {
        return buffer.getShort(offset + SCHEMA_ID_OFFSET, BYTE_ORDER) & 0xFFFF;
    }

    /**
     * Gets the schema version number.
     *
     * @return the version
     */
    public int version() {
        return buffer.getShort(offset + VERSION_OFFSET, BYTE_ORDER) & 0xFFFF;
    }

    // ==========================================================================
    // Setters (Fluent API)
    // ==========================================================================

    /**
     * Sets the block length.
     *
     * @param value the block length in bytes
     * @return this header for method chaining
     */
    public SbeMessageHeader blockLength(int value) {
        ((MutableDirectBuffer) buffer).putShort(offset + BLOCK_LENGTH_OFFSET, (short) value, BYTE_ORDER);
        return this;
    }

    /**
     * Sets the template ID.
     *
     * @param value the template ID
     * @return this header for method chaining
     */
    public SbeMessageHeader templateId(int value) {
        ((MutableDirectBuffer) buffer).putShort(offset + TEMPLATE_ID_OFFSET, (short) value, BYTE_ORDER);
        return this;
    }

    /**
     * Sets the schema ID.
     *
     * @param value the schema ID
     * @return this header for method chaining
     */
    public SbeMessageHeader schemaId(int value) {
        ((MutableDirectBuffer) buffer).putShort(offset + SCHEMA_ID_OFFSET, (short) value, BYTE_ORDER);
        return this;
    }

    /**
     * Sets the schema version.
     *
     * @param value the version
     * @return this header for method chaining
     */
    public SbeMessageHeader version(int value) {
        ((MutableDirectBuffer) buffer).putShort(offset + VERSION_OFFSET, (short) value, BYTE_ORDER);
        return this;
    }

    /**
     * Gets the total message length based on the header's block length.
     * Note: This does not include repeating groups or variable-length data.
     * For the complete message length, use the message's getEncodedLength().
     *
     * @return header size + block length
     */
    public int getMinimumMessageLength() {
        return ENCODED_LENGTH + blockLength();
    }

    @Override
    public String toString() {
        if (!isWrapped()) {
            return "SbeMessageHeader[not wrapped]";
        }
        return String.format("SbeMessageHeader[blockLength=%d, templateId=%d, schemaId=%d, version=%d]",
                blockLength(), templateId(), schemaId(), version());
    }
}
