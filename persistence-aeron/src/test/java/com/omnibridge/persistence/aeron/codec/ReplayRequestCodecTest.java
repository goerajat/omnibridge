package com.omnibridge.persistence.aeron.codec;

import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayRequestCodecTest {

    private final MutableDirectBuffer buffer = new ExpandableDirectByteBuffer(256);

    @Test
    void shouldRoundTripFullRequest() {
        long correlationId = 42L;
        byte direction = MessageTypes.DIRECTION_INBOUND;
        int fromSeq = 100;
        int toSeq = 200;
        long fromTs = 1700000000000L;
        long toTs = 1700000060000L;
        long maxEntries = 5000L;
        String streamName = "FIX.4.4:SENDER->TARGET";

        int length = ReplayRequestCodec.encode(buffer, 0, correlationId, direction,
                fromSeq, toSeq, fromTs, toTs, maxEntries, streamName);
        assertTrue(length > 0);

        assertEquals(MessageTypes.REPLAY_REQUEST, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(correlationId, ReplayRequestCodec.decodeCorrelationId(buffer, 0));
        assertEquals(direction, ReplayRequestCodec.decodeDirection(buffer, 0));
        assertEquals(fromSeq, ReplayRequestCodec.decodeFromSeqNum(buffer, 0));
        assertEquals(toSeq, ReplayRequestCodec.decodeToSeqNum(buffer, 0));
        assertEquals(fromTs, ReplayRequestCodec.decodeFromTimestamp(buffer, 0));
        assertEquals(toTs, ReplayRequestCodec.decodeToTimestamp(buffer, 0));
        assertEquals(maxEntries, ReplayRequestCodec.decodeMaxEntries(buffer, 0));
        assertEquals(streamName, ReplayRequestCodec.decodeStreamName(buffer, 0));
    }

    @Test
    void shouldHandleEmptyStreamAndZeroFilters() {
        int length = ReplayRequestCodec.encode(buffer, 0, 1L,
                MessageTypes.DIRECTION_BOTH, 0, 0, 0, 0, 0, "");

        assertEquals(1L, ReplayRequestCodec.decodeCorrelationId(buffer, 0));
        assertEquals(MessageTypes.DIRECTION_BOTH, ReplayRequestCodec.decodeDirection(buffer, 0));
        assertEquals(0, ReplayRequestCodec.decodeFromSeqNum(buffer, 0));
        assertEquals(0, ReplayRequestCodec.decodeToSeqNum(buffer, 0));
        assertEquals(0L, ReplayRequestCodec.decodeFromTimestamp(buffer, 0));
        assertEquals(0L, ReplayRequestCodec.decodeToTimestamp(buffer, 0));
        assertEquals(0L, ReplayRequestCodec.decodeMaxEntries(buffer, 0));
        assertEquals("", ReplayRequestCodec.decodeStreamName(buffer, 0));
    }

    @Test
    void shouldHandleNullStreamName() {
        int length = ReplayRequestCodec.encode(buffer, 0, 99L,
                MessageTypes.DIRECTION_OUTBOUND, 1, 100, 0, 0, 0, null);

        assertEquals("", ReplayRequestCodec.decodeStreamName(buffer, 0));
        assertEquals(MessageTypes.DIRECTION_OUTBOUND, ReplayRequestCodec.decodeDirection(buffer, 0));
    }
}
