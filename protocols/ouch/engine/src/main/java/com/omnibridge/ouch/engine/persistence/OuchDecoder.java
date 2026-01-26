package com.omnibridge.ouch.engine.persistence;

import com.omnibridge.ouch.message.*;
import com.omnibridge.persistence.Decoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * OUCH protocol decoder for log store integration.
 *
 * <p>Implements the Decoder interface to provide protocol-specific message
 * interpretation for the log viewer and replay tools.</p>
 *
 * <p>Outputs messages in single-line JSON format for easy parsing and display.</p>
 */
public class OuchDecoder implements Decoder {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Flyweight message instances for decoding
    private final OrderAcceptedMessage orderAccepted = new OrderAcceptedMessage();
    private final OrderExecutedMessage orderExecuted = new OrderExecutedMessage();
    private final OrderCanceledMessage orderCanceled = new OrderCanceledMessage();
    private final OrderRejectedMessage orderRejected = new OrderRejectedMessage();
    private final OrderReplacedMessage orderReplaced = new OrderReplacedMessage();
    private final SystemEventMessage systemEvent = new SystemEventMessage();
    private final EnterOrderMessage enterOrder = new EnterOrderMessage();
    private final CancelOrderMessage cancelOrder = new CancelOrderMessage();
    private final ReplaceOrderMessage replaceOrder = new ReplaceOrderMessage();

    @Override
    public String getProtocolName() {
        return "OUCH";
    }

    @Override
    public String decodeMessageType(ByteBuffer message) {
        if (message.remaining() < 1) {
            return "?";
        }
        byte typeCode = message.get(message.position());
        return String.valueOf((char) typeCode);
    }

    @Override
    public String getMessageTypeName(String messageType) {
        if (messageType == null || messageType.isEmpty()) {
            return "Unknown";
        }
        char code = messageType.charAt(0);
        OuchMessageType type = OuchMessageType.fromOutboundCode((byte) code);
        if (type == OuchMessageType.UNKNOWN) {
            type = OuchMessageType.fromInboundCode((byte) code);
        }
        return type.getDescription();
    }

    @Override
    public boolean isAdminMessage(String messageType) {
        if (messageType == null || messageType.isEmpty()) {
            return false;
        }
        char code = messageType.charAt(0);
        // System events are admin messages
        return code == 'S';
    }

    @Override
    public String formatMessage(ByteBuffer message, boolean verbose) {
        if (message.remaining() < 1) {
            return "{}";
        }

        DirectBuffer buffer = new UnsafeBuffer(message);
        int offset = message.position();
        int length = message.remaining();

        byte typeCode = buffer.getByte(offset);
        OuchMessageType type = OuchMessageType.fromOutboundCode(typeCode);
        if (type == OuchMessageType.UNKNOWN) {
            type = OuchMessageType.fromInboundCode(typeCode);
        }

        try {
            return switch (type) {
                case ORDER_ACCEPTED -> formatOrderAccepted(buffer, offset, length, verbose);
                case ORDER_EXECUTED -> formatOrderExecuted(buffer, offset, length, verbose);
                case ORDER_CANCELED -> formatOrderCanceled(buffer, offset, length, verbose);
                case ORDER_REJECTED -> formatOrderRejected(buffer, offset, length, verbose);
                case ORDER_REPLACED -> formatOrderReplaced(buffer, offset, length, verbose);
                case SYSTEM_EVENT -> formatSystemEvent(buffer, offset, length, verbose);
                case ENTER_ORDER -> formatEnterOrder(buffer, offset, length, verbose);
                case CANCEL_ORDER -> formatCancelOrder(buffer, offset, length, verbose);
                case REPLACE_ORDER -> formatReplaceOrder(buffer, offset, length, verbose);
                default -> formatUnknown(type, typeCode);
            };
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\",\"type\":\"" + type + "\"}";
        }
    }

    @Override
    public int decodeSequenceNumber(ByteBuffer message) {
        // OUCH doesn't have sequence numbers in the message itself
        // Return -1 to indicate not available
        return -1;
    }

    // =====================================================
    // Format methods for each message type
    // =====================================================

