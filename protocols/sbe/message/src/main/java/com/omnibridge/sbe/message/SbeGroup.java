package com.omnibridge.sbe.message;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Base class for SBE repeating groups.
 * <p>
 * SBE repeating groups have a header followed by N entries of fixed size:
 * <pre>
 * Offset  Length  Field
 * 0       2       blockLength (size of each group entry)
 * 2       1       numInGroup (number of entries, for count <= 254)
 *   or
 * 0       2       blockLength
 * 2       2       numInGroup (for count > 254)
 * </pre>
 * <p>
 * Subclasses define the fields within each group entry.
 * <p>
 * Usage pattern for reading:
 * <pre>
 * FillsGroup fills = message.getFillsGroup();
 * for (FillsGroup.Entry entry : fills) {
 *     long fillQty = entry.getFillQty();
 *     long fillPx = entry.getFillPx();
 * }
 * </pre>
 *
 * @param <E> the entry type
 */
public abstract class SbeGroup<E extends SbeGroup.Entry> implements Iterable<E> {

    /** Size of the 8-bit count group header */
    public static final int HEADER_SIZE_8BIT = 3;

    /** Size of the 16-bit count group header */
    public static final int HEADER_SIZE_16BIT = 4;

    /** Byte order for SBE */
    protected static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    /** The buffer containing the group */
    protected DirectBuffer buffer;

    /** Offset of the group header */
    protected int offset;

    /** Block length of each entry */
    protected int blockLength;

    /** Number of entries in the group */
    protected int count;

    /** Offset of the first entry (after header) */
    protected int entriesOffset;

    /** Size of the group header */
    protected int headerSize;

    /** Reusable entry for iteration */
    protected E iteratorEntry;

    /**
     * Wraps the group for reading with 8-bit count.
     *
     * @param buffer the buffer containing the group
     * @param offset the offset of the group header
     * @return this group for method chaining
     */
    public SbeGroup<E> wrapForReading(DirectBuffer buffer, int offset) {
        return wrapForReading(buffer, offset, false);
    }

    /**
     * Wraps the group for reading.
     *
     * @param buffer the buffer containing the group
     * @param offset the offset of the group header
     * @param use16BitCount true for 16-bit count header
     * @return this group for method chaining
     */
    public SbeGroup<E> wrapForReading(DirectBuffer buffer, int offset, boolean use16BitCount) {
        this.buffer = buffer;
        this.offset = offset;
        this.headerSize = use16BitCount ? HEADER_SIZE_16BIT : HEADER_SIZE_8BIT;

        this.blockLength = buffer.getShort(offset, BYTE_ORDER) & 0xFFFF;
        this.count = use16BitCount
                ? buffer.getShort(offset + 2, BYTE_ORDER) & 0xFFFF
                : buffer.getByte(offset + 2) & 0xFF;
        this.entriesOffset = offset + headerSize;

        return this;
    }

    /**
     * Gets the number of entries in the group.
     *
     * @return the entry count
     */
    public int getCount() {
        return count;
    }

    /**
     * Gets the block length of each entry.
     *
     * @return the block length
     */
    public int getBlockLength() {
        return blockLength;
    }

    /**
     * Gets the total encoded length of this group (header + all entries).
     *
     * @return the total length in bytes
     */
    public int getEncodedLength() {
        return headerSize + (count * blockLength);
    }

    /**
     * Checks if the group is empty.
     *
     * @return true if count is 0
     */
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * Gets an entry at the specified index.
     *
     * @param index the entry index (0-based)
     * @return the entry
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public E getEntry(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", Count: " + count);
        }
        E entry = createEntry();
        entry.wrap(buffer, entriesOffset + (index * blockLength), blockLength);
        return entry;
    }

    /**
     * Creates a new entry instance.
     * Subclasses must implement this to return protocol-specific entries.
     *
     * @return a new entry instance
     */
    protected abstract E createEntry();

    /**
     * Gets the reusable iterator entry.
     * Creates one if it doesn't exist.
     *
     * @return the iterator entry
     */
    protected E getIteratorEntry() {
        if (iteratorEntry == null) {
            iteratorEntry = createEntry();
        }
        return iteratorEntry;
    }

    @Override
    public Iterator<E> iterator() {
        return new GroupIterator();
    }

    /**
     * Iterator implementation that reuses a single entry instance.
     */
    private class GroupIterator implements Iterator<E> {
        private int currentIndex = 0;

        @Override
        public boolean hasNext() {
            return currentIndex < count;
        }

        @Override
        public E next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            E entry = getIteratorEntry();
            entry.wrap(buffer, entriesOffset + (currentIndex * blockLength), blockLength);
            currentIndex++;
            return entry;
        }
    }

    /**
     * Base class for group entries.
     * Entries are flyweights that wrap buffer regions.
     */
    public static abstract class Entry {

        /** The buffer containing the entry */
        protected DirectBuffer buffer;

        /** Offset of this entry within the buffer */
        protected int offset;

        /** Length of this entry */
        protected int length;

        /**
         * Wraps the entry around a buffer region.
         *
         * @param buffer the buffer
         * @param offset the offset
         * @param length the entry length
         */
        public void wrap(DirectBuffer buffer, int offset, int length) {
            this.buffer = buffer;
            this.offset = offset;
            this.length = length;
        }

        /**
         * Resets the entry.
         */
        public void reset() {
            this.buffer = null;
            this.offset = 0;
            this.length = 0;
        }

        // Field access utilities

        protected byte getByte(int fieldOffset) {
            return buffer.getByte(offset + fieldOffset);
        }

        protected void putByte(int fieldOffset, byte value) {
            ((MutableDirectBuffer) buffer).putByte(offset + fieldOffset, value);
        }

        protected short getShort(int fieldOffset) {
            return buffer.getShort(offset + fieldOffset, BYTE_ORDER);
        }

        protected void putShort(int fieldOffset, short value) {
            ((MutableDirectBuffer) buffer).putShort(offset + fieldOffset, value, BYTE_ORDER);
        }

        protected int getInt(int fieldOffset) {
            return buffer.getInt(offset + fieldOffset, BYTE_ORDER);
        }

        protected void putInt(int fieldOffset, int value) {
            ((MutableDirectBuffer) buffer).putInt(offset + fieldOffset, value, BYTE_ORDER);
        }

        protected long getLong(int fieldOffset) {
            return buffer.getLong(offset + fieldOffset, BYTE_ORDER);
        }

        protected void putLong(int fieldOffset, long value) {
            ((MutableDirectBuffer) buffer).putLong(offset + fieldOffset, value, BYTE_ORDER);
        }
    }
}
