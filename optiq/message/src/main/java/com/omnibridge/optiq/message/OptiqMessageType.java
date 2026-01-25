package com.omnibridge.optiq.message;

import com.omnibridge.sbe.message.SbeMessageType;

/**
 * Euronext Optiq message types.
 * <p>
 * Defines all message types for the Optiq Order Entry Gateway (OEG) protocol:
 * <ul>
 *   <li>Session messages (Logon, LogonAck, Logout, Heartbeat, etc.)</li>
 *   <li>Order entry messages (NewOrder, ModifyOrder, CancelOrder, etc.)</li>
 *   <li>Execution messages (ExecutionReport, Reject, etc.)</li>
 * </ul>
 */
public enum OptiqMessageType implements SbeMessageType {

    // Session Messages
    LOGON(100, "Logon", Direction.INBOUND, 48),
    LOGON_ACK(101, "LogonAck", Direction.OUTBOUND, 32),
    LOGON_REJECT(102, "LogonReject", Direction.OUTBOUND, 36),
    LOGOUT(103, "Logout", Direction.BIDIRECTIONAL, 24),
    HEARTBEAT(104, "Heartbeat", Direction.BIDIRECTIONAL, 8),
    TEST_REQUEST(105, "TestRequest", Direction.BIDIRECTIONAL, 8),

    // Order Entry Messages
    NEW_ORDER(200, "NewOrder", Direction.INBOUND, 96),
    MODIFY_ORDER(201, "ModifyOrder", Direction.INBOUND, 104),
    CANCEL_ORDER(202, "CancelOrder", Direction.INBOUND, 48),
    MASS_CANCEL(203, "MassCancel", Direction.INBOUND, 40),

    // Quote Messages
    QUOTE(210, "Quote", Direction.INBOUND, 80),
    QUOTE_ACK(211, "QuoteAck", Direction.OUTBOUND, 48),

    // Execution Messages
    EXECUTION_REPORT(300, "ExecutionReport", Direction.OUTBOUND, 120),
    REJECT(301, "Reject", Direction.OUTBOUND, 64),
    CANCEL_REJECT(302, "CancelReject", Direction.OUTBOUND, 56),
    TRADE_BUST_NOTIFICATION(303, "TradeBustNotification", Direction.OUTBOUND, 80),

    // Reference Data
    SECURITY_DEFINITION(400, "SecurityDefinition", Direction.OUTBOUND, 160),
    SECURITY_STATUS(401, "SecurityStatus", Direction.OUTBOUND, 48),

    // Unknown
    UNKNOWN(-1, "Unknown", Direction.BIDIRECTIONAL, 0);

    private final int templateId;
    private final String name;
    private final Direction direction;
    private final int blockLength;

    OptiqMessageType(int templateId, String name, Direction direction, int blockLength) {
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
        return OptiqMessage.SCHEMA_ID;
    }

    /**
     * Gets the message type for a given template ID.
     *
     * @param templateId the template ID
     * @return the message type, or UNKNOWN if not found
     */
    public static OptiqMessageType fromTemplateId(int templateId) {
        for (OptiqMessageType type : values()) {
            if (type.templateId == templateId) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