    private String formatOrderAccepted(DirectBuffer buffer, int offset, int length, boolean verbose) {
        orderAccepted.wrapForReading(buffer, offset, length);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "OrderAccepted");
        json.put("token", orderAccepted.getOrderToken().trim());
        json.put("symbol", orderAccepted.getSymbol().trim());
        json.put("side", String.valueOf(orderAccepted.getSideCode()));
        json.put("shares", orderAccepted.getShares());
        json.put("price", orderAccepted.getPriceAsDouble());
        json.put("orderRef", orderAccepted.getOrderReferenceNumber());
        json.put("state", String.valueOf(orderAccepted.getOrderState()));
        if (verbose) {
            json.put("timestamp", orderAccepted.getTimestamp());
            json.put("display", String.valueOf(orderAccepted.getDisplay()));
            json.put("capacity", String.valueOf(orderAccepted.getCapacity()));
        }
        return json.toString();
    }

    private String formatOrderExecuted(DirectBuffer buffer, int offset, int length, boolean verbose) {
        orderExecuted.wrapForReading(buffer, offset, length);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "OrderExecuted");
        json.put("token", orderExecuted.getOrderToken().trim());
        json.put("execShares", orderExecuted.getExecutedShares());
        json.put("execPrice", orderExecuted.getExecutionPriceAsDouble());
        json.put("liquidity", String.valueOf(orderExecuted.getLiquidityFlag()));
        json.put("matchNum", orderExecuted.getMatchNumber());
        if (verbose) {
            json.put("timestamp", orderExecuted.getTimestamp());
        }
        return json.toString();
    }

    private String formatOrderCanceled(DirectBuffer buffer, int offset, int length, boolean verbose) {
        orderCanceled.wrapForReading(buffer, offset, length);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "OrderCanceled");
        json.put("token", orderCanceled.getOrderToken().trim());
        json.put("decrement", orderCanceled.getDecrementShares());
        json.put("reason", String.valueOf(orderCanceled.getReason()));
        json.put("reasonDesc", orderCanceled.getReasonDescription());
        if (verbose) {
            json.put("timestamp", orderCanceled.getTimestamp());
        }
        return json.toString();
    }

    private String formatOrderRejected(DirectBuffer buffer, int offset, int length, boolean verbose) {
        orderRejected.wrapForReading(buffer, offset, length);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "OrderRejected");
        json.put("token", orderRejected.getOrderToken().trim());
        json.put("reason", String.valueOf(orderRejected.getRejectReason()));
        json.put("reasonDesc", orderRejected.getRejectReasonDescription());
        if (verbose) {
            json.put("timestamp", orderRejected.getTimestamp());
        }
        return json.toString();
    }

    private String formatOrderReplaced(DirectBuffer buffer, int offset, int length, boolean verbose) {
        orderReplaced.wrapForReading(buffer, offset, length);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "OrderReplaced");
        json.put("newToken", orderReplaced.getReplacementOrderToken().trim());
        json.put("prevToken", orderReplaced.getPreviousOrderToken().trim());
        json.put("symbol", orderReplaced.getSymbol().trim());
        json.put("side", String.valueOf(orderReplaced.getSideCode()));
        json.put("shares", orderReplaced.getShares());
        json.put("price", orderReplaced.getPriceAsDouble());
        if (verbose) {
            json.put("timestamp", orderReplaced.getTimestamp());
            json.put("orderRef", orderReplaced.getOrderReferenceNumber());
        }
        return json.toString();
    }

    private String formatSystemEvent(DirectBuffer buffer, int offset, int length, boolean verbose) {
        systemEvent.wrapForReading(buffer, offset, length);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "SystemEvent");
        json.put("event", String.valueOf(systemEvent.getEventCode()));
        json.put("eventDesc", systemEvent.getEventDescription());
        if (verbose) {
            json.put("timestamp", systemEvent.getTimestamp());
        }
        return json.toString();
    }

    private String formatEnterOrder(DirectBuffer buffer, int offset, int length, boolean verbose) {
        // For outbound messages, we read directly from buffer
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "EnterOrder");

        // Read fields directly
        byte[] tokenBytes = new byte[14];
        buffer.getBytes(offset + 1, tokenBytes, 0, 14);
        json.put("token", new String(tokenBytes).trim());

        json.put("side", String.valueOf((char) buffer.getByte(offset + 15)));
        json.put("shares", buffer.getInt(offset + 16, java.nio.ByteOrder.BIG_ENDIAN));

        byte[] symbolBytes = new byte[8];
        buffer.getBytes(offset + 20, symbolBytes, 0, 8);
        json.put("symbol", new String(symbolBytes).trim());

        int price = buffer.getInt(offset + 28, java.nio.ByteOrder.BIG_ENDIAN);
        json.put("price", price / 10000.0);

        if (verbose) {
            json.put("tif", buffer.getInt(offset + 32, java.nio.ByteOrder.BIG_ENDIAN));
            json.put("display", String.valueOf((char) buffer.getByte(offset + 40)));
            json.put("capacity", String.valueOf((char) buffer.getByte(offset + 41)));
        }
        return json.toString();
    }

    private String formatCancelOrder(DirectBuffer buffer, int offset, int length, boolean verbose) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "CancelOrder");

        byte[] tokenBytes = new byte[14];
        buffer.getBytes(offset + 1, tokenBytes, 0, 14);
        json.put("token", new String(tokenBytes).trim());
        json.put("shares", buffer.getInt(offset + 15, java.nio.ByteOrder.BIG_ENDIAN));

        return json.toString();
    }

    private String formatReplaceOrder(DirectBuffer buffer, int offset, int length, boolean verbose) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "ReplaceOrder");

        byte[] existingToken = new byte[14];
        buffer.getBytes(offset + 1, existingToken, 0, 14);
        json.put("existingToken", new String(existingToken).trim());

        byte[] newToken = new byte[14];
        buffer.getBytes(offset + 15, newToken, 0, 14);
        json.put("newToken", new String(newToken).trim());

        json.put("shares", buffer.getInt(offset + 29, java.nio.ByteOrder.BIG_ENDIAN));
        int price = buffer.getInt(offset + 33, java.nio.ByteOrder.BIG_ENDIAN);
        json.put("price", price / 10000.0);

        return json.toString();
    }

    private String formatUnknown(OuchMessageType type, byte typeCode) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", type.name());
        json.put("code", String.valueOf((char) typeCode));
        return json.toString();
    }
}
