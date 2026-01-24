package com.omnibridge.ouch.message;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thread-local pool of pre-allocated OUCH message instances.
 *
 * <p>Uses flyweight pattern to minimize allocation during message processing.
 * Each thread gets its own pool to avoid synchronization overhead.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Get the thread-local pool
 * OuchMessagePool pool = OuchMessagePool.get();
 *
 * // Get a message instance for reading
 * OrderAcceptedMessage msg = pool.getMessage(OrderAcceptedMessage.class);
 * msg.wrapForReading(buffer, offset, length);
 *
 * // Get a message instance for writing
 * EnterOrderMessage enterMsg = pool.getMessage(EnterOrderMessage.class);
 * enterMsg.wrap(buffer, offset);
 * enterMsg.setOrderToken("TOKEN001");
 * }</pre>
 */
public class OuchMessagePool {

    private static final ThreadLocal<OuchMessagePool> THREAD_LOCAL_POOL =
            ThreadLocal.withInitial(OuchMessagePool::new);

    // Pre-allocated message instances (one per type)
    private final EnterOrderMessage enterOrder = new EnterOrderMessage();
    private final CancelOrderMessage cancelOrder = new CancelOrderMessage();
    private final ReplaceOrderMessage replaceOrder = new ReplaceOrderMessage();
    private final OrderAcceptedMessage orderAccepted = new OrderAcceptedMessage();
    private final OrderExecutedMessage orderExecuted = new OrderExecutedMessage();
    private final OrderCanceledMessage orderCanceled = new OrderCanceledMessage();
    private final OrderRejectedMessage orderRejected = new OrderRejectedMessage();
    private final OrderReplacedMessage orderReplaced = new OrderReplacedMessage();
    private final SystemEventMessage systemEvent = new SystemEventMessage();

    // Class to instance mapping for getMessage()
    private final Map<Class<? extends OuchMessage>, OuchMessage> messageMap;

    // Class to factory mapping for creating new instances
    private static final Map<Class<? extends OuchMessage>, Supplier<? extends OuchMessage>> FACTORIES;

    static {
        FACTORIES = new HashMap<>();
        FACTORIES.put(EnterOrderMessage.class, EnterOrderMessage::new);
        FACTORIES.put(CancelOrderMessage.class, CancelOrderMessage::new);
        FACTORIES.put(ReplaceOrderMessage.class, ReplaceOrderMessage::new);
        FACTORIES.put(OrderAcceptedMessage.class, OrderAcceptedMessage::new);
        FACTORIES.put(OrderExecutedMessage.class, OrderExecutedMessage::new);
        FACTORIES.put(OrderCanceledMessage.class, OrderCanceledMessage::new);
        FACTORIES.put(OrderRejectedMessage.class, OrderRejectedMessage::new);
        FACTORIES.put(OrderReplacedMessage.class, OrderReplacedMessage::new);
        FACTORIES.put(SystemEventMessage.class, SystemEventMessage::new);
    }

    /**
     * Create a new message pool with pre-allocated instances.
     */
    public OuchMessagePool() {
        messageMap = new HashMap<>();
        messageMap.put(EnterOrderMessage.class, enterOrder);
        messageMap.put(CancelOrderMessage.class, cancelOrder);
        messageMap.put(ReplaceOrderMessage.class, replaceOrder);
        messageMap.put(OrderAcceptedMessage.class, orderAccepted);
        messageMap.put(OrderExecutedMessage.class, orderExecuted);
        messageMap.put(OrderCanceledMessage.class, orderCanceled);
        messageMap.put(OrderRejectedMessage.class, orderRejected);
        messageMap.put(OrderReplacedMessage.class, orderReplaced);
        messageMap.put(SystemEventMessage.class, systemEvent);
    }

    /**
     * Get the thread-local message pool for the current thread.
     */
    public static OuchMessagePool get() {
        return THREAD_LOCAL_POOL.get();
    }

