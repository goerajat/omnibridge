package com.omnibridge.ouch.message.v50;

import com.omnibridge.ouch.message.*;
import com.omnibridge.ouch.message.factory.OuchMessageFactory;
import org.agrona.DirectBuffer;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory implementation for OUCH 5.0 messages.
 */
public class V50MessageFactory implements OuchMessageFactory {

    // Mapping from base message classes to V50-specific classes
    private static final Map<Class<? extends OuchMessage>, Class<? extends OuchMessage>> CLASS_MAPPING = new HashMap<>();

    static {
        CLASS_MAPPING.put(EnterOrderMessage.class, V50EnterOrderMessage.class);
        CLASS_MAPPING.put(CancelOrderMessage.class, V50CancelOrderMessage.class);
        CLASS_MAPPING.put(ReplaceOrderMessage.class, V50ReplaceOrderMessage.class);
        CLASS_MAPPING.put(OrderAcceptedMessage.class, V50OrderAcceptedMessage.class);
        CLASS_MAPPING.put(OrderExecutedMessage.class, V50OrderExecutedMessage.class);
        CLASS_MAPPING.put(OrderCanceledMessage.class, V50OrderCanceledMessage.class);
        CLASS_MAPPING.put(OrderRejectedMessage.class, V50OrderRejectedMessage.class);
        CLASS_MAPPING.put(OrderReplacedMessage.class, V50OrderReplacedMessage.class);
        CLASS_MAPPING.put(SystemEventMessage.class, V50SystemEventMessage.class);
    }

    private final V50MessagePool pool;
    private final V50MessageReader reader;

    public V50MessageFactory() {
        this.pool = new V50MessagePool();
        this.reader = new V50MessageReader();
    }

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V50;
    }

    @Override
    public OuchMessage getMessage(OuchMessageType type) {
        return pool.getMessageByType(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OuchMessage> T getMessage(Class<T> messageClass) {
        // Map base message classes to V50-specific classes
        Class<? extends OuchMessage> v50Class = CLASS_MAPPING.getOrDefault(messageClass, messageClass);
        return (T) pool.getMessage(v50Class);
    }

    @Override
    public <T extends OuchMessage> T createMessage(Class<T> messageClass) {
        return V50MessagePool.createMessage(messageClass);
    }

    @Override
    public OuchMessage readMessage(DirectBuffer buffer, int offset, int length) {
        return reader.read(buffer, offset, length);
    }

    @Override
    public OuchMessage readOutboundMessage(DirectBuffer buffer, int offset, int length) {
        return reader.readOutbound(buffer, offset, length);
    }

    @Override
    public OuchMessage readInboundMessage(DirectBuffer buffer, int offset, int length) {
        return reader.readInbound(buffer, offset, length);
    }

    @Override
    public OuchMessageType peekType(DirectBuffer buffer, int offset) {
        return reader.peekType(buffer, offset);
    }

    @Override
    public int getExpectedLength(OuchMessageType type) {
        return V50MessageReader.getExpectedLength(type);
    }

    /**
     * Calculate the actual message length including appendages.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @return the actual message length
     */
    public int calculateActualLength(DirectBuffer buffer, int offset) {
        return reader.calculateActualLength(buffer, offset);
    }

    @Override
    public void release(OuchMessage message) {
        pool.release(message);
    }

    @Override
    public boolean supports(OuchMessageType type) {
        return switch (type) {
            case ENTER_ORDER, CANCEL_ORDER, REPLACE_ORDER, MODIFY_ORDER, CANCEL_BY_ORDER_ID,
                 ORDER_ACCEPTED, ORDER_EXECUTED, ORDER_CANCELED, ORDER_REJECTED,
                 ORDER_REPLACED, ORDER_RESTATED, SYSTEM_EVENT -> true;
            default -> false;
        };
    }

    /**
     * Get access to the underlying pool.
     */
    public V50MessagePool getPool() {
        return pool;
    }

    /**
     * Get access to the underlying reader.
     */
    public V50MessageReader getReader() {
        return reader;
    }
}
