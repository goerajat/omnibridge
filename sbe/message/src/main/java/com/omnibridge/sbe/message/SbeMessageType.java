package com.omnibridge.sbe.message;

/**
 * Interface for SBE message type enumerations.
 * <p>
 * Each SBE-based protocol (CME iLink 3, Euronext Optiq, etc.) defines its own
 * message types with unique template IDs. This interface provides a common
 * abstraction for message type handling.
 * <p>
 * Implementations should be enums that define all message types for a specific
 * protocol schema. Example:
 * <pre>
 * public enum ILink3MessageType implements SbeMessageType {
 *     NEGOTIATE(500, "Negotiate", Direction.INBOUND),
 *     NEGOTIATION_RESPONSE(501, "NegotiationResponse", Direction.OUTBOUND),
 *     NEW_ORDER_SINGLE(514, "NewOrderSingle", Direction.INBOUND),
 *     EXECUTION_REPORT(522, "ExecutionReport", Direction.OUTBOUND);
 *     // ...
 * }
 * </pre>
 *
 * @see SbeMessage
 */
public interface SbeMessageType {

    /**
     * Message direction relative to the trading system.
     */
    enum Direction {
        /** Messages sent from client to exchange/server */
        INBOUND,
        /** Messages sent from exchange/server to client */
        OUTBOUND,
        /** Messages that can flow in both directions */
        BIDIRECTIONAL
    }

    /**
     * Gets the SBE template ID for this message type.
     * The template ID uniquely identifies the message within a schema.
     *
     * @return the template ID
     */
    int getTemplateId();

    /**
     * Gets a human-readable name for this message type.
     *
     * @return the message type name
     */
    String getName();

    /**
     * Gets the direction of this message type.
     *
     * @return the message direction
     */
    Direction getDirection();

    /**
     * Checks if this message type is inbound (client to server).
     *
     * @return true if inbound
     */
    default boolean isInbound() {
        return getDirection() == Direction.INBOUND || getDirection() == Direction.BIDIRECTIONAL;
    }

    /**
     * Checks if this message type is outbound (server to client).
     *
     * @return true if outbound
     */
    default boolean isOutbound() {
        return getDirection() == Direction.OUTBOUND || getDirection() == Direction.BIDIRECTIONAL;
    }

    /**
     * Gets the schema ID this message type belongs to.
     * Protocol-specific implementations should override this.
     *
     * @return the schema ID
     */
    default int getSchemaId() {
        return 0;
    }

    /**
     * Gets the block length for this message type.
     * This is the fixed size of the message body (excluding groups and var data).
     * Returns -1 if the message has variable length.
     *
     * @return the block length or -1 for variable-length messages
     */
    default int getBlockLength() {
        return -1;
    }
}
