package com.omnibridge.persistence.aeron.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SBE codec for CatchUpRequestMessage (templateId=9).
 *
 * <p>Sent by the remote store to the engine when the store starts (or restarts)
 * so the engine can replay missed entries.
 *
 * <p>Block layout (16 bytes):
 * <pre>
 * [0-7]   publisherId     int64   Target publisher (0 = all)
 * [8-15]  lastTimestamp    int64   Last entry timestamp the store has (0 = from beginning)
 * </pre>
 *
 * <p>Var-length: repeated stream position entries:
 * <pre>
 * For each stream:
 *   [0-3]   streamNameLength  int32   (0 = end of list)
 *   [4..N]  streamName        UTF-8 bytes
 *   [N+1..N+4] lastSeqNum    int32   Last seq# the store has for this stream
 * </pre>
 *
 * <p>The list is terminated by a {@code streamNameLength} of 0 (sentinel) or
 * by reaching the end of the buffer. An empty list means "replay everything".
 */
public final class CatchUpRequestCodec {

    public static final int TEMPLATE_ID = MessageTypes.CATCH_UP_REQUEST;
    public static final int BLOCK_LENGTH = 16;

    private static final int PUBLISHER_ID_OFFSET = 0;
    private static final int LAST_TIMESTAMP_OFFSET = 8;

    private CatchUpRequestCodec() {
    }

    /**
     * A single stream's last known position at the remote store.
     */
    public record StreamPosition(String streamName, int lastSeqNum) {}

    /**
     * Decoded catch-up request.
     */
    public record DecodedRequest(long publisherId, long lastTimestamp, List<StreamPosition> streams) {}

    /**
     * Encode a catch-up request.
     *
     * @param buffer      target buffer
     * @param offset      write offset
     * @param publisherId publisher to catch up (0 = all)
     * @param lastTimestamp last timestamp the store has (0 = replay all)
     * @param streams     per-stream positions (empty list = replay all streams from seq 0)
     * @return total encoded length (header + block + var-length)
     */
    public static int encode(MutableDirectBuffer buffer, int offset,
                             long publisherId, long lastTimestamp,
                             List<StreamPosition> streams) {
        AeronMessageHeader.write(buffer, offset, BLOCK_LENGTH, TEMPLATE_ID,
                MessageTypes.SCHEMA_ID, MessageTypes.SCHEMA_VERSION);
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        buffer.putLong(pos + PUBLISHER_ID_OFFSET, publisherId, ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(pos + LAST_TIMESTAMP_OFFSET, lastTimestamp, ByteOrder.LITTLE_ENDIAN);
        pos += BLOCK_LENGTH;

        // Encode stream positions
        if (streams != null) {
            for (StreamPosition sp : streams) {
                byte[] nameBytes = sp.streamName().getBytes(StandardCharsets.UTF_8);
                buffer.putInt(pos, nameBytes.length, ByteOrder.LITTLE_ENDIAN);
                pos += 4;
                buffer.putBytes(pos, nameBytes);
                pos += nameBytes.length;
                buffer.putInt(pos, sp.lastSeqNum(), ByteOrder.LITTLE_ENDIAN);
                pos += 4;
            }
        }

        // Sentinel: streamNameLength = 0
        buffer.putInt(pos, 0, ByteOrder.LITTLE_ENDIAN);
        pos += 4;

        return pos - offset;
    }

    /**
     * Decode a catch-up request.
     */
    public static DecodedRequest decode(DirectBuffer buffer, int offset) {
        int pos = offset + AeronMessageHeader.HEADER_SIZE;

        long publisherId = buffer.getLong(pos + PUBLISHER_ID_OFFSET, ByteOrder.LITTLE_ENDIAN);
        long lastTimestamp = buffer.getLong(pos + LAST_TIMESTAMP_OFFSET, ByteOrder.LITTLE_ENDIAN);
        pos += BLOCK_LENGTH;

        List<StreamPosition> streams = new ArrayList<>();
        while (pos + 4 <= buffer.capacity()) {
            int nameLen = buffer.getInt(pos, ByteOrder.LITTLE_ENDIAN);
            pos += 4;
            if (nameLen <= 0) {
                break; // sentinel
            }
            String name = buffer.getStringWithoutLengthAscii(pos, nameLen);
            pos += nameLen;
            int lastSeqNum = buffer.getInt(pos, ByteOrder.LITTLE_ENDIAN);
            pos += 4;
            streams.add(new StreamPosition(name, lastSeqNum));
        }

        return new DecodedRequest(publisherId, lastTimestamp, streams);
    }

    /**
     * Decode just the publisher ID from a catch-up request.
     */
    public static long decodePublisherId(DirectBuffer buffer, int offset) {
        return buffer.getLong(offset + AeronMessageHeader.HEADER_SIZE + PUBLISHER_ID_OFFSET,
                ByteOrder.LITTLE_ENDIAN);
    }
}
