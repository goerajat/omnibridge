package com.fixengine.persistence;

import java.nio.ByteBuffer;

/**
 * Interface for protocol-specific message decoding.
 *
 * <p>Implementations of this interface provide protocol-specific logic for
 * interpreting raw message bytes. This allows the persistence layer to be
 * protocol-agnostic while still providing meaningful display and filtering
 * capabilities.</p>
 *
 * <p>Example implementations:</p>
 * <ul>
 *   <li>FIXDecoder - for FIX protocol messages</li>
 *   <li>SBEDecoder - for Simple Binary Encoding messages</li>
 *   <li>ITCHDecoder - for NASDAQ ITCH protocol messages</li>
 * </ul>
 */
public interface Decoder {

    /**
     * Get the protocol name (e.g., "FIX", "SBE", "ITCH").
     *
     * @return the protocol name
     */
    String getProtocolName();

    /**
     * Extract the message type from raw bytes.
     *
     * @param message the raw message bytes
     * @return the message type string, or null if not available
     */
    String decodeMessageType(ByteBuffer message);

    /**
     * Get the human-readable name for a message type.
     *
     * @param messageType the message type code
     * @return the human-readable name, or the original code if unknown
     */
    String getMessageTypeName(String messageType);

    /**
     * Check if a message type represents an admin/session-level message.
     *
     * @param messageType the message type code
     * @return true if the message is admin-level, false otherwise
     */
    boolean isAdminMessage(String messageType);

    /**
     * Format a message for human-readable display.
     *
     * @param message the raw message bytes
     * @param verbose whether to include extra detail
     * @return the formatted message string
     */
    String formatMessage(ByteBuffer message, boolean verbose);

    /**
     * Format a message for human-readable display.
     *
     * @param message the raw message bytes
     * @param verbose whether to include extra detail
     * @return the formatted message string
     */
    default String formatMessage(byte[] message, boolean verbose) {
        if (message == null) {
            return "";
        }
        return formatMessage(ByteBuffer.wrap(message), verbose);
    }

    /**
     * Extract the sequence number from raw message bytes.
     *
     * @param message the raw message bytes
     * @return the sequence number, or -1 if not available
     */
    int decodeSequenceNumber(ByteBuffer message);

    /**
     * Extract the sequence number from raw message bytes.
     *
     * @param message the raw message bytes
     * @return the sequence number, or -1 if not available
     */
    default int decodeSequenceNumber(byte[] message) {
        if (message == null) {
            return -1;
        }
        return decodeSequenceNumber(ByteBuffer.wrap(message));
    }
}
