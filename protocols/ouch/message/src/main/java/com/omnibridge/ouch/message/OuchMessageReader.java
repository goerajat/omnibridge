package com.omnibridge.ouch.message;

import org.agrona.DirectBuffer;

/**
 * Reader for parsing incoming OUCH messages.
 *
 * <p>Uses flyweight pattern - reuses message objects to minimize allocation.
 * Thread-safe when each thread uses its own reader instance.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * OuchMessageReader reader = new OuchMessageReader();
 * OuchMessage msg = reader.read(buffer, offset, length);
 * if (msg != null) {
 *     switch (msg.getMessageType()) {
 *         case ORDER_ACCEPTED -> handleAccepted((OrderAcceptedMessage) msg);
 *         case ORDER_EXECUTED -> handleExecuted((OrderExecutedMessage) msg);
 *         case ORDER_CANCELED -> handleCanceled((OrderCanceledMessage) msg);
 *     }
 * }
 * }</pre>
 */
public class OuchMessageReader {

    // Flyweight message instances (reused for each parse)
    private final OrderAcceptedMessage orderAccepted = new OrderAcceptedMessage();
    private final OrderExecutedMessage orderExecuted = new OrderExecutedMessage();
    private final OrderCanceledMessage orderCanceled = new OrderCanceledMessage();
    private final OrderRejectedMessage orderRejected = new OrderRejectedMessage();
    private final SystemEventMessage systemEvent = new SystemEventMessage();
    private final OrderReplacedMessage orderReplaced = new OrderReplacedMessage();

    // Inbound message instances (for acceptor mode)
    private final EnterOrderMessage enterOrder = new EnterOrderMessage();
    private final CancelOrderMessage cancelOrder = new CancelOrderMessage();
    private final ReplaceOrderMessage replaceOrder = new ReplaceOrderMessage();

    /**
     * Read an OUCH message from the buffer.
     *
     * <p>Supports both outbound messages (exchange to client) and inbound messages
     * (client to exchange) for use in both initiator and acceptor modes.</p>
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return the parsed message, or null if unknown type
     */
    public OuchMessage read(DirectBuffer buffer, int offset, int length) {
        if (length < 1) {
            return null;
        }

        byte typeCode = buffer.getByte(offset);

        // Try outbound messages first (exchange to client)
        OuchMessageType type = OuchMessageType.fromOutboundCode(typeCode);
        OuchMessage msg = getMessageForType(type);

        // If not found, try inbound messages (client to exchange)
        if (msg == null) {
            type = OuchMessageType.fromInboundCode(typeCode);
            msg = getMessageForType(type);
        }

        if (msg != null) {
            msg.wrapForReading(buffer, offset, length);
        }

        return msg;
    }

    /**
     * Read an outbound OUCH message from the buffer (exchange to client).
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return the parsed message, or null if unknown type
     */
    public OuchMessage readOutbound(DirectBuffer buffer, int offset, int length) {
        if (length < 1) {
            return null;
        }

        byte typeCode = buffer.getByte(offset);
        OuchMessageType type = OuchMessageType.fromOutboundCode(typeCode);

        return switch (type) {
            case ORDER_ACCEPTED -> orderAccepted.wrapForReading(buffer, offset, length);
            case ORDER_EXECUTED -> orderExecuted.wrapForReading(buffer, offset, length);
            case ORDER_CANCELED -> orderCanceled.wrapForReading(buffer, offset, length);
            case ORDER_REJECTED -> orderRejected.wrapForReading(buffer, offset, length);
            case SYSTEM_EVENT -> systemEvent.wrapForReading(buffer, offset, length);
            case ORDER_REPLACED -> orderReplaced.wrapForReading(buffer, offset, length);
            default -> null;
        };
    }

    /**
     * Read an inbound OUCH message from the buffer (client to exchange).
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return the parsed message, or null if unknown type
     */
    public OuchMessage readInbound(DirectBuffer buffer, int offset, int length) {
        if (length < 1) {
            return null;
        }

        byte typeCode = buffer.getByte(offset);
        OuchMessageType type = OuchMessageType.fromInboundCode(typeCode);

        return switch (type) {
            case ENTER_ORDER -> enterOrder.wrapForReading(buffer, offset, length);
            case CANCEL_ORDER -> cancelOrder.wrapForReading(buffer, offset, length);
            case REPLACE_ORDER -> replaceOrder.wrapForReading(buffer, offset, length);
            default -> null;
        };
    }

