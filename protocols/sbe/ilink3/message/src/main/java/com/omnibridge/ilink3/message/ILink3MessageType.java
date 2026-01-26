package com.omnibridge.ilink3.message;

import com.omnibridge.sbe.message.SbeMessageType;

/**
 * CME iLink 3 message types.
 * <p>
 * Defines all message types for the iLink 3 protocol including:
 * <ul>
 *   <li>Session messages (Negotiate, Establish, Terminate, etc.)</li>
 *   <li>Order entry messages (NewOrderSingle, OrderCancelRequest, etc.)</li>
 *   <li>Execution messages (ExecutionReport, etc.)</li>
 * </ul>
 */
public enum ILink3MessageType implements SbeMessageType {

    // Session Messages
    NEGOTIATE(500, "Negotiate", Direction.INBOUND, 76),
    NEGOTIATION_RESPONSE(501, "NegotiationResponse", Direction.OUTBOUND, 30),
    NEGOTIATION_REJECT(502, "NegotiationReject", Direction.OUTBOUND, 69),
    ESTABLISH(503, "Establish", Direction.INBOUND, 84),
    ESTABLISHMENT_ACK(504, "EstablishmentAck", Direction.OUTBOUND, 34),
    ESTABLISHMENT_REJECT(505, "EstablishmentReject", Direction.OUTBOUND, 69),
    TERMINATE(507, "Terminate", Direction.BIDIRECTIONAL, 70),
    SEQUENCE(508, "Sequence", Direction.BIDIRECTIONAL, 12),
    RETRANSMIT_REQUEST(509, "RetransmitRequest", Direction.INBOUND, 16),
    RETRANSMISSION(510, "Retransmission", Direction.OUTBOUND, 14),
    NOT_APPLIED(513, "NotApplied", Direction.OUTBOUND, 16),

    // Order Entry Messages
    NEW_ORDER_SINGLE(514, "NewOrderSingle", Direction.INBOUND, 118),
    ORDER_CANCEL_REPLACE_REQUEST(515, "OrderCancelReplaceRequest", Direction.INBOUND, 130),
    ORDER_CANCEL_REQUEST(516, "OrderCancelRequest", Direction.INBOUND, 62),
    MASS_QUOTE(517, "MassQuote", Direction.INBOUND, 54),
    PARTY_DETAILS_LIST_REQUEST(518, "PartyDetailsListRequest", Direction.INBOUND, 16),
    PARTY_DETAILS_LIST_REPORT(519, "PartyDetailsListReport", Direction.OUTBOUND, 70),
    QUOTE_CANCEL(528, "QuoteCancel", Direction.INBOUND, 38),

    // Execution Messages
    EXECUTION_REPORT_NEW(522, "ExecutionReportNew", Direction.OUTBOUND, 142),
    EXECUTION_REPORT_REJECT(523, "ExecutionReportReject", Direction.OUTBOUND, 102),
    EXECUTION_REPORT_ELIMINATION(524, "ExecutionReportElimination", Direction.OUTBOUND, 102),
    EXECUTION_REPORT_TRADE_OUTRIGHT(525, "ExecutionReportTradeOutright", Direction.OUTBOUND, 166),
    EXECUTION_REPORT_TRADE_SPREAD(526, "ExecutionReportTradeSpread", Direction.OUTBOUND, 174),
    EXECUTION_REPORT_TRADE_SPREAD_LEG(527, "ExecutionReportTradeSpreadLeg", Direction.OUTBOUND, 78),
    EXECUTION_REPORT_MODIFY(531, "ExecutionReportModify", Direction.OUTBOUND, 142),
    EXECUTION_REPORT_STATUS(532, "ExecutionReportStatus", Direction.OUTBOUND, 142),
    EXECUTION_REPORT_CANCEL(534, "ExecutionReportCancel", Direction.OUTBOUND, 102),

    // Business Reject
    BUSINESS_REJECT(521, "BusinessReject", Direction.OUTBOUND, 42),

    // Unknown/Unsupported
    UNKNOWN(-1, "Unknown", Direction.BIDIRECTIONAL, 0);

    private final int templateId;
    private final String name;
    private final Direction direction;
    private final int blockLength;

    ILink3MessageType(int templateId, String name, Direction direction, int blockLength) {
        this.templateId = templateId;
        this.name = name;
        this.direction = direction;
        this.blockLength = blockLength;
    }

    @Override
    public int getTemplateId() {
        return templateId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Direction getDirection() {
        return direction;
    }

    @Override
    public int getBlockLength() {
        return blockLength;
    }

    @Override
    public int getSchemaId() {
        return ILink3Message.SCHEMA_ID;
    }

    /**
     * Gets the message type for a given template ID.
     *
     * @param templateId the template ID
     * @return the message type, or UNKNOWN if not found
     */
    public static ILink3MessageType fromTemplateId(int templateId) {
        for (ILink3MessageType type : values()) {
            if (type.templateId == templateId) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
