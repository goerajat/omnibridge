package com.omnibridge.ouch.message;

/**
 * OUCH protocol message types.
 *
 * <p>Based on NASDAQ OUCH 4.2/5.0 specification. Message types are single ASCII characters
 * that identify the message format.</p>
 *
 * <p>Inbound messages are sent from the client to the exchange.
 * Outbound messages are sent from the exchange to the client.</p>
 */
public enum OuchMessageType {

    // =====================================================
    // Inbound Messages (Client -> Exchange)
    // =====================================================

    /** Enter a new order */
    ENTER_ORDER('O', Direction.INBOUND, "Enter Order"),

    /** Replace an existing order */
    REPLACE_ORDER('U', Direction.INBOUND, "Replace Order"),

    /** Cancel an existing order */
    CANCEL_ORDER('X', Direction.INBOUND, "Cancel Order"),

    /** Modify an order (OUCH 5.0) */
    MODIFY_ORDER('M', Direction.INBOUND, "Modify Order"),

    /** Cancel orders by order ID */
    CANCEL_BY_ORDER_ID('Y', Direction.INBOUND, "Cancel By Order ID"),

    // =====================================================
    // Outbound Messages (Exchange -> Client)
    // =====================================================

    /** System event notification */
    SYSTEM_EVENT('S', Direction.OUTBOUND, "System Event"),

    /** Order accepted confirmation */
    ORDER_ACCEPTED('A', Direction.OUTBOUND, "Order Accepted"),

    /** Order replaced confirmation */
    ORDER_REPLACED('U', Direction.OUTBOUND, "Order Replaced"),

    /** Order canceled confirmation */
    ORDER_CANCELED('C', Direction.OUTBOUND, "Order Canceled"),

    /** Unsolicited order cancel (AIQ) */
    AIQ_CANCELED('D', Direction.OUTBOUND, "AIQ Canceled"),

    /** Order executed (fill) */
    ORDER_EXECUTED('E', Direction.OUTBOUND, "Order Executed"),

    /** Broken trade notification */
    BROKEN_TRADE('B', Direction.OUTBOUND, "Broken Trade"),

    /** Order rejected */
    ORDER_REJECTED('J', Direction.OUTBOUND, "Order Rejected"),

    /** Cancel pending notification */
    CANCEL_PENDING('P', Direction.OUTBOUND, "Cancel Pending"),

    /** Cancel reject notification */
    CANCEL_REJECT('I', Direction.OUTBOUND, "Cancel Reject"),

    /** Order priority update */
    PRIORITY_UPDATE('T', Direction.OUTBOUND, "Priority Update"),

    /** Order modified confirmation */
    ORDER_MODIFIED('M', Direction.OUTBOUND, "Order Modified"),

    /** Order restated notification */
    ORDER_RESTATED('R', Direction.OUTBOUND, "Order Restated"),

    /** Account query response */
    ACCOUNT_QUERY_RESPONSE('Q', Direction.OUTBOUND, "Account Query Response"),

    /** Unknown message type */
    UNKNOWN('?', Direction.UNKNOWN, "Unknown");

    /**
     * Message direction enum.
     */
    public enum Direction {
        /** Client to exchange */
        INBOUND,
        /** Exchange to client */
        OUTBOUND,
        /** Unknown direction */
        UNKNOWN
    }

    private final char code;
    private final Direction direction;
    private final String description;

    OuchMessageType(char code, Direction direction, String description) {
        this.code = code;
        this.direction = direction;
        this.description = description;
    }

    /**
     * Get the single-character message type code.
     */
    public char getCode() {
        return code;
    }

    /**
     * Get the byte value of the message type code.
     */
    public byte getCodeByte() {
        return (byte) code;
    }

    /**
     * Get the message direction.
     */
    public Direction getDirection() {
        return direction;
    }

    /**
     * Get a human-readable description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Check if this is an inbound message type.
     */
    public boolean isInbound() {
        return direction == Direction.INBOUND;
    }

    /**
     * Check if this is an outbound message type.
     */
    public boolean isOutbound() {
        return direction == Direction.OUTBOUND;
    }

    /**
     * Lookup message type by code character.
     *
     * @param code the single-character type code
     * @param isInbound true if expecting inbound message
     * @return the message type, or UNKNOWN if not found
     */
    public static OuchMessageType fromCode(char code, boolean isInbound) {
        for (OuchMessageType type : values()) {
            if (type.code == code) {
                // Handle 'U' and 'M' which have both inbound and outbound meanings
                if (code == 'U') {
                    return isInbound ? REPLACE_ORDER : ORDER_REPLACED;
                }
                if (code == 'M') {
                    return isInbound ? MODIFY_ORDER : ORDER_MODIFIED;
                }
                return type;
            }
        }
        return UNKNOWN;
    }

    /**
     * Lookup message type by code byte.
     *
     * @param code the type code byte
     * @param isInbound true if expecting inbound message
     * @return the message type, or UNKNOWN if not found
     */
    public static OuchMessageType fromCode(byte code, boolean isInbound) {
        return fromCode((char) (code & 0xFF), isInbound);
    }

    /**
     * Lookup outbound message type by code.
     */
    public static OuchMessageType fromOutboundCode(byte code) {
        return fromCode(code, false);
    }

    /**
     * Lookup inbound message type by code.
     */
    public static OuchMessageType fromInboundCode(byte code) {
        return fromCode(code, true);
    }

    @Override
    public String toString() {
        return name() + "(" + code + ")";
    }
}
