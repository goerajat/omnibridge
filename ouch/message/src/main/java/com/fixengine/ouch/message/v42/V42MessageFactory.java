package com.fixengine.ouch.message.v42;

import com.fixengine.ouch.message.*;
import com.fixengine.ouch.message.factory.OuchMessageFactory;
import org.agrona.DirectBuffer;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory implementation for OUCH 4.2 messages.
 */
public class V42MessageFactory implements OuchMessageFactory {

    // Mapping from base message classes to V42-specific classes
    private static final Map<Class<? extends OuchMessage>, Class<? extends OuchMessage>> CLASS_MAPPING = new HashMap<>();

    static {
        CLASS_MAPPING.put(EnterOrderMessage.class, V42EnterOrderMessage.class);
        CLASS_MAPPING.put(CancelOrderMessage.class, V42CancelOrderMessage.class);
        CLASS_MAPPING.put(ReplaceOrderMessage.class, V42ReplaceOrderMessage.class);
        CLASS_MAPPING.put(OrderAcceptedMessage.class, V42OrderAcceptedMessage.class);
        CLASS_MAPPING.put(OrderExecutedMessage.class, V42OrderExecutedMessage.class);
        CLASS_MAPPING.put(OrderCanceledMessage.class, V42OrderCanceledMessage.class);
        CLASS_MAPPING.put(OrderRejectedMessage.class, V42OrderRejectedMessage.class);
        CLASS_MAPPING.put(OrderReplacedMessage.class, V42OrderReplacedMessage.class);
        CLASS_MAPPING.put(SystemEventMessage.class, V42SystemEventMessage.class);
    }

    private final V42MessagePool pool;
    private final V42MessageReader reader;

    public V42MessageFactory() {
        this.pool = new V42MessagePool();
        this.reader = new V42MessageReader();
    }

    @Override
    public OuchVersion getVersion() {
        return OuchVersion.V42;
    }

    @Override
    public OuchMessage getMessage(OuchMessageType type) {
        return pool.getMessageByType(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OuchMessage> T getMessage(Class<T> messageClass) {
        // Map base message classes to V42-specific classes
        Class<? extends OuchMessage> v42Class = CLASS_MAPPING.getOrDefault(messageClass, messageClass);
        return (T) pool.getMessage(v42Class);
    }

    @Override
    public <T extends OuchMessage> T createMessage(Class<T> messageClass) {
        return V42MessagePool.createMessage(messageClass);
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
        return V42MessageReader.getExpectedLength(type);
    }

    @Override
    public void release(OuchMessage message) {
        pool.release(message);
    }

    @Override
    public boolean supports(OuchMessageType type) {
        return switch (type) {
            case ENTER_ORDER, CANCEL_ORDER, REPLACE_ORDER,
                 ORDER_ACCEPTED, ORDER_EXECUTED, ORDER_CANCELED,
                 ORDER_REJECTED, ORDER_REPLACED, SYSTEM_EVENT -> true;
            default -> false;
        };
    }

    /**
     * Get access to the underlying pool.
     */
    public V42MessagePool getPool() {
        return pool;
    }

    /**
     * Get access to the underlying reader.
     */
    public V42MessageReader getReader() {
        return reader;
    }
}
