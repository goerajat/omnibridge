package com.fixengine.ouch.message.v50.appendage;

import org.agrona.MutableDirectBuffer;

/**
 * OUCH 5.0 Reserve appendage - allows reserve/display quantity settings.
 *
 * <p>Data format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       4       Display Quantity (unsigned int)
 * 4       4       Replenish Quantity (unsigned int)
 * Total: 8 bytes
 * </pre>
 */
public class ReserveAppendage extends Appendage {

    public static final int DATA_LENGTH = 8;

    public static final int DISPLAY_QTY_OFFSET = 0;
    public static final int REPLENISH_QTY_OFFSET = 4;

    @Override
    public AppendageType getType() {
        return AppendageType.RESERVE;
    }

    /**
     * Initialize for writing.
     */
    public ReserveAppendage wrapForWriting(MutableDirectBuffer buffer, int offset) {
        super.wrapForWriting(buffer, offset, DATA_LENGTH);
        return this;
    }

    // =====================================================
    // Field getters
    // =====================================================

    /**
     * Get the display quantity.
     */
    public int getDisplayQuantity() {
        return getDataInt(DISPLAY_QTY_OFFSET);
    }

    /**
     * Get the display quantity as unsigned.
     */
    public long getDisplayQuantityUnsigned() {
        return getDataInt(DISPLAY_QTY_OFFSET) & 0xFFFFFFFFL;
    }

    /**
     * Get the replenish quantity.
     */
    public int getReplenishQuantity() {
        return getDataInt(REPLENISH_QTY_OFFSET);
    }

    /**
     * Get the replenish quantity as unsigned.
     */
    public long getReplenishQuantityUnsigned() {
        return getDataInt(REPLENISH_QTY_OFFSET) & 0xFFFFFFFFL;
    }

    // =====================================================
    // Field setters
    // =====================================================

    /**
     * Set the display quantity.
     */
    public ReserveAppendage setDisplayQuantity(int displayQty) {
        putDataInt(DISPLAY_QTY_OFFSET, displayQty);
        return this;
    }

    /**
     * Set the replenish quantity.
     */
    public ReserveAppendage setReplenishQuantity(int replenishQty) {
        putDataInt(REPLENISH_QTY_OFFSET, replenishQty);
        return this;
    }

    @Override
    public String toString() {
        return String.format("Reserve{display=%d, replenish=%d}",
                getDisplayQuantity(), getReplenishQuantity());
    }
}
