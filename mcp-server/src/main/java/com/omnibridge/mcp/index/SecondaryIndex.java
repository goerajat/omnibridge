package com.omnibridge.mcp.index;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RocksDB-backed secondary index for FIX message field lookups.
 *
 * <p>Provides sub-linear lookups on indexed FIX fields (ClOrdID, Symbol, OrderID, etc.)
 * by mapping field values to Chronicle Queue positions.</p>
 *
 * <p>Key schema: {@code {fieldValue}\0{timestamp_8bytes}} -> {@code {stream}\0{position_8bytes}}</p>
 */
public class SecondaryIndex implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SecondaryIndex.class);

    public static final String CF_CLORDID = "clordid";
    public static final String CF_ORDERID = "orderid";
    public static final String CF_SYMBOL = "symbol";
    public static final String CF_MSGTYPE = "msgtype";
    public static final String CF_EXECTYPE = "exectype";
    public static final String CF_COMP = "comp";
    private static final String CF_META = "meta";

    static final String[] COLUMN_FAMILY_NAMES = {
            CF_CLORDID, CF_ORDERID, CF_SYMBOL, CF_MSGTYPE, CF_EXECTYPE, CF_COMP, CF_META
    };

    private static final byte SEPARATOR = 0x00;

    private RocksDB db;
    private final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
    private final String dataDir;

    public SecondaryIndex(String dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * Open the RocksDB index, creating column families if needed.
     */
    public void open() throws RocksDBException {
        RocksDB.loadLibrary();

        DBOptions dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);

        List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
        // Default column family is always required
        cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
        for (String cfName : COLUMN_FAMILY_NAMES) {
            cfDescriptors.add(new ColumnFamilyDescriptor(cfName.getBytes(StandardCharsets.UTF_8)));
        }

        db = RocksDB.open(dbOptions, dataDir, cfDescriptors, cfHandles);
        log.info("Opened secondary index at {}", dataDir);
    }

    /**
     * Index an entry: map a field value to a Chronicle Queue position.
     */
    public void put(String cfName, String fieldValue, long timestamp, String stream, long position)
            throws RocksDBException {
        ColumnFamilyHandle cfh = getCfHandle(cfName);
        byte[] key = buildKey(fieldValue, timestamp);
        byte[] value = buildValue(stream, position);
        db.put(cfh, key, value);
    }

    /**
     * Look up all entries for a field value.
     */
    public List<IndexEntry> lookup(String cfName, String fieldValue) throws RocksDBException {
        return lookupRange(cfName, fieldValue, 0, Long.MAX_VALUE);
    }

    /**
     * Look up entries for a field value within a time range.
     */
    public List<IndexEntry> lookupRange(String cfName, String fieldValue, long fromTs, long toTs)
            throws RocksDBException {
        ColumnFamilyHandle cfh = getCfHandle(cfName);
        List<IndexEntry> results = new ArrayList<>();

        byte[] prefix = (fieldValue + (char) SEPARATOR).getBytes(StandardCharsets.UTF_8);
        byte[] seekKey = buildKey(fieldValue, fromTs);

        try (RocksIterator iter = db.newIterator(cfh)) {
            iter.seek(seekKey);
            while (iter.isValid()) {
                byte[] key = iter.key();
                // Check prefix match
                if (!startsWith(key, prefix)) break;

                long ts = extractTimestamp(key, prefix.length);
                if (ts > toTs) break;

                byte[] value = iter.value();
                IndexEntry entry = parseValue(value, ts);
                results.add(entry);

                iter.next();
            }
        }
        return results;
    }

    /**
     * Get the last indexed position for a stream (for checkpoint/resume).
     */
    public long getLastIndexedPosition(String stream) throws RocksDBException {
        ColumnFamilyHandle metaCf = getCfHandle(CF_META);
        byte[] key = ("pos:" + stream).getBytes(StandardCharsets.UTF_8);
        byte[] value = db.get(metaCf, key);
        if (value == null) return -1;
        return ByteBuffer.wrap(value).getLong();
    }

    /**
     * Save the last indexed position for a stream.
     */
    public void setLastIndexedPosition(String stream, long position) throws RocksDBException {
        ColumnFamilyHandle metaCf = getCfHandle(CF_META);
        byte[] key = ("pos:" + stream).getBytes(StandardCharsets.UTF_8);
        byte[] value = new byte[8];
        ByteBuffer.wrap(value).putLong(position);
        db.put(metaCf, key, value);
    }

    public boolean isOpen() {
        return db != null;
    }

    @Override
    public void close() {
        if (db != null) {
            for (ColumnFamilyHandle cfh : cfHandles) {
                cfh.close();
            }
            cfHandles.clear();
            db.close();
            db = null;
            log.info("Closed secondary index at {}", dataDir);
        }
    }

    // ==================== Internal ====================

    private ColumnFamilyHandle getCfHandle(String cfName) {
        // Index 0 is default CF; named CFs start at index 1
        for (int i = 0; i < COLUMN_FAMILY_NAMES.length; i++) {
            if (COLUMN_FAMILY_NAMES[i].equals(cfName)) {
                return cfHandles.get(i + 1); // +1 for default CF
            }
        }
        throw new IllegalArgumentException("Unknown column family: " + cfName);
    }

    static byte[] buildKey(String fieldValue, long timestamp) {
        byte[] fieldBytes = fieldValue.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[fieldBytes.length + 1 + 8];
        System.arraycopy(fieldBytes, 0, key, 0, fieldBytes.length);
        key[fieldBytes.length] = SEPARATOR;
        // Big-endian timestamp for natural sort order
        ByteBuffer.wrap(key, fieldBytes.length + 1, 8).putLong(timestamp);
        return key;
    }

    static byte[] buildValue(String stream, long position) {
        byte[] streamBytes = stream.getBytes(StandardCharsets.UTF_8);
        byte[] value = new byte[streamBytes.length + 1 + 8];
        System.arraycopy(streamBytes, 0, value, 0, streamBytes.length);
        value[streamBytes.length] = SEPARATOR;
        ByteBuffer.wrap(value, streamBytes.length + 1, 8).putLong(position);
        return value;
    }

    private static long extractTimestamp(byte[] key, int offset) {
        return ByteBuffer.wrap(key, offset, 8).getLong();
    }

    private static IndexEntry parseValue(byte[] value, long timestamp) {
        int sepIdx = -1;
        for (int i = 0; i < value.length; i++) {
            if (value[i] == SEPARATOR) { sepIdx = i; break; }
        }
        if (sepIdx < 0) throw new IllegalStateException("Malformed index value");
        String stream = new String(value, 0, sepIdx, StandardCharsets.UTF_8);
        long position = ByteBuffer.wrap(value, sepIdx + 1, 8).getLong();
        return new IndexEntry(stream, position, timestamp);
    }

    private static boolean startsWith(byte[] arr, byte[] prefix) {
        if (arr.length < prefix.length) return false;
        return Arrays.equals(arr, 0, prefix.length, prefix, 0, prefix.length);
    }

    /**
     * A single index entry pointing to a Chronicle Queue position.
     */
    public record IndexEntry(String stream, long position, long timestamp) {}
}
