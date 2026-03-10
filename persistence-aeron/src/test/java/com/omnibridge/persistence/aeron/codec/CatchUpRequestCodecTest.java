package com.omnibridge.persistence.aeron.codec;

import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatchUpRequestCodecTest {

    private final MutableDirectBuffer buffer = new ExpandableDirectByteBuffer(512);

    @Test
    void shouldRoundTripEmptyStreamList() {
        long publisherId = 42L;
        long lastTimestamp = 1700000000000L;

        int length = CatchUpRequestCodec.encode(buffer, 0, publisherId, lastTimestamp, List.of());

        CatchUpRequestCodec.DecodedRequest decoded = CatchUpRequestCodec.decode(buffer, 0);
        assertEquals(publisherId, decoded.publisherId());
        assertEquals(lastTimestamp, decoded.lastTimestamp());
        assertTrue(decoded.streams().isEmpty());

        // Verify header
        assertEquals(MessageTypes.CATCH_UP_REQUEST, AeronMessageHeader.readTemplateId(buffer, 0));
    }

    @Test
    void shouldRoundTripSingleStream() {
        List<CatchUpRequestCodec.StreamPosition> streams = List.of(
                new CatchUpRequestCodec.StreamPosition("FIX-SESSION-1", 150)
        );

        CatchUpRequestCodec.encode(buffer, 0, 1L, 5000L, streams);

        CatchUpRequestCodec.DecodedRequest decoded = CatchUpRequestCodec.decode(buffer, 0);
        assertEquals(1L, decoded.publisherId());
        assertEquals(5000L, decoded.lastTimestamp());
        assertEquals(1, decoded.streams().size());
        assertEquals("FIX-SESSION-1", decoded.streams().get(0).streamName());
        assertEquals(150, decoded.streams().get(0).lastSeqNum());
    }

    @Test
    void shouldRoundTripMultipleStreams() {
        List<CatchUpRequestCodec.StreamPosition> streams = List.of(
                new CatchUpRequestCodec.StreamPosition("SESSION-A", 100),
                new CatchUpRequestCodec.StreamPosition("SESSION-B", 200),
                new CatchUpRequestCodec.StreamPosition("SESSION-C", 0)
        );

        CatchUpRequestCodec.encode(buffer, 0, 7L, 9999L, streams);

        CatchUpRequestCodec.DecodedRequest decoded = CatchUpRequestCodec.decode(buffer, 0);
        assertEquals(7L, decoded.publisherId());
        assertEquals(9999L, decoded.lastTimestamp());
        assertEquals(3, decoded.streams().size());

        assertEquals("SESSION-A", decoded.streams().get(0).streamName());
        assertEquals(100, decoded.streams().get(0).lastSeqNum());

        assertEquals("SESSION-B", decoded.streams().get(1).streamName());
        assertEquals(200, decoded.streams().get(1).lastSeqNum());

        assertEquals("SESSION-C", decoded.streams().get(2).streamName());
        assertEquals(0, decoded.streams().get(2).lastSeqNum());
    }

    @Test
    void shouldDecodePublisherId() {
        CatchUpRequestCodec.encode(buffer, 0, 123L, 0L, List.of());

        assertEquals(123L, CatchUpRequestCodec.decodePublisherId(buffer, 0));
    }

    @Test
    void shouldHandleNullStreamList() {
        int length = CatchUpRequestCodec.encode(buffer, 0, 0L, 0L, null);

        CatchUpRequestCodec.DecodedRequest decoded = CatchUpRequestCodec.decode(buffer, 0);
        assertEquals(0L, decoded.publisherId());
        assertEquals(0L, decoded.lastTimestamp());
        assertTrue(decoded.streams().isEmpty());
    }

    @Test
    void shouldEncodeAtOffset() {
        int offset = 64;
        List<CatchUpRequestCodec.StreamPosition> streams = List.of(
                new CatchUpRequestCodec.StreamPosition("TEST-STREAM", 42)
        );

        CatchUpRequestCodec.encode(buffer, offset, 10L, 2000L, streams);

        CatchUpRequestCodec.DecodedRequest decoded = CatchUpRequestCodec.decode(buffer, offset);
        assertEquals(10L, decoded.publisherId());
        assertEquals(2000L, decoded.lastTimestamp());
        assertEquals(1, decoded.streams().size());
        assertEquals("TEST-STREAM", decoded.streams().get(0).streamName());
        assertEquals(42, decoded.streams().get(0).lastSeqNum());
    }
}