    /**
     * Get a pre-allocated message instance of the specified type.
     *
     * <p>The returned instance is reused across calls, so callers must not
     * hold references to it across message boundaries.</p>
     *
     * @param messageClass the message class
     * @param <T> the message type
     * @return the pre-allocated message instance
     * @throws IllegalArgumentException if the message type is not supported
     */
    @SuppressWarnings("unchecked")
    public <T extends OuchMessage> T getMessage(Class<T> messageClass) {
        OuchMessage msg = messageMap.get(messageClass);
        if (msg == null) {
            throw new IllegalArgumentException("Unknown message type: " + messageClass.getName());
        }
        msg.reset();
        return (T) msg;
    }

    /**
     * Get a message instance by message type.
     *
     * @param type the message type
     * @return the pre-allocated message instance, or null if unknown type
     */
    public OuchMessage getMessageByType(OuchMessageType type) {
        OuchMessage msg = switch (type) {
            case ENTER_ORDER -> enterOrder;
            case CANCEL_ORDER -> cancelOrder;
            case REPLACE_ORDER -> replaceOrder;
            case ORDER_ACCEPTED -> orderAccepted;
            case ORDER_EXECUTED -> orderExecuted;
            case ORDER_CANCELED -> orderCanceled;
            case ORDER_REJECTED -> orderRejected;
            case ORDER_REPLACED -> orderReplaced;
            case SYSTEM_EVENT -> systemEvent;
            default -> null;
        };
        if (msg != null) {
            msg.reset();
        }
        return msg;
    }

    /**
     * Release a message back to the pool.
     *
     * <p>This resets the message state. Not strictly necessary since messages
     * are reused automatically, but can be called explicitly for clarity.</p>
     *
     * @param message the message to release
     */
    public void release(OuchMessage message) {
        if (message != null) {
            message.reset();
        }
    }

    /**
     * Create a new message instance of the specified type.
     *
     * <p>Unlike {@link #getMessage(Class)}, this creates a fresh instance
     * that is not pooled. Use this when you need to hold onto a message
     * beyond the current processing context.</p>
     *
     * @param messageClass the message class
     * @param <T> the message type
     * @return a new message instance
     * @throws IllegalArgumentException if the message type is not supported
     */
    @SuppressWarnings("unchecked")
    public static <T extends OuchMessage> T createMessage(Class<T> messageClass) {
        Supplier<? extends OuchMessage> factory = FACTORIES.get(messageClass);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown message type: " + messageClass.getName());
        }
        return (T) factory.get();
    }

    // =====================================================
    // Direct access to pooled instances (for performance)
    // =====================================================

    /**
     * Get the pooled EnterOrderMessage instance.
     */
    public EnterOrderMessage getEnterOrderMessage() {
        enterOrder.reset();
        return enterOrder;
    }

    /**
     * Get the pooled CancelOrderMessage instance.
     */
    public CancelOrderMessage getCancelOrderMessage() {
        cancelOrder.reset();
        return cancelOrder;
    }

    /**
     * Get the pooled ReplaceOrderMessage instance.
     */
    public ReplaceOrderMessage getReplaceOrderMessage() {
        replaceOrder.reset();
        return replaceOrder;
    }

    /**
     * Get the pooled OrderAcceptedMessage instance.
     */
    public OrderAcceptedMessage getOrderAcceptedMessage() {
        orderAccepted.reset();
        return orderAccepted;
    }

    /**
     * Get the pooled OrderExecutedMessage instance.
     */
    public OrderExecutedMessage getOrderExecutedMessage() {
        orderExecuted.reset();
        return orderExecuted;
    }

    /**
     * Get the pooled OrderCanceledMessage instance.
     */
    public OrderCanceledMessage getOrderCanceledMessage() {
        orderCanceled.reset();
        return orderCanceled;
    }

    /**
     * Get the pooled OrderRejectedMessage instance.
     */
    public OrderRejectedMessage getOrderRejectedMessage() {
        orderRejected.reset();
        return orderRejected;
    }

    /**
     * Get the pooled OrderReplacedMessage instance.
     */
    public OrderReplacedMessage getOrderReplacedMessage() {
        orderReplaced.reset();
        return orderReplaced;
    }

    /**
     * Get the pooled SystemEventMessage instance.
     */
    public SystemEventMessage getSystemEventMessage() {
        systemEvent.reset();
        return systemEvent;
    }
}
