package com.fixengine.ouch.message.v50;

import com.fixengine.ouch.message.OuchMessage;
import com.fixengine.ouch.message.OuchMessageType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thread-local pool of OUCH 5.0 message instances.
 *
 * <p>This pool provides flyweight-style reuse of message objects to minimize
 * garbage collection pressure in high-throughput applications.</p>
 */
public class V50MessagePool {

    private static final ThreadLocal<V50MessagePool> THREAD_LOCAL_POOL =
            ThreadLocal.withInitial(V50MessagePool::new);

    private static final Map<Class<? extends OuchMessage>, Supplier<? extends OuchMessage>> FACTORIES = new HashMap<>();

    static {
        FACTORIES.put(V50EnterOrderMessage.class, V50EnterOrderMessage::new);
        FACTORIES.put(V50CancelOrderMessage.class, V50CancelOrderMessage::new);
        FACTORIES.put(V50ReplaceOrderMessage.class, V50ReplaceOrderMessage::new);
        FACTORIES.put(V50ModifyOrderMessage.class, V50ModifyOrderMessage::new);
        FACTORIES.put(V50MassCancelMessage.class, V50MassCancelMessage::new);
        FACTORIES.put(V50OrderAcceptedMessage.class, V50OrderAcceptedMessage::new);
        FACTORIES.put(V50OrderExecutedMessage.class, V50OrderExecutedMessage::new);
        FACTORIES.put(V50OrderCanceledMessage.class, V50OrderCanceledMessage::new);
        FACTORIES.put(V50OrderRejectedMessage.class, V50OrderRejectedMessage::new);
        FACTORIES.put(V50OrderReplacedMessage.class, V50OrderReplacedMessage::new);
        FACTORIES.put(V50OrderRestatedMessage.class, V50OrderRestatedMessage::new);
        FACTORIES.put(V50SystemEventMessage.class, V50SystemEventMessage::new);
    }

    // Pre-allocated message instances
    private final V50EnterOrderMessage enterOrderMessage = new V50EnterOrderMessage();
    private final V50CancelOrderMessage cancelOrderMessage = new V50CancelOrderMessage();
    private final V50ReplaceOrderMessage replaceOrderMessage = new V50ReplaceOrderMessage();
    private final V50ModifyOrderMessage modifyOrderMessage = new V50ModifyOrderMessage();
    private final V50MassCancelMessage massCancelMessage = new V50MassCancelMessage();
    private final V50OrderAcceptedMessage orderAcceptedMessage = new V50OrderAcceptedMessage();
    private final V50OrderExecutedMessage orderExecutedMessage = new V50OrderExecutedMessage();
    private final V50OrderCanceledMessage orderCanceledMessage = new V50OrderCanceledMessage();
    private final V50OrderRejectedMessage orderRejectedMessage = new V50OrderRejectedMessage();
    private final V50OrderReplacedMessage orderReplacedMessage = new V50OrderReplacedMessage();
    private final V50OrderRestatedMessage orderRestatedMessage = new V50OrderRestatedMessage();
    private final V50SystemEventMessage systemEventMessage = new V50SystemEventMessage();

    /**
     * Get the thread-local pool instance.
     */
    public static V50MessagePool get() {
        return THREAD_LOCAL_POOL.get();
    }

