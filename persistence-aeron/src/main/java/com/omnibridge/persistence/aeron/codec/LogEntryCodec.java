package com.omnibridge.persistence.aeron.codec;

import com.omnibridge.persistence.LogEntry;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * SBE codec for LogEntryMessage (templateId=1).
 *
 * <p>Block layout (21 bytes):
 * <pre>
 * [0-7]   timestamp        int64
 * [8]     direction        uint8   0=INBOUND, 1=OUTBOUND
 * [9-12]  sequenceNumber   int32
 * [13-20] publisherId      int64
 * </pre>
 *
 * <p>Var-length data (4-byte length prefix each):
 * streamName, metadata, rawMessage
 */
public final class LogEntryCodec {

    public static final int TEMPLATE_ID = MessageTypes.LOG_ENTRY;
    public static final int BLOCK_LENGTH = 21;

    private static final int TIMESTAMP_OFFSET = 0;
    private static final int DIRECTION_OFFSET = 8;
    private static final int SEQUENCE_NUMBER_OFFSET = 9;
    private static final int PUBLISHER_ID_OFFSET = 13;

    private LogEntryCodec() {
    }

    public static int encode(MutableDirectBuffer buffer, int offset, LogEntry entry, long publisherId) {
        AeronMessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID,
                MessageTypes.SCHEMA_ID, MessageTypes.SCHEMA_VERSION);
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        buffer.putLong(pos + TIMESTAMP_OFFSET, entry.getTimestamp(), ByteOrder.LITTLE_ENDIAN);
        buffer.putByte(pos + DIRECTION_OFFSET,
                (byte) (entry.getDirection() == LogEntry.Direction.INBOUND ? 0 : 1));
        buffer.putInt(pos + SEQUENCE_NUMBER_OFFSET, entry.getSequenceNumber(), ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(pos + PUBLISHER_ID_OFFSET, publisherId, ByteOrder.LITTLE_ENDIAN);
        pos += BLOCK_LENGTH;

        // streamName
        byte[] streamBytes = entry.getStreamName() != null
                ? entry.getStreamName().getBytes(StandardCharsets.UTF_8) : new byte[0];
        buffer.putInt(pos, streamBytes.length, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        if (streamBytes.length > 0) {
            buffer.putBytes(pos, streamBytes);
            pos += streamBytes.length;
        }

        // metadata
        byte[] meta = entry.getMetadata();
        int metaLen = (meta != null) ? meta.length : 0;
        buffer.putInt(pos, metaLen, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        if (metaLen > 0) {
            buffer.putBytes(pos, meta);
            pos += metaLen;
        }

        // rawMessage
        byte[] raw = entry.getRawMessage();
        int rawLen = (raw != null) ? raw.length : 0;
        buffer.putInt(pos, rawLen, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        if (rawLen > 0) {
            buffer.putBytes(pos, raw);
            pos += rawLen;
        }

        return pos - offset;
    }

    public static LogEntry decode(DirectBuffer buffer, int offset) {
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        long timestamp = buffer.getLong(pos + TIMESTAMP_OFFSET, ByteOrder.LITTLE_ENDIAN);
        byte directionByte = buffer.getByte(pos + DIRECTION_OFFSET);
        int seqNum = buffer.getInt(pos + SEQUENCE_NUMBER_OFFSET, ByteOrder.LITTLE_ENDIAN);
        // publisherId at pos + PUBLISHER_ID_OFFSET (not stored in LogEntry)
        pos += BLOCK_LENGTH;

        // streamName
        int streamLen = buffer.getInt(pos, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        String streamName = streamLen > 0 ? buffer.getStringWithoutLengthAscii(pos, streamLen) : "";
        pos += streamLen;

        // metadata
        int metaLen = buffer.getInt(pos, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        byte[] metadata = null;
        if (metaLen > 0) {
            metadata = new byte[metaLen];
            buffer.getBytes(pos, metadata);
            pos += metaLen;
        }

        // rawMessage
        int rawLen = buffer.getInt(pos, ByteOrder.LITTLE_ENDIAN);
        pos += 4;
        byte[] rawMessage = null;
        if (rawLen > 0) {
            rawMessage = new byte[rawLen];
            buffer.getBytes(pos, rawMessage);
        }

        return LogEntry.builder()
                .timestamp(timestamp)
                .direction(directionByte == 0 ? LogEntry.Direction.INBOUND : LogEntry.Direction.OUTBOUND)
                .sequenceNumber(seqNum)
                .streamName(streamName)
                .metadata(metadata)
                .rawMessage(rawMessage)
                .build();
    }

    public static long decodePublisherId(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + PUBLISHER_ID_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }
}
