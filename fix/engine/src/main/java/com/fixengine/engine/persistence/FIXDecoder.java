package com.fixengine.engine.persistence;

import com.fixengine.persistence.Decoder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * FIX protocol decoder implementation.
 *
 * <p>This decoder handles FIX protocol messages, extracting message types,
 * sequence numbers, and providing human-readable formatting.</p>
 */
public class FIXDecoder implements Decoder {

    private static final byte SOH = 0x01;

    private static final Map<String, String> MSG_TYPE_NAMES = new HashMap<>();

    static {
        // Session-level messages
        MSG_TYPE_NAMES.put("0", "Heartbeat");
        MSG_TYPE_NAMES.put("1", "TestRequest");
        MSG_TYPE_NAMES.put("2", "ResendRequest");
        MSG_TYPE_NAMES.put("3", "Reject");
        MSG_TYPE_NAMES.put("4", "SequenceReset");
        MSG_TYPE_NAMES.put("5", "Logout");
        MSG_TYPE_NAMES.put("A", "Logon");

        // Application messages - Pre-trade
        MSG_TYPE_NAMES.put("S", "Quote");
        MSG_TYPE_NAMES.put("R", "QuoteRequest");
        MSG_TYPE_NAMES.put("AI", "QuoteStatusRequest");
        MSG_TYPE_NAMES.put("AJ", "QuoteResponse");
        MSG_TYPE_NAMES.put("AG", "QuoteCancel");
        MSG_TYPE_NAMES.put("Z", "QuoteStatusReport");
        MSG_TYPE_NAMES.put("a", "QuoteAck");
        MSG_TYPE_NAMES.put("b", "MassQuote");
        MSG_TYPE_NAMES.put("c", "MassQuoteAck");

        // Application messages - Trade
        MSG_TYPE_NAMES.put("D", "NewOrderSingle");
        MSG_TYPE_NAMES.put("E", "NewOrderList");
        MSG_TYPE_NAMES.put("F", "OrderCancelRequest");
        MSG_TYPE_NAMES.put("G", "OrderCancelReplaceRequest");
        MSG_TYPE_NAMES.put("H", "OrderStatusRequest");
        MSG_TYPE_NAMES.put("J", "AllocationInstruction");
        MSG_TYPE_NAMES.put("K", "ListCancelRequest");
        MSG_TYPE_NAMES.put("L", "ListExecute");
        MSG_TYPE_NAMES.put("M", "ListStatusRequest");
        MSG_TYPE_NAMES.put("N", "ListStatus");
        MSG_TYPE_NAMES.put("P", "AllocationInstructionAck");
        MSG_TYPE_NAMES.put("Q", "DontKnowTrade");

        // Application messages - Execution
        MSG_TYPE_NAMES.put("8", "ExecutionReport");
        MSG_TYPE_NAMES.put("9", "OrderCancelReject");

        // Application messages - Post-trade
        MSG_TYPE_NAMES.put("AT", "Confirmation");
        MSG_TYPE_NAMES.put("AU", "ConfirmationAck");
        MSG_TYPE_NAMES.put("AV", "ConfirmationRequest");
        MSG_TYPE_NAMES.put("AZ", "CollateralRequest");
        MSG_TYPE_NAMES.put("BA", "CollateralAssignment");
        MSG_TYPE_NAMES.put("BB", "CollateralResponse");
        MSG_TYPE_NAMES.put("BC", "CollateralReport");
        MSG_TYPE_NAMES.put("BD", "CollateralInquiry");
        MSG_TYPE_NAMES.put("BG", "CollateralInquiryAck");

        // Application messages - Market Data
        MSG_TYPE_NAMES.put("V", "MarketDataRequest");
        MSG_TYPE_NAMES.put("W", "MarketDataSnapshotFullRefresh");
        MSG_TYPE_NAMES.put("X", "MarketDataIncrementalRefresh");
        MSG_TYPE_NAMES.put("Y", "MarketDataRequestReject");

        // Application messages - Security
        MSG_TYPE_NAMES.put("x", "SecurityListRequest");
        MSG_TYPE_NAMES.put("y", "SecurityList");
        MSG_TYPE_NAMES.put("z", "SecurityStatusRequest");
        MSG_TYPE_NAMES.put("f", "SecurityStatus");
        MSG_TYPE_NAMES.put("d", "SecurityDefinitionRequest");
        MSG_TYPE_NAMES.put("e", "SecurityDefinition");

        // Application messages - Business
        MSG_TYPE_NAMES.put("j", "BusinessMessageReject");

        // Application messages - Trading Session
        MSG_TYPE_NAMES.put("g", "TradingSessionStatusRequest");
        MSG_TYPE_NAMES.put("h", "TradingSessionStatus");

        // Application messages - News
        MSG_TYPE_NAMES.put("B", "News");

        // Application messages - Position
        MSG_TYPE_NAMES.put("AM", "PositionMaintenanceRequest");
        MSG_TYPE_NAMES.put("AN", "PositionMaintenanceReport");
        MSG_TYPE_NAMES.put("AO", "RequestForPositions");
        MSG_TYPE_NAMES.put("AP", "RequestForPositionsAck");
        MSG_TYPE_NAMES.put("AQ", "PositionReport");

        // Special
        MSG_TYPE_NAMES.put("EOD", "EndOfDay");
    }