    /**
     * Get a flyweight message instance for the given type.
     */
    private OuchMessage getMessageForType(OuchMessageType type) {
        return switch (type) {
            case ORDER_ACCEPTED -> orderAccepted;
            case ORDER_EXECUTED -> orderExecuted;
            case ORDER_CANCELED -> orderCanceled;
            case ORDER_REJECTED -> orderRejected;
            case SYSTEM_EVENT -> systemEvent;
            case ORDER_REPLACED -> orderReplaced;
            case ENTER_ORDER -> enterOrder;
            case CANCEL_ORDER -> cancelOrder;
            case REPLACE_ORDER -> replaceOrder;
            default -> null;
        };
    }

    /**
     * Get the message type without fully parsing.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @return the message type, or UNKNOWN if not recognized
     */
    public OuchMessageType peekType(DirectBuffer buffer, int offset) {
        byte typeCode = buffer.getByte(offset);
        OuchMessageType type = OuchMessageType.fromOutboundCode(typeCode);
        if (type == OuchMessageType.UNKNOWN) {
            type = OuchMessageType.fromInboundCode(typeCode);
        }
        return type;
    }

    /**
     * Get the expected message length for a message type.
     *
     * @param type the message type
     * @return the expected length, or -1 if unknown
     */
    public static int getExpectedLength(OuchMessageType type) {
        return switch (type) {
            case ORDER_ACCEPTED -> OrderAcceptedMessage.MESSAGE_LENGTH;
            case ORDER_EXECUTED -> OrderExecutedMessage.MESSAGE_LENGTH;
            case ORDER_CANCELED -> OrderCanceledMessage.MESSAGE_LENGTH;
            case ORDER_REJECTED -> OrderRejectedMessage.MESSAGE_LENGTH;
            case SYSTEM_EVENT -> SystemEventMessage.MESSAGE_LENGTH;
            case ORDER_REPLACED -> OrderReplacedMessage.MESSAGE_LENGTH;
            case ENTER_ORDER -> EnterOrderMessage.MESSAGE_LENGTH;
            case CANCEL_ORDER -> CancelOrderMessage.MESSAGE_LENGTH;
            case REPLACE_ORDER -> ReplaceOrderMessage.MESSAGE_LENGTH;
            default -> -1;
        };
    }

    // =====================================================
    // Direct access to flyweight instances
    // =====================================================

    /**
     * Get the pooled OrderAcceptedMessage instance.
     */
    public OrderAcceptedMessage getOrderAcceptedMessage() {
        return orderAccepted;
    }

    /**
     * Get the pooled OrderExecutedMessage instance.
     */
    public OrderExecutedMessage getOrderExecutedMessage() {
        return orderExecuted;
    }

    /**
     * Get the pooled OrderCanceledMessage instance.
     */
    public OrderCanceledMessage getOrderCanceledMessage() {
        return orderCanceled;
    }

    /**
     * Get the pooled OrderRejectedMessage instance.
     */
    public OrderRejectedMessage getOrderRejectedMessage() {
        return orderRejected;
    }

    /**
     * Get the pooled OrderReplacedMessage instance.
     */
    public OrderReplacedMessage getOrderReplacedMessage() {
        return orderReplaced;
    }

    /**
     * Get the pooled SystemEventMessage instance.
     */
    public SystemEventMessage getSystemEventMessage() {
        return systemEvent;
    }

    /**
     * Get the pooled EnterOrderMessage instance.
     */
    public EnterOrderMessage getEnterOrderMessage() {
        return enterOrder;
    }

    /**
     * Get the pooled CancelOrderMessage instance.
     */
    public CancelOrderMessage getCancelOrderMessage() {
        return cancelOrder;
    }

    /**
     * Get the pooled ReplaceOrderMessage instance.
     */
    public ReplaceOrderMessage getReplaceOrderMessage() {
        return replaceOrder;
    }
}
