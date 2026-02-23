package com.omnibridge.persistence.aeron.codec;

import com.omnibridge.persistence.LogEntry;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayEntryCodecTest {

    private final MutableDirectBuffer buffer = new ExpandableDirectByteBuffer(8192);

    @Test
    void shouldRoundTripReplayEntry() {
        long correlationId = 7L;
        long entryIndex = 42L;
        LogEntry original = LogEntry.builder()
                .timestamp(1700000000000L)
                .inbound()
                .sequenceNumber(99)
                .streamName("session-replay")
                .metadata("meta".getBytes())
                .rawMessage("8=FIX.4.4|35=A|".getBytes())
                .build();

        int length = ReplayEntryCodec.encode(buffer, 0, correlationId, original, entryIndex);
        assertTrue(length > 0);

        assertEquals(MessageTypes.REPLAY_ENTRY, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(correlationId, ReplayEntryCodec.decodeCorrelationId(buffer, 0));
        assertEquals(entryIndex, ReplayEntryCodec.decodeEntryIndex(buffer, 0));

        LogEntry decoded = ReplayEntryCodec.decode(buffer, 0);

        assertEquals(original.getTimestamp(), decoded.getTimestamp());
        assertEquals(LogEntry.Direction.INBOUND, decoded.getDirection());
        assertEquals(99, decoded.getSequenceNumber());
        assertEquals("session-replay", decoded.getStreamName());
        assertArrayEquals("meta".getBytes(), decoded.getMetadata());
        assertArrayEquals("8=FIX.4.4|35=A|".getBytes(), decoded.getRawMessage());
    }

    @Test
    void shouldHandleNullMetadataAndMessage() {
        LogEntry entry = LogEntry.builder()
                .timestamp(1000L)
                .outbound()
                .sequenceNumber(1)
                .streamName("test")
                .build();

        int length = ReplayEntryCodec.encode(buffer, 0, 1L, entry, 0);
        LogEntry decoded = ReplayEntryCodec.decode(buffer, 0);

        assertNull(decoded.getMetadata());
        assertNull(decoded.getRawMessage());
        assertEquals(LogEntry.Direction.OUTBOUND, decoded.getDirection());
    }
}
