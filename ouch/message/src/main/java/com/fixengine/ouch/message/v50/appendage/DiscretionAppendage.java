package com.fixengine.ouch.message.v50.appendage;

import org.agrona.MutableDirectBuffer;

/**
 * OUCH 5.0 Discretion appendage - allows discretionary pricing.
 *
 * <p>A discretionary order allows the exchange to execute at a price
 * up to (for buys) or down to (for sells) the discretion price.</p>
 *
 * <p>Data format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       4       Discretion Price (signed int, price * 10000)
 * Total: 4 bytes
 * </pre>
 */
public class DiscretionAppendage extends Appendage {

    public static final int DATA_LENGTH = 4;

    public static final int PRICE_OFFSET = 0;

    @Override
    public AppendageType getType() {
        return AppendageType.DISCRETION;
    }

    /**
     * Initialize for writing.
     */
    public DiscretionAppendage wrapForWriting(MutableDirectBuffer buffer, int offset) {
        super.wrapForWriting(buffer, offset, DATA_LENGTH);
        return this;
    }

    // =====================================================
    // Field getters
    // =====================================================

    /**
     * Get the discretion price in micros (1/10000 of a dollar).
     */
    public int getDiscretionPrice() {
        return getDataInt(PRICE_OFFSET);
    }

    /**
     * Get the discretion price as a double.
     */
    public double getDiscretionPriceAsDouble() {
        return getDiscretionPrice() / 10000.0;
    }

    // =====================================================
    // Field setters
    // =====================================================

    /**
     * Set the discretion price in micros.
     */
    public DiscretionAppendage setDiscretionPrice(int priceInMicros) {
        putDataInt(PRICE_OFFSET, priceInMicros);
        return this;
    }

    /**
     * Set the discretion price from dollars.
     */
    public DiscretionAppendage setDiscretionPrice(double price) {
        putDataInt(PRICE_OFFSET, (int) (price * 10000));
        return this;
    }

    @Override
    public String toString() {
        return String.format("Discretion{price=%.4f}", getDiscretionPriceAsDouble());
    }
}
