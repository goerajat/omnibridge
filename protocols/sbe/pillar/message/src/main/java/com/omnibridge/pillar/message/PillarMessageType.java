package com.omnibridge.pillar.message;

import com.omnibridge.sbe.message.SbeMessageType;

/**
 * NYSE Pillar message types.
 * <p>
 * Defines all message types for the Pillar binary protocol including:
 * <ul>
 *   <li>Stream Protocol messages (Login, LoginResponse, StreamAvail, etc.)</li>
 *   <li>Order entry messages (NewOrder, CancelOrder, etc.)</li>
 *   <li>Execution messages (ExecutionReport, OrderAck, etc.)</li>
 * </ul>
 * <p>
 * Message types are grouped by category:
 * <ul>
 *   <li>1-99: Stream Protocol messages</li>
 *   <li>100-199: Session/Admin messages</li>
 *   <li>200-299: Order Entry messages (TG - Trader to Gateway)</li>
 *   <li>300-399: Execution messages (GT - Gateway to Trader)</li>
 * </ul>
 */
public enum PillarMessageType implements SbeMessageType {

    // Stream Protocol Messages (1-99)
    LOGIN(1, "Login", Direction.INBOUND, 72),
    LOGIN_RESPONSE(2, "LoginResponse", Direction.OUTBOUND, 24),
    STREAM_AVAIL(3, "StreamAvail", Direction.OUTBOUND, 24),
    OPEN(4, "Open", Direction.INBOUND, 32),
    OPEN_RESPONSE(5, "OpenResponse", Direction.OUTBOUND, 16),
    CLOSE(6, "Close", Direction.INBOUND, 16),
    CLOSE_RESPONSE(7, "CloseResponse", Direction.OUTBOUND, 12),
    HEARTBEAT(8, "Heartbeat", Direction.BIDIRECTIONAL, 8),
    SESSION_CONFIG_REQUEST(9, "SessionConfigRequest", Direction.INBOUND, 16),
    SESSION_CONFIG_RESPONSE(10, "SessionConfigResponse", Direction.OUTBOUND, 16),

    // Session/Admin Messages (100-199)
    TEST_REQUEST(100, "TestRequest", Direction.BIDIRECTIONAL, 8),
    REJECT(101, "Reject", Direction.OUTBOUND, 48),
    SEQUENCE_RESET(102, "SequenceReset", Direction.BIDIRECTIONAL, 16),
    RESEND_REQUEST(103, "ResendRequest", Direction.INBOUND, 24),

    // Order Entry Messages - TG Stream (200-299)
    NEW_ORDER(200, "NewOrder", Direction.INBOUND, 128),
    CANCEL_ORDER(201, "CancelOrder", Direction.INBOUND, 64),
    CANCEL_REPLACE(202, "CancelReplace", Direction.INBOUND, 144),
    MODIFY_ORDER(203, "ModifyOrder", Direction.INBOUND, 96),
    MASS_CANCEL(204, "MassCancel", Direction.INBOUND, 48),

    // Execution Messages - GT Stream (300-399)
    ORDER_ACK(300, "OrderAck", Direction.OUTBOUND, 80),
    ORDER_REJECT(301, "OrderReject", Direction.OUTBOUND, 96),
    CANCEL_ACK(302, "CancelAck", Direction.OUTBOUND, 64),
    CANCEL_REJECT(303, "CancelReject", Direction.OUTBOUND, 80),
    REPLACE_ACK(304, "ReplaceAck", Direction.OUTBOUND, 88),
    REPLACE_REJECT(305, "ReplaceReject", Direction.OUTBOUND, 96),
    EXECUTION_REPORT(306, "ExecutionReport", Direction.OUTBOUND, 144),
    TRADE_CANCEL(307, "TradeCancel", Direction.OUTBOUND, 96),
    TRADE_CORRECT(308, "TradeCorrect", Direction.OUTBOUND, 112),
    ORDER_STATUS(309, "OrderStatus", Direction.OUTBOUND, 104),
    MASS_CANCEL_ACK(310, "MassCancelAck", Direction.OUTBOUND, 48),
    MASS_CANCEL_REJECT(311, "MassCancelReject", Direction.OUTBOUND, 64),

    // Unknown/Unsupported
    UNKNOWN(-1, "Unknown", Direction.BIDIRECTIONAL, 0);

    private final int templateId;
    private final String name;
    private final Direction direction;
    private final int blockLength;

    PillarMessageType(int templateId, String name, Direction direction, int blockLength) {
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
        return PillarMessage.SCHEMA_ID;
    }

    /**
     * Checks if this is a stream protocol message.
     */
    public boolean isStreamProtocol() {
        return templateId >= 1 && templateId <= 99;
    }

    /**
     * Checks if this is a session/admin message.
     */
    public boolean isSessionMessage() {
        return templateId >= 100 && templateId <= 199;
    }

    /**
     * Checks if this is an order entry message (TG stream).
     */
    public boolean isOrderEntry() {
        return templateId >= 200 && templateId <= 299;
    }

    /**
     * Checks if this is an execution message (GT stream).
     */
    public boolean isExecution() {
        return templateId >= 300 && templateId <= 399;
    }

    /**
     * Checks if this message requires sequencing (SeqMsg header).
     */
    public boolean isSequenced() {
        return isOrderEntry() || isExecution();
    }

    /**
     * Gets the message type for a given template ID.
     *
     * @param templateId the template ID
     * @return the message type, or UNKNOWN if not found
     */
    public static PillarMessageType fromTemplateId(int templateId) {
        for (PillarMessageType type : values()) {
            if (type.templateId == templateId) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
