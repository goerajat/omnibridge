package com.omnibridge.ouch.message.factory;

import com.omnibridge.ouch.message.OuchMessage;
import com.omnibridge.ouch.message.OuchMessageType;
import com.omnibridge.ouch.message.OuchVersion;
import org.agrona.DirectBuffer;

/**
 * Factory interface for creating version-specific OUCH messages.
 *
 * <p>This interface abstracts the creation and reading of OUCH messages,
 * allowing the session layer to work with messages without knowing the
 * specific protocol version.</p>
 */
public interface OuchMessageFactory {

    /**
     * Get the OUCH protocol version this factory produces.
     */
    OuchVersion getVersion();

    /**
     * Get a pooled message instance by type.
     * The message is reset before being returned.
     *
     * @param type the message type
     * @return a pooled message instance
     */
    OuchMessage getMessage(OuchMessageType type);

    /**
     * Get a pooled message instance by class.
     *
     * @param messageClass the message class
     * @return a pooled message instance
     */
    <T extends OuchMessage> T getMessage(Class<T> messageClass);

    /**
     * Create a new non-pooled message instance.
     *
     * @param messageClass the message class
     * @return a new message instance
     */
    <T extends OuchMessage> T createMessage(Class<T> messageClass);

    /**
     * Read a message from a buffer (for receiving).
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return the parsed message, or null if unknown type
     */
    OuchMessage readMessage(DirectBuffer buffer, int offset, int length);

    /**
     * Read an outbound message from a buffer (exchange to client).
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return the parsed message, or null if not an outbound message
     */
    OuchMessage readOutboundMessage(DirectBuffer buffer, int offset, int length);

    /**
     * Read an inbound message from a buffer (client to exchange).
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return the parsed message, or null if not an inbound message
     */
    OuchMessage readInboundMessage(DirectBuffer buffer, int offset, int length);

    /**
     * Peek at the message type without fully parsing.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @return the message type
     */
    OuchMessageType peekType(DirectBuffer buffer, int offset);

    /**
     * Get the expected message length for a given type.
     * For variable-length messages (V50), returns the base message length.
     *
     * @param type the message type
     * @return the expected length in bytes
     */
    int getExpectedLength(OuchMessageType type);

    /**
     * Release a message back to the pool.
     *
     * @param message the message to release
     */
    void release(OuchMessage message);

    /**
     * Check if this factory supports the given message type.
     *
     * @param type the message type
     * @return true if supported
     */
    boolean supports(OuchMessageType type);
}