    /**
     * Create a new non-pooled message instance.
     */
    @SuppressWarnings("unchecked")
    public static <T extends OuchMessage> T createMessage(Class<T> messageClass) {
        Supplier<? extends OuchMessage> factory = FACTORIES.get(messageClass);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown message class: " + messageClass.getName());
        }
        return (T) factory.get();
    }

    /**
     * Get a pooled message instance by class.
     * The message is reset before being returned.
     */
    @SuppressWarnings("unchecked")
    public <T extends OuchMessage> T getMessage(Class<T> messageClass) {
        OuchMessage message = getMessageInstance(messageClass);
        message.reset();
        return (T) message;
    }

    private OuchMessage getMessageInstance(Class<? extends OuchMessage> messageClass) {
        if (messageClass == V50EnterOrderMessage.class) return enterOrderMessage;
        if (messageClass == V50CancelOrderMessage.class) return cancelOrderMessage;
        if (messageClass == V50ReplaceOrderMessage.class) return replaceOrderMessage;
        if (messageClass == V50ModifyOrderMessage.class) return modifyOrderMessage;
        if (messageClass == V50MassCancelMessage.class) return massCancelMessage;
        if (messageClass == V50OrderAcceptedMessage.class) return orderAcceptedMessage;
        if (messageClass == V50OrderExecutedMessage.class) return orderExecutedMessage;
        if (messageClass == V50OrderCanceledMessage.class) return orderCanceledMessage;
        if (messageClass == V50OrderRejectedMessage.class) return orderRejectedMessage;
        if (messageClass == V50OrderReplacedMessage.class) return orderReplacedMessage;
        if (messageClass == V50OrderRestatedMessage.class) return orderRestatedMessage;
        if (messageClass == V50SystemEventMessage.class) return systemEventMessage;
        throw new IllegalArgumentException("Unknown V50 message class: " + messageClass.getName());
    }

    /**
     * Get a pooled message instance by type.
     */
    public OuchMessage getMessageByType(OuchMessageType type) {
        OuchMessage message = switch (type) {
            case ENTER_ORDER -> enterOrderMessage;
            case CANCEL_ORDER -> cancelOrderMessage;
            case REPLACE_ORDER -> replaceOrderMessage;
            case MODIFY_ORDER -> modifyOrderMessage;
            case CANCEL_BY_ORDER_ID -> massCancelMessage;
            case ORDER_ACCEPTED -> orderAcceptedMessage;
            case ORDER_EXECUTED -> orderExecutedMessage;
            case ORDER_CANCELED -> orderCanceledMessage;
            case ORDER_REJECTED -> orderRejectedMessage;
            case ORDER_REPLACED -> orderReplacedMessage;
            case ORDER_RESTATED -> orderRestatedMessage;
            case SYSTEM_EVENT -> systemEventMessage;
            default -> throw new IllegalArgumentException("Unsupported V50 message type: " + type);
        };
        message.reset();
        return message;
    }

    /**
     * Release a message back to the pool.
     */
    public void release(OuchMessage message) {
        if (message != null) {
            message.reset();
        }
    }

    // Direct accessor methods for convenience
    public V50EnterOrderMessage getEnterOrderMessage() { enterOrderMessage.reset(); return enterOrderMessage; }
    public V50CancelOrderMessage getCancelOrderMessage() { cancelOrderMessage.reset(); return cancelOrderMessage; }
    public V50ReplaceOrderMessage getReplaceOrderMessage() { replaceOrderMessage.reset(); return replaceOrderMessage; }
    public V50ModifyOrderMessage getModifyOrderMessage() { modifyOrderMessage.reset(); return modifyOrderMessage; }
    public V50MassCancelMessage getMassCancelMessage() { massCancelMessage.reset(); return massCancelMessage; }
    public V50OrderAcceptedMessage getOrderAcceptedMessage() { orderAcceptedMessage.reset(); return orderAcceptedMessage; }
    public V50OrderExecutedMessage getOrderExecutedMessage() { orderExecutedMessage.reset(); return orderExecutedMessage; }
    public V50OrderCanceledMessage getOrderCanceledMessage() { orderCanceledMessage.reset(); return orderCanceledMessage; }
    public V50OrderRejectedMessage getOrderRejectedMessage() { orderRejectedMessage.reset(); return orderRejectedMessage; }
    public V50OrderReplacedMessage getOrderReplacedMessage() { orderReplacedMessage.reset(); return orderReplacedMessage; }
    public V50OrderRestatedMessage getOrderRestatedMessage() { orderRestatedMessage.reset(); return orderRestatedMessage; }
    public V50SystemEventMessage getSystemEventMessage() { systemEventMessage.reset(); return systemEventMessage; }
}
