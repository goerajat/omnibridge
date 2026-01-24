package com.fixengine.ouch.message.v50.appendage;

/**
 * OUCH 5.0 appendage types.
 *
 * <p>Appendages are optional extensions to OUCH 5.0 messages that provide
 * additional order instruction capabilities like pegging, reserves, and
 * discretionary pricing.</p>
 */
public enum AppendageType {

    /**
     * Peg appendage - allows orders to peg to market prices.
     */
    PEG((byte) 0x01, "Peg"),

    /**
     * Reserve appendage - allows reserve/display quantity settings.
     */
    RESERVE((byte) 0x02, "Reserve"),

    /**
     * Discretion appendage - allows discretionary pricing.
     */
    DISCRETION((byte) 0x03, "Discretion"),

    /**
     * Random reserve appendage - randomized reserve replenishment.
     */
    RANDOM_RESERVE((byte) 0x04, "Random Reserve"),

    /**
     * Route appendage - routing instructions.
     */
    ROUTE((byte) 0x05, "Route"),

    /**
     * Group ID appendage - order grouping.
     */
    GROUP_ID((byte) 0x06, "Group ID"),

    /**
     * Self-trade prevention appendage.
     */
    SELF_TRADE_PREVENTION((byte) 0x07, "Self Trade Prevention"),

    /**
     * Unknown appendage type.
     */
    UNKNOWN((byte) 0xFF, "Unknown");

    private final byte tag;
    private final String description;

    AppendageType(byte tag, String description) {
        this.tag = tag;
        this.description = description;
    }

    /**
     * Get the appendage tag byte.
     */
    public byte getTag() {
        return tag;
    }

    /**
     * Get the appendage description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get the minimum data length for this appendage type.
     */
    public int getMinDataLength() {
        return switch (this) {
            case PEG -> 5;          // 1 (peg type) + 4 (offset)
            case RESERVE -> 8;      // 4 (display qty) + 4 (replenish qty)
            case DISCRETION -> 4;   // 4 (price)
            case RANDOM_RESERVE -> 12; // 4 (display) + 4 (min) + 4 (max)
            case ROUTE -> 4;        // 4 (routing firm)
            case GROUP_ID -> 8;     // 8 (group ID)
            case SELF_TRADE_PREVENTION -> 5; // 1 (action) + 4 (group ID)
            default -> 0;
        };
    }

    /**
     * Parse appendage type from tag byte.
     *
     * @param tag the tag byte
     * @return the appendage type, or UNKNOWN if not recognized
     */
    public static AppendageType fromTag(byte tag) {
        for (AppendageType type : values()) {
            if (type.tag == tag) {
                return type;
            }
        }
        return UNKNOWN;
    }

    @Override
    public String toString() {
        return description + " (0x" + String.format("%02X", tag) + ")";
    }
}
