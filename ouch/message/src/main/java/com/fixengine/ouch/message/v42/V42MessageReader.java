package com.fixengine.ouch.message.v42;

import com.fixengine.ouch.message.OuchMessage;
import com.fixengine.ouch.message.OuchMessageType;
import org.agrona.DirectBuffer;

/**
 * Reader for OUCH 4.2 messages.
 *
 * <p>Uses flyweight pattern with pre-allocated message instances for efficient parsing.</p>
 */
public class V42MessageReader {

    // Flyweight instances for reading (outbound messages)
    private final V42OrderAcceptedMessage orderAccepted = new V42OrderAcceptedMessage();
    private final V42OrderExecutedMessage orderExecuted = new V42OrderExecutedMessage();
    private final V42OrderCanceledMessage orderCanceled = new V42OrderCanceledMessage();
    private final V42OrderRejectedMessage orderRejected = new V42OrderRejectedMessage();
    private final V42OrderReplacedMessage orderReplaced = new V42OrderReplacedMessage();
    private final V42SystemEventMessage systemEvent = new V42SystemEventMessage();

    // Flyweight instances for reading (inbound messages)
    private final V42EnterOrderMessage enterOrder = new V42EnterOrderMessage();
    private final V42CancelOrderMessage cancelOrder = new V42CancelOrderMessage();
    private final V42ReplaceOrderMessage replaceOrder = new V42ReplaceOrderMessage();

    /**
     * Read a message from the buffer.
     * Tries outbound first, then inbound.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return the parsed message, or null if unknown type
     */
    public OuchMessage read(DirectBuffer buffer, int offset, int length) {
        OuchMessage msg = readOutbound(buffer, offset, length);
        if (msg == null) {
            msg = readInbound(buffer, offset, length);
        }
        return msg;
    }

    /**
     * Read an outbound message (exchange to client).
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return the parsed message, or null if not an outbound message
     */
    public OuchMessage readOutbound(DirectBuffer buffer, int offset, int length) {
        if (length < 1) {
            return null;
        }

        byte typeCode = buffer.getByte(offset);
        OuchMessageType type = OuchMessageType.fromOutboundCode(typeCode);

        OuchMessage message = switch (type) {
            case ORDER_ACCEPTED -> orderAccepted;
            case ORDER_EXECUTED -> orderExecuted;
            case ORDER_CANCELED -> orderCanceled;
            case ORDER_REJECTED -> orderRejected;
            case ORDER_REPLACED -> orderReplaced;
            case SYSTEM_EVENT -> systemEvent;
            default -> null;
        };

        if (message != null) {
            message.wrapForReading(buffer, offset, length);
        }
        return message;
    }

    /**
     * Read an inbound message (client to exchange).
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param length the available length
     * @return the parsed message, or null if not an inbound message
     */
    public OuchMessage readInbound(DirectBuffer buffer, int offset, int length) {
        if (length < 1) {
            return null;
        }

        byte typeCode = buffer.getByte(offset);
        OuchMessageType type = OuchMessageType.fromInboundCode(typeCode);

        OuchMessage message = switch (type) {
            case ENTER_ORDER -> enterOrder;
            case CANCEL_ORDER -> cancelOrder;
            case REPLACE_ORDER -> replaceOrder;
            default -> null;
        };

        if (message != null) {
            message.wrapForReading(buffer, offset, length);
        }
        return message;
    }

    /**
     * Peek at the message type without fully parsing.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @return the message type, or UNKNOWN if buffer is empty
     */
    public OuchMessageType peekType(DirectBuffer buffer, int offset) {
        if (buffer.capacity() <= offset) {
            return OuchMessageType.UNKNOWN;
        }
        byte typeCode = buffer.getByte(offset);
        OuchMessageType type = OuchMessageType.fromOutboundCode(typeCode);
        if (type == OuchMessageType.UNKNOWN) {
            type = OuchMessageType.fromInboundCode(typeCode);
        }
        return type;
    }

    /**
     * Get the expected message length for a V42 message type.
     *
     * @param type the message type
     * @return the expected length in bytes
     */
    public static int getExpectedLength(OuchMessageType type) {
        return switch (type) {
            case ENTER_ORDER -> V42EnterOrderMessage.MESSAGE_LENGTH;
            case CANCEL_ORDER -> V42CancelOrderMessage.MESSAGE_LENGTH;
            case REPLACE_ORDER -> V42ReplaceOrderMessage.MESSAGE_LENGTH;
            case ORDER_ACCEPTED -> V42OrderAcceptedMessage.MESSAGE_LENGTH;
            case ORDER_EXECUTED -> V42OrderExecutedMessage.MESSAGE_LENGTH;
            case ORDER_CANCELED -> V42OrderCanceledMessage.MESSAGE_LENGTH;
            case ORDER_REJECTED -> V42OrderRejectedMessage.MESSAGE_LENGTH;
            case ORDER_REPLACED -> V42OrderReplacedMessage.MESSAGE_LENGTH;
            case SYSTEM_EVENT -> V42SystemEventMessage.MESSAGE_LENGTH;
            default -> 0;
        };
    }

    // Direct accessors for flyweight instances
    public V42OrderAcceptedMessage getOrderAccepted() { return orderAccepted; }
    public V42OrderExecutedMessage getOrderExecuted() { return orderExecuted; }
    public V42OrderCanceledMessage getOrderCanceled() { return orderCanceled; }
    public V42OrderRejectedMessage getOrderRejected() { return orderRejected; }
    public V42OrderReplacedMessage getOrderReplaced() { return orderReplaced; }
    public V42SystemEventMessage getSystemEvent() { return systemEvent; }
    public V42EnterOrderMessage getEnterOrder() { return enterOrder; }
    public V42CancelOrderMessage getCancelOrder() { return cancelOrder; }
    public V42ReplaceOrderMessage getReplaceOrder() { return replaceOrder; }
}
