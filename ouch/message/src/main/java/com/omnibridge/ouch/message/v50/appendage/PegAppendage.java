package com.omnibridge.ouch.message.v50.appendage;

import org.agrona.MutableDirectBuffer;

/**
 * OUCH 5.0 Peg appendage - allows orders to peg to market prices.
 *
 * <p>Data format:</p>
 * <pre>
 * Offset  Length  Field
 * 0       1       Peg Type (P=Primary, M=Midpoint, A=Market)
 * 1       4       Offset (signed, price * 10000)
 * Total: 5 bytes
 * </pre>
 */
public class PegAppendage extends Appendage {

    public static final int DATA_LENGTH = 5;

    public static final int PEG_TYPE_OFFSET = 0;
    public static final int PRICE_OFFSET_OFFSET = 1;

    // Peg Type values
    public static final char PEG_PRIMARY = 'P';    // Peg to primary quote (NBBO)
    public static final char PEG_MIDPOINT = 'M';   // Peg to midpoint
    public static final char PEG_MARKET = 'A';     // Peg to market

    @Override
    public AppendageType getType() {
        return AppendageType.PEG;
    }

    /**
     * Initialize for writing.
     */
    public PegAppendage wrapForWriting(MutableDirectBuffer buffer, int offset) {
        super.wrapForWriting(buffer, offset, DATA_LENGTH);
        return this;
    }

    // =====================================================
    // Field getters
    // =====================================================

    /**
     * Get the peg type.
     */
    public char getPegType() {
        return (char) getDataByte(PEG_TYPE_OFFSET);
    }

    /**
     * Get the price offset in micros (1/10000 of a dollar).
     */
    public int getPriceOffset() {
        return getDataInt(PRICE_OFFSET_OFFSET);
    }

    /**
     * Get the price offset as a double.
     */
    public double getPriceOffsetAsDouble() {
        return getPriceOffset() / 10000.0;
    }

    /**
     * Check if this is a primary peg (NBBO).
     */
    public boolean isPrimaryPeg() {
        return getPegType() == PEG_PRIMARY;
    }

    /**
     * Check if this is a midpoint peg.
     */
    public boolean isMidpointPeg() {
        return getPegType() == PEG_MIDPOINT;
    }

    /**
     * Check if this is a market peg.
     */
    public boolean isMarketPeg() {
        return getPegType() == PEG_MARKET;
    }

    // =====================================================
    // Field setters
    // =====================================================

    /**
     * Set the peg type.
     */
    public PegAppendage setPegType(char pegType) {
        putDataByte(PEG_TYPE_OFFSET, (byte) pegType);
        return this;
    }

    /**
     * Set the price offset in micros.
     */
    public PegAppendage setPriceOffset(int offsetInMicros) {
        putDataInt(PRICE_OFFSET_OFFSET, offsetInMicros);
        return this;
    }

    /**
     * Set the price offset from dollars.
     */
    public PegAppendage setPriceOffset(double offset) {
        putDataInt(PRICE_OFFSET_OFFSET, (int) (offset * 10000));
        return this;
    }

    @Override
    public String toString() {
        return String.format("Peg{type=%c, offset=%.4f}", getPegType(), getPriceOffsetAsDouble());
    }
}