    @Override
    public String getProtocolName() {
        return "FIX";
    }

    @Override
    public String decodeMessageType(ByteBuffer message) {
        return extractTag(message, 35);
    }

    @Override
    public String getMessageTypeName(String messageType) {
        if (messageType == null) return "Unknown";
        String name = MSG_TYPE_NAMES.get(messageType);
        return name != null ? name : "Unknown(" + messageType + ")";
    }

    @Override
    public boolean isAdminMessage(String messageType) {
        if (messageType == null) return false;
        return "0".equals(messageType) || "1".equals(messageType) ||
               "2".equals(messageType) || "3".equals(messageType) ||
               "4".equals(messageType) || "5".equals(messageType) ||
               "A".equals(messageType);
    }

    @Override
    public String formatMessage(ByteBuffer message, boolean verbose) {
        if (message == null || message.remaining() == 0) {
            return "";
        }

        // Convert to string with | as delimiter
        byte[] bytes = new byte[message.remaining()];
        message.duplicate().get(bytes);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            if (b == SOH) {
                sb.append('|');
            } else {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    @Override
    public int decodeSequenceNumber(ByteBuffer message) {
        String seqNumStr = extractTag(message, 34);
        if (seqNumStr != null) {
            try {
                return Integer.parseInt(seqNumStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Extract a tag value from a FIX message.
     *
     * @param message the raw message bytes
     * @param tag the tag number to extract
     * @return the tag value, or null if not found
     */
    private String extractTag(ByteBuffer message, int tag) {
        if (message == null || message.remaining() == 0) {
            return null;
        }

        String tagPrefix = tag + "=";
        byte[] prefixBytes = tagPrefix.getBytes(StandardCharsets.US_ASCII);

        ByteBuffer dup = message.duplicate();
        int pos = dup.position();
        int limit = dup.limit();

        // Search for tag prefix
        outer:
        while (pos < limit - prefixBytes.length) {
            // Check if we're at the start or after a SOH
            if (pos == dup.position() || dup.get(pos - 1) == SOH) {
                // Check for tag prefix
                boolean match = true;
                for (int i = 0; i < prefixBytes.length; i++) {
                    if (dup.get(pos + i) != prefixBytes[i]) {
                        match = false;
                        break;
                    }
                }

                if (match) {
                    // Found tag, extract value until SOH
                    int valueStart = pos + prefixBytes.length;
                    int valueEnd = valueStart;
                    while (valueEnd < limit && dup.get(valueEnd) != SOH) {
                        valueEnd++;
                    }

                    byte[] valueBytes = new byte[valueEnd - valueStart];
                    for (int i = 0; i < valueBytes.length; i++) {
                        valueBytes[i] = dup.get(valueStart + i);
                    }
                    return new String(valueBytes, StandardCharsets.US_ASCII);
                }
            }
            pos++;
        }

        return null;
    }

    /**
     * Get a formatted string with both code and name.
     *
     * @param msgType the FIX message type code
     * @return formatted string like "8 (ExecutionReport)"
     */
    public String decodeWithCode(String msgType) {
        if (msgType == null) return "Unknown";
        String name = MSG_TYPE_NAMES.get(msgType);
        if (name != null) {
            return msgType + " (" + name + ")";
        }
        return msgType;
    }

    /**
     * Get all known message types.
     *
     * @return map of message type codes to names
     */
    public static Map<String, String> getAllMessageTypes() {
        return new HashMap<>(MSG_TYPE_NAMES);
    }
}
