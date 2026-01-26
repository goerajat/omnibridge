package com.omnibridge.sbe.message;

import org.agrona.DirectBuffer;

/**
 * Factory interface for creating and reading SBE messages.
 * <p>
 * Each protocol implementation (CME iLink 3, Euronext Optiq, etc.) provides
 * its own factory that knows how to create and parse messages for that protocol.
 * The factory abstracts away version-specific details and provides a unified
 * interface for message handling.
 * <p>
 * The factory combines the functionality of:
 * <ul>
 *   <li>{@link SbeMessagePool} - Pooled message instances</li>
 *   <li>{@link SbeMessageReader} - Message parsing</li>
 *   <li>{@link SbeSchema} - Schema metadata</li>
 * </ul>
 * <p>
 * Usage pattern:
 * <pre>
 * SbeMessageFactory factory = new ILink3MessageFactory();
 *
 * // Reading messages
 * SbeMessage msg = factory.readMessage(buffer, offset, length);
 *
 * // Creating messages for sending
 * NewOrderSingleMessage order = factory.getMessage(NewOrderSingleMessage.class);
 * order.wrap(sendBuffer, 0);
 * order.writeHeader();
 * order.setClOrdId(...)
 *       .setPrice(...)
 *       .setQuantity(...);
 * int length = order.complete();
 * </pre>
 *
 * @see SbeMessage
 * @see SbeMessagePool
 * @see SbeMessageReader
 */
public interface SbeMessageFactory {

    /**
     * Gets the schema this factory handles.
     *
     * @return the SBE schema
     */
    SbeSchema getSchema();

    /**
     * Gets a pooled message instance by class.
     * The message is reset and ready for use.
     *
     * @param messageClass the message class
     * @param <T> the message type
     * @return the pooled message instance
     */
    <T extends SbeMessage> T getMessage(Class<T> messageClass);

    /**
     * Gets a pooled message instance by template ID.
     *
     * @param templateId the template ID
     * @return the pooled message instance, or null if not supported
     */
    SbeMessage getMessageByTemplateId(int templateId);

    /**
     * Creates a new (non-pooled) message instance.
     * Use when you need a message that outlives the current scope.
     *
     * @param messageClass the message class
     * @param <T> the message type
     * @return a new message instance
     */
    <T extends SbeMessage> T createMessage(Class<T> messageClass);

    /**
     * Reads a message from the buffer.
     * Returns a flyweight that wraps the buffer without copying.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message
     * @param length the available length
     * @return the parsed message, or null if type is unknown
     */
    SbeMessage readMessage(DirectBuffer buffer, int offset, int length);

    /**
     * Peeks at the template ID without fully parsing.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @return the template ID
     */
    int peekTemplateId(DirectBuffer buffer, int offset);

    /**
     * Gets the expected message length.
     * Returns -1 if more data is needed to determine length.
     *
     * @param buffer the buffer containing the message
     * @param offset the offset of the message header
     * @param availableLength the available bytes
     * @return the expected length, or -1 if incomplete
     */
    int getExpectedLength(DirectBuffer buffer, int offset, int availableLength);

    /**
     * Gets the message type for a template ID.
     *
     * @param templateId the template ID
     * @return the message type, or null if unknown
     */
    SbeMessageType getMessageType(int templateId);

    /**
     * Checks if a template ID is supported by this factory.
     *
     * @param templateId the template ID
     * @return true if supported
     */
    boolean supportsTemplateId(int templateId);

    /**
     * Releases a message back to the pool.
     *
     * @param message the message to release
     */
    void release(SbeMessage message);
}
