package com.omnibridge.ouch.message.v50;

import com.omnibridge.ouch.message.OuchMessage;
import com.omnibridge.ouch.message.OuchMessageType;
import org.agrona.DirectBuffer;

import java.nio.ByteOrder;

/**
 * Reader for OUCH 5.0 messages.
 *
 * <p>Uses flyweight pattern with pre-allocated message instances for efficient parsing.
 * Supports variable-length messages with appendages.</p>
 */
public class V50MessageReader {

    // Flyweight instances for reading (outbound messages)
    private final V50OrderAcceptedMessage orderAccepted = new V50OrderAcceptedMessage();
    private final V50OrderExecutedMessage orderExecuted = new V50OrderExecutedMessage();
    private final V50OrderCanceledMessage orderCanceled = new V50OrderCanceledMessage();
    private final V50OrderRejectedMessage orderRejected = new V50OrderRejectedMessage();
    private final V50OrderReplacedMessage orderReplaced = new V50OrderReplacedMessage();
    private final V50OrderRestatedMessage orderRestated = new V50OrderRestatedMessage();
    private final V50SystemEventMessage systemEvent = new V50SystemEventMessage();

    // Flyweight instances for reading (inbound messages)
    private final V50EnterOrderMessage enterOrder = new V50EnterOrderMessage();
    private final V50CancelOrderMessage cancelOrder = new V50CancelOrderMessage();
    private final V50ReplaceOrderMessage replaceOrder = new V50ReplaceOrderMessage();
    private final V50ModifyOrderMessage modifyOrder = new V50ModifyOrderMessage();
    private final V50MassCancelMessage massCancel = new V50MassCancelMessage();

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

        V50OuchMessage message = switch (type) {
            case ORDER_ACCEPTED -> orderAccepted;
            case ORDER_EXECUTED -> orderExecuted;
            case ORDER_CANCELED -> orderCanceled;
            case ORDER_REJECTED -> orderRejected;
            case ORDER_REPLACED -> orderReplaced;
            case ORDER_RESTATED -> orderRestated;
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

        V50OuchMessage message = switch (type) {
            case ENTER_ORDER -> enterOrder;
            case CANCEL_ORDER -> cancelOrder;
            case REPLACE_ORDER -> replaceOrder;
            case MODIFY_ORDER -> modifyOrder;
            case CANCEL_BY_ORDER_ID -> massCancel;
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
     * Get the expected base message length for a V50 message type.
     * Note: Actual length may be larger due to appendages.
     *
     * @param type the message type
     * @return the expected base length in bytes
     */
    public static int getExpectedLength(OuchMessageType type) {
        return switch (type) {
            case ENTER_ORDER -> V50EnterOrderMessage.BASE_MESSAGE_LENGTH;
            case CANCEL_ORDER -> V50CancelOrderMessage.BASE_MESSAGE_LENGTH;
            case REPLACE_ORDER -> V50ReplaceOrderMessage.BASE_MESSAGE_LENGTH;
            case MODIFY_ORDER -> V50ModifyOrderMessage.BASE_MESSAGE_LENGTH;
            case CANCEL_BY_ORDER_ID -> V50MassCancelMessage.BASE_MESSAGE_LENGTH;
            case ORDER_ACCEPTED -> V50OrderAcceptedMessage.BASE_MESSAGE_LENGTH;
            case ORDER_EXECUTED -> V50OrderExecutedMessage.BASE_MESSAGE_LENGTH;
            case ORDER_CANCELED -> V50OrderCanceledMessage.BASE_MESSAGE_LENGTH;
            case ORDER_REJECTED -> V50OrderRejectedMessage.BASE_MESSAGE_LENGTH;
            case ORDER_REPLACED -> V50OrderReplacedMessage.BASE_MESSAGE_LENGTH;
            case ORDER_RESTATED -> V50OrderRestatedMessage.BASE_MESSAGE_LENGTH;
            case SYSTEM_EVENT -> V50SystemEventMessage.BASE_MESSAGE_LENGTH;
            default -> 0;
        };
    }

    /**
     * Calculate the actual message length including appendages.
     * Must read the appendage count and parse appendage headers.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @return the actual message length, or 0 if unknown
     */
    public int calculateActualLength(DirectBuffer buffer, int offset) {
        if (buffer.capacity() <= offset) {
            return 0;
        }

        byte typeCode = buffer.getByte(offset);
        OuchMessageType type = OuchMessageType.fromOutboundCode(typeCode);
        if (type == OuchMessageType.UNKNOWN) {
            type = OuchMessageType.fromInboundCode(typeCode);
        }

        int baseLength = getExpectedLength(type);
        if (baseLength == 0) {
            return 0;
        }

        // Get appendage count offset for this message type
        int appendageCountOffset = getAppendageCountOffset(type);
        if (appendageCountOffset < 0 || buffer.capacity() <= offset + appendageCountOffset) {
            return baseLength;
        }

        int appendageCount = buffer.getByte(offset + appendageCountOffset) & 0xFF;
        if (appendageCount == 0) {
            return baseLength;
        }

        // Calculate appendage section length
        int appendageStart = offset + appendageCountOffset + 1;
        int appendagesLength = 0;
        for (int i = 0; i < appendageCount && appendageStart + 3 <= buffer.capacity(); i++) {
            int dataLength = buffer.getShort(appendageStart + 1, ByteOrder.BIG_ENDIAN) & 0xFFFF;
            int totalAppendageLength = 3 + dataLength; // tag(1) + length(2) + data
            appendagesLength += totalAppendageLength;
            appendageStart += totalAppendageLength;
        }

        return baseLength + appendagesLength;
    }

    private int getAppendageCountOffset(OuchMessageType type) {
        return switch (type) {
            case ENTER_ORDER -> V50EnterOrderMessage.APPENDAGE_COUNT_OFFSET;
            case REPLACE_ORDER -> V50ReplaceOrderMessage.APPENDAGE_COUNT_OFFSET;
            case ORDER_ACCEPTED -> V50OrderAcceptedMessage.APPENDAGE_COUNT_OFFSET;
            case ORDER_REPLACED -> V50OrderReplacedMessage.APPENDAGE_COUNT_OFFSET;
            default -> -1;
        };
    }

    // Direct accessors for flyweight instances
    public V50OrderAcceptedMessage getOrderAccepted() { return orderAccepted; }
    public V50OrderExecutedMessage getOrderExecuted() { return orderExecuted; }
    public V50OrderCanceledMessage getOrderCanceled() { return orderCanceled; }
    public V50OrderRejectedMessage getOrderRejected() { return orderRejected; }
    public V50OrderReplacedMessage getOrderReplaced() { return orderReplaced; }
    public V50OrderRestatedMessage getOrderRestated() { return orderRestated; }
    public V50SystemEventMessage getSystemEvent() { return systemEvent; }
    public V50EnterOrderMessage getEnterOrder() { return enterOrder; }
    public V50CancelOrderMessage getCancelOrder() { return cancelOrder; }
    public V50ReplaceOrderMessage getReplaceOrder() { return replaceOrder; }
    public V50ModifyOrderMessage getModifyOrder() { return modifyOrder; }
    public V50MassCancelMessage getMassCancel() { return massCancel; }
}
