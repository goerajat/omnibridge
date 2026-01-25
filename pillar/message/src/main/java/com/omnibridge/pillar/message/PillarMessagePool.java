package com.omnibridge.pillar.message;

import com.omnibridge.pillar.message.order.*;
import com.omnibridge.pillar.message.session.*;
import com.omnibridge.sbe.message.SbeMessage;
import com.omnibridge.sbe.message.SbeMessagePool;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thread-local pool of NYSE Pillar message instances.
 * <p>
 * Provides zero-allocation message handling by reusing flyweight instances.
 */
public class PillarMessagePool {

    private final Map<Class<? extends PillarMessage>, PillarMessage> messagesByClass = new HashMap<>();
    private final Map<Integer, PillarMessage> messagesByTemplateId = new HashMap<>();

    /**
     * Creates a new message pool with all message types registered.
     */
    public PillarMessagePool() {
        // Register stream protocol messages
        register(LoginMessage.class, LoginMessage::new);
        register(LoginResponseMessage.class, LoginResponseMessage::new);
        register(StreamAvailMessage.class, StreamAvailMessage::new);
        register(OpenMessage.class, OpenMessage::new);
        register(OpenResponseMessage.class, OpenResponseMessage::new);
        register(CloseMessage.class, CloseMessage::new);
        register(CloseResponseMessage.class, CloseResponseMessage::new);
        register(HeartbeatMessage.class, HeartbeatMessage::new);

        // Register order entry messages
        register(NewOrderMessage.class, NewOrderMessage::new);
        register(CancelOrderMessage.class, CancelOrderMessage::new);
        register(CancelReplaceMessage.class, CancelReplaceMessage::new);

        // Register execution messages
        register(OrderAckMessage.class, OrderAckMessage::new);
        register(OrderRejectMessage.class, OrderRejectMessage::new);
        register(ExecutionReportMessage.class, ExecutionReportMessage::new);
        register(CancelAckMessage.class, CancelAckMessage::new);
        register(CancelRejectMessage.class, CancelRejectMessage::new);
        register(ReplaceAckMessage.class, ReplaceAckMessage::new);
        register(ReplaceRejectMessage.class, ReplaceRejectMessage::new);
    }

    private <T extends PillarMessage> void register(Class<T> clazz, Supplier<T> factory) {
        T message = factory.get();
        messagesByClass.put(clazz, message);
        messagesByTemplateId.put(message.getMessageType().getTemplateId(), message);
    }

    /**
     * Gets a pooled message instance by class.
     *
     * @param messageClass the message class
     * @return the pooled message instance
     */
    @SuppressWarnings("unchecked")
    public <T extends PillarMessage> T getMessage(Class<T> messageClass) {
        PillarMessage message = messagesByClass.get(messageClass);
        if (message != null) {
            message.reset();
        }
        return (T) message;
    }

    /**
     * Gets a pooled message instance by template ID.
     *
     * @param templateId the message template ID
     * @return the pooled message instance, or null if not found
     */
    public PillarMessage getMessageByTemplateId(int templateId) {
        PillarMessage message = messagesByTemplateId.get(templateId);
        if (message != null) {
            message.reset();
        }
        return message;
    }

    /**
     * Checks if a template ID is supported.
     *
     * @param templateId the template ID
     * @return true if supported
     */
    public boolean supportsTemplateId(int templateId) {
        return messagesByTemplateId.containsKey(templateId);
    }

    /**
     * Releases a message back to the pool.
     *
     * @param message the message to release
     */
    public void release(SbeMessage message) {
        if (message instanceof PillarMessage) {
            message.reset();
        }
    }
}
