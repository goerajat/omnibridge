package com.omnibridge.persistence.cli;

import java.util.HashMap;
import java.util.Map;

/**
 * Decodes FIX message type codes to human-readable names.
 */
public class MsgTypeDecoder {

    private static final Map<String, String> MSG_TYPES = new HashMap<>();

    static {
        // Session-level messages
        MSG_TYPES.put("0", "Heartbeat");
        MSG_TYPES.put("1", "TestRequest");
        MSG_TYPES.put("2", "ResendRequest");
        MSG_TYPES.put("3", "Reject");
        MSG_TYPES.put("4", "SequenceReset");
        MSG_TYPES.put("5", "Logout");
        MSG_TYPES.put("A", "Logon");

        // Application messages - Pre-trade
        MSG_TYPES.put("S", "Quote");
        MSG_TYPES.put("R", "QuoteRequest");
        MSG_TYPES.put("AI", "QuoteStatusRequest");
        MSG_TYPES.put("AJ", "QuoteResponse");
        MSG_TYPES.put("AG", "QuoteCancel");
        MSG_TYPES.put("Z", "QuoteStatusReport");
        MSG_TYPES.put("a", "QuoteAck");
        MSG_TYPES.put("b", "MassQuote");
        MSG_TYPES.put("c", "MassQuoteAck");

        // Application messages - Trade
        MSG_TYPES.put("D", "NewOrderSingle");
        MSG_TYPES.put("E", "NewOrderList");
        MSG_TYPES.put("F", "OrderCancelRequest");
        MSG_TYPES.put("G", "OrderCancelReplaceRequest");
        MSG_TYPES.put("H", "OrderStatusRequest");
        MSG_TYPES.put("J", "AllocationInstruction");
        MSG_TYPES.put("K", "ListCancelRequest");
        MSG_TYPES.put("L", "ListExecute");
        MSG_TYPES.put("M", "ListStatusRequest");
        MSG_TYPES.put("N", "ListStatus");
        MSG_TYPES.put("P", "AllocationInstructionAck");
        MSG_TYPES.put("Q", "DontKnowTrade");

        // Application messages - Execution
        MSG_TYPES.put("8", "ExecutionReport");
        MSG_TYPES.put("9", "OrderCancelReject");

        // Application messages - Post-trade
        MSG_TYPES.put("AT", "Confirmation");
        MSG_TYPES.put("AU", "ConfirmationAck");
        MSG_TYPES.put("AV", "ConfirmationRequest");
        MSG_TYPES.put("AZ", "CollateralRequest");
        MSG_TYPES.put("BA", "CollateralAssignment");
        MSG_TYPES.put("BB", "CollateralResponse");
        MSG_TYPES.put("BC", "CollateralReport");
        MSG_TYPES.put("BD", "CollateralInquiry");
        MSG_TYPES.put("BG", "CollateralInquiryAck");

        // Application messages - Market Data
        MSG_TYPES.put("V", "MarketDataRequest");
        MSG_TYPES.put("W", "MarketDataSnapshotFullRefresh");
        MSG_TYPES.put("X", "MarketDataIncrementalRefresh");
        MSG_TYPES.put("Y", "MarketDataRequestReject");

        // Application messages - Security
        MSG_TYPES.put("x", "SecurityListRequest");
        MSG_TYPES.put("y", "SecurityList");
        MSG_TYPES.put("z", "SecurityStatusRequest");
        MSG_TYPES.put("f", "SecurityStatus");
        MSG_TYPES.put("d", "SecurityDefinitionRequest");
        MSG_TYPES.put("e", "SecurityDefinition");

        // Application messages - Business
        MSG_TYPES.put("j", "BusinessMessageReject");

        // Application messages - Trading Session
        MSG_TYPES.put("g", "TradingSessionStatusRequest");
        MSG_TYPES.put("h", "TradingSessionStatus");

        // Application messages - News
        MSG_TYPES.put("B", "News");

        // Application messages - Position
        MSG_TYPES.put("AM", "PositionMaintenanceRequest");
        MSG_TYPES.put("AN", "PositionMaintenanceReport");
        MSG_TYPES.put("AO", "RequestForPositions");
        MSG_TYPES.put("AP", "RequestForPositionsAck");
        MSG_TYPES.put("AQ", "PositionReport");

        // Special
        MSG_TYPES.put("EOD", "EndOfDay");
    }

    /**
     * Decode a message type code to a human-readable name.
     *
     * @param msgType the FIX message type code
     * @return the human-readable name, or the original code if unknown
     */
    public static String decode(String msgType) {
        if (msgType == null) return "Unknown";
        String name = MSG_TYPES.get(msgType);
        return name != null ? name : msgType;
    }

    /**
     * Get a formatted string with both code and name.
     *
     * @param msgType the FIX message type code
     * @return formatted string like "8 (ExecutionReport)"
     */
    public static String decodeWithCode(String msgType) {
        if (msgType == null) return "Unknown";
        String name = MSG_TYPES.get(msgType);
        if (name != null) {
            return msgType + " (" + name + ")";
        }
        return msgType;
    }

    /**
     * Check if a message type is an admin/session message.
     */
    public static boolean isAdminMessage(String msgType) {
        return "0".equals(msgType) || "1".equals(msgType) || "2".equals(msgType) ||
               "3".equals(msgType) || "4".equals(msgType) || "5".equals(msgType) ||
               "A".equals(msgType);
    }

    /**
     * Get all known message types.
     */
    public static Map<String, String> getAllMessageTypes() {
        return new HashMap<>(MSG_TYPES);
    }
}
