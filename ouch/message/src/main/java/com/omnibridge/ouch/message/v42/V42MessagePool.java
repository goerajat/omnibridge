package com.omnibridge.ouch.message.v42;

import com.omnibridge.ouch.message.OuchMessage;
import com.omnibridge.ouch.message.OuchMessageType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thread-local pool of OUCH 4.2 message instances.
 *
 * <p>This pool provides flyweight-style reuse of message objects to minimize
 * garbage collection pressure in high-throughput applications.</p>
 */
public class V42MessagePool {

    private static final ThreadLocal<V42MessagePool> THREAD_LOCAL_POOL =
            ThreadLocal.withInitial(V42MessagePool::new);

    private static final Map<Class<? extends OuchMessage>, Supplier<? extends OuchMessage>> FACTORIES = new HashMap<>();

    static {
        FACTORIES.put(V42EnterOrderMessage.class, V42EnterOrderMessage::new);
        FACTORIES.put(V42CancelOrderMessage.class, V42CancelOrderMessage::new);
        FACTORIES.put(V42ReplaceOrderMessage.class, V42ReplaceOrderMessage::new);
        FACTORIES.put(V42OrderAcceptedMessage.class, V42OrderAcceptedMessage::new);
        FACTORIES.put(V42OrderExecutedMessage.class, V42OrderExecutedMessage::new);
        FACTORIES.put(V42OrderCanceledMessage.class, V42OrderCanceledMessage::new);
        FACTORIES.put(V42OrderRejectedMessage.class, V42OrderRejectedMessage::new);
        FACTORIES.put(V42OrderReplacedMessage.class, V42OrderReplacedMessage::new);
        FACTORIES.put(V42SystemEventMessage.class, V42SystemEventMessage::new);
    }

    // Pre-allocated message instances
    private final V42EnterOrderMessage enterOrderMessage = new V42EnterOrderMessage();
    private final V42CancelOrderMessage cancelOrderMessage = new V42CancelOrderMessage();
    private final V42ReplaceOrderMessage replaceOrderMessage = new V42ReplaceOrderMessage();
    private final V42OrderAcceptedMessage orderAcceptedMessage = new V42OrderAcceptedMessage();
    private final V42OrderExecutedMessage orderExecutedMessage = new V42OrderExecutedMessage();
    private final V42OrderCanceledMessage orderCanceledMessage = new V42OrderCanceledMessage();
    private final V42OrderRejectedMessage orderRejectedMessage = new V42OrderRejectedMessage();
    private final V42OrderReplacedMessage orderReplacedMessage = new V42OrderReplacedMessage();
    private final V42SystemEventMessage systemEventMessage = new V42SystemEventMessage();

    /**
     * Get the thread-local pool instance.
     */
    public static V42MessagePool get() {
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
        if (messageClass == V42EnterOrderMessage.class) {
            return enterOrderMessage;
        } else if (messageClass == V42CancelOrderMessage.class) {
            return cancelOrderMessage;
        } else if (messageClass == V42ReplaceOrderMessage.class) {
            return replaceOrderMessage;
        } else if (messageClass == V42OrderAcceptedMessage.class) {
            return orderAcceptedMessage;
        } else if (messageClass == V42OrderExecutedMessage.class) {
            return orderExecutedMessage;
        } else if (messageClass == V42OrderCanceledMessage.class) {
            return orderCanceledMessage;
        } else if (messageClass == V42OrderRejectedMessage.class) {
            return orderRejectedMessage;
        } else if (messageClass == V42OrderReplacedMessage.class) {
            return orderReplacedMessage;
        } else if (messageClass == V42SystemEventMessage.class) {
            return systemEventMessage;
        }
        throw new IllegalArgumentException("Unknown V42 message class: " + messageClass.getName());
    }

    /**
     * Get a pooled message instance by type.
     */
    public OuchMessage getMessageByType(OuchMessageType type) {
        OuchMessage message = switch (type) {
            case ENTER_ORDER -> enterOrderMessage;
            case CANCEL_ORDER -> cancelOrderMessage;
            case REPLACE_ORDER -> replaceOrderMessage;
            case ORDER_ACCEPTED -> orderAcceptedMessage;
            case ORDER_EXECUTED -> orderExecutedMessage;
            case ORDER_CANCELED -> orderCanceledMessage;
            case ORDER_REJECTED -> orderRejectedMessage;
            case ORDER_REPLACED -> orderReplacedMessage;
            case SYSTEM_EVENT -> systemEventMessage;
            default -> throw new IllegalArgumentException("Unsupported V42 message type: " + type);
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
    public V42EnterOrderMessage getEnterOrderMessage() {
        enterOrderMessage.reset();
        return enterOrderMessage;
    }

    public V42CancelOrderMessage getCancelOrderMessage() {
        cancelOrderMessage.reset();
        return cancelOrderMessage;
    }

    public V42ReplaceOrderMessage getReplaceOrderMessage() {
        replaceOrderMessage.reset();
        return replaceOrderMessage;
    }

    public V42OrderAcceptedMessage getOrderAcceptedMessage() {
        orderAcceptedMessage.reset();
        return orderAcceptedMessage;
    }

    public V42OrderExecutedMessage getOrderExecutedMessage() {
        orderExecutedMessage.reset();
        return orderExecutedMessage;
    }

    public V42OrderCanceledMessage getOrderCanceledMessage() {
        orderCanceledMessage.reset();
        return orderCanceledMessage;
    }

    public V42OrderRejectedMessage getOrderRejectedMessage() {
        orderRejectedMessage.reset();
        return orderRejectedMessage;
    }

    public V42OrderReplacedMessage getOrderReplacedMessage() {
        orderReplacedMessage.reset();
        return orderReplacedMessage;
    }

    public V42SystemEventMessage getSystemEventMessage() {
        systemEventMessage.reset();
        return systemEventMessage;
    }
}
