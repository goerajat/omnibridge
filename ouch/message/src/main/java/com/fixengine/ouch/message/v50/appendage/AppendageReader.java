package com.fixengine.ouch.message.v50.appendage;

import org.agrona.DirectBuffer;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for OUCH 5.0 message appendages.
 *
 * <p>Parses appendages from a buffer using flyweight pattern.</p>
 */
public class AppendageReader {

    // Flyweight instances for reading
    private final PegAppendage pegAppendage = new PegAppendage();
    private final ReserveAppendage reserveAppendage = new ReserveAppendage();
    private final DiscretionAppendage discretionAppendage = new DiscretionAppendage();

    /**
     * Read a single appendage from the buffer.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @return the parsed appendage, or null if unrecognized type
     */
    public Appendage read(DirectBuffer buffer, int offset) {
        if (buffer.capacity() <= offset) {
            return null;
        }

        byte tag = buffer.getByte(offset);
        AppendageType type = AppendageType.fromTag(tag);

        Appendage appendage = getAppendageForType(type);
        if (appendage != null) {
            appendage.wrapForReading(buffer, offset);
        }
        return appendage;
    }

    /**
     * Read all appendages from a buffer section.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param count the number of appendages to read
     * @return list of parsed appendages
     */
    public List<Appendage> readAll(DirectBuffer buffer, int offset, int count) {
        List<Appendage> appendages = new ArrayList<>(count);
        int currentOffset = offset;

        for (int i = 0; i < count; i++) {
            if (currentOffset >= buffer.capacity()) {
                break;
            }

            byte tag = buffer.getByte(currentOffset);
            int dataLength = buffer.getShort(currentOffset + Appendage.LENGTH_OFFSET, ByteOrder.BIG_ENDIAN) & 0xFFFF;
            int totalLength = Appendage.HEADER_SIZE + dataLength;

            AppendageType type = AppendageType.fromTag(tag);
            Appendage appendage = createAppendageForType(type);
            if (appendage != null) {
                appendage.wrapForReading(buffer, currentOffset);
                appendages.add(appendage);
            }

            currentOffset += totalLength;
        }

        return appendages;
    }

    /**
     * Peek at the appendage type at the given offset.
     *
     * @param buffer the source buffer
     * @param offset the offset
     * @return the appendage type
     */
    public AppendageType peekType(DirectBuffer buffer, int offset) {
        if (buffer.capacity() <= offset) {
            return AppendageType.UNKNOWN;
        }
        return AppendageType.fromTag(buffer.getByte(offset));
    }

    /**
     * Get the total length of all appendages starting at the given offset.
     *
     * @param buffer the source buffer
     * @param offset the starting offset
     * @param count the number of appendages
     * @return total length in bytes
     */
    public int getTotalLength(DirectBuffer buffer, int offset, int count) {
        int totalLength = 0;
        int currentOffset = offset;

        for (int i = 0; i < count && currentOffset < buffer.capacity(); i++) {
            int dataLength = buffer.getShort(currentOffset + Appendage.LENGTH_OFFSET, ByteOrder.BIG_ENDIAN) & 0xFFFF;
            int appendageLength = Appendage.HEADER_SIZE + dataLength;
            totalLength += appendageLength;
            currentOffset += appendageLength;
        }

        return totalLength;
    }

    private Appendage getAppendageForType(AppendageType type) {
        return switch (type) {
            case PEG -> pegAppendage;
            case RESERVE -> reserveAppendage;
            case DISCRETION -> discretionAppendage;
            default -> null;
        };
    }

    private Appendage createAppendageForType(AppendageType type) {
        return switch (type) {
            case PEG -> new PegAppendage();
            case RESERVE -> new ReserveAppendage();
            case DISCRETION -> new DiscretionAppendage();
            default -> null;
        };
    }

    // Direct accessors for flyweight instances
    public PegAppendage getPegAppendage() { return pegAppendage; }
    public ReserveAppendage getReserveAppendage() { return reserveAppendage; }
    public DiscretionAppendage getDiscretionAppendage() { return discretionAppendage; }
}
