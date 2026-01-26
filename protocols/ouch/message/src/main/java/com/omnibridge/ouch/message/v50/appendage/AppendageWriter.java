package com.omnibridge.ouch.message.v50.appendage;

import org.agrona.MutableDirectBuffer;

import java.util.List;

/**
 * Writer for OUCH 5.0 message appendages.
 *
 * <p>Provides methods to write appendages to a buffer.</p>
 */
public class AppendageWriter {

    private final PegAppendage pegAppendage = new PegAppendage();
    private final ReserveAppendage reserveAppendage = new ReserveAppendage();
    private final DiscretionAppendage discretionAppendage = new DiscretionAppendage();

    /**
     * Write a list of appendages to a buffer.
     *
     * @param buffer the target buffer
     * @param offset the starting offset
     * @param appendages the appendages to write
     * @return the total number of bytes written
     */
    public int write(MutableDirectBuffer buffer, int offset, List<Appendage> appendages) {
        int currentOffset = offset;
        for (Appendage appendage : appendages) {
            int length = appendage.getTotalLength();
            buffer.putBytes(currentOffset, appendage.buffer, appendage.offset, length);
            currentOffset += length;
        }
        return currentOffset - offset;
    }

    /**
     * Create and write a peg appendage.
     *
     * @param buffer the target buffer
     * @param offset the starting offset
     * @param pegType the peg type (P=Primary, M=Midpoint, A=Market)
     * @param priceOffset the price offset in micros
     * @return the appendage instance
     */
    public PegAppendage writePeg(MutableDirectBuffer buffer, int offset, char pegType, int priceOffset) {
        pegAppendage.wrapForWriting(buffer, offset);
        pegAppendage.setPegType(pegType);
        pegAppendage.setPriceOffset(priceOffset);
        return pegAppendage;
    }

    /**
     * Create and write a reserve appendage.
     *
     * @param buffer the target buffer
     * @param offset the starting offset
     * @param displayQty the display quantity
     * @param replenishQty the replenish quantity
     * @return the appendage instance
     */
    public ReserveAppendage writeReserve(MutableDirectBuffer buffer, int offset,
                                         int displayQty, int replenishQty) {
        reserveAppendage.wrapForWriting(buffer, offset);
        reserveAppendage.setDisplayQuantity(displayQty);
        reserveAppendage.setReplenishQuantity(replenishQty);
        return reserveAppendage;
    }

    /**
     * Create and write a discretion appendage.
     *
     * @param buffer the target buffer
     * @param offset the starting offset
     * @param discretionPrice the discretion price in micros
     * @return the appendage instance
     */
    public DiscretionAppendage writeDiscretion(MutableDirectBuffer buffer, int offset,
                                                int discretionPrice) {
        discretionAppendage.wrapForWriting(buffer, offset);
        discretionAppendage.setDiscretionPrice(discretionPrice);
        return discretionAppendage;
    }

    /**
     * Calculate the total length needed for a list of appendages.
     *
     * @param appendages the appendages
     * @return total length in bytes
     */
    public static int calculateTotalLength(List<Appendage> appendages) {
        int total = 0;
        for (Appendage appendage : appendages) {
            total += appendage.getTotalLength();
        }
        return total;
    }

    /**
     * Get the length for a specific appendage type.
     *
     * @param type the appendage type
     * @return the total length (header + data)
     */
    public static int getLengthForType(AppendageType type) {
        return Appendage.HEADER_SIZE + type.getMinDataLength();
    }

    // Direct accessors for reusable instances
    public PegAppendage getPegAppendage() { return pegAppendage; }
    public ReserveAppendage getReserveAppendage() { return reserveAppendage; }
    public DiscretionAppendage getDiscretionAppendage() { return discretionAppendage; }
}
