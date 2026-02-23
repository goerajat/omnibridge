package com.omnibridge.persistence.aeron.codec;

import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatCodecTest {

    private final MutableDirectBuffer buffer = new ExpandableDirectByteBuffer(64);

    @Test
    void shouldRoundTrip() {
        long timestamp = 1700000000000L;
        long publisherId = 12345L;

        int length = HeartbeatCodec.encode(buffer, 0, timestamp, publisherId);
        assertEquals(HeartbeatCodec.ENCODED_LENGTH, length);

        assertEquals(MessageTypes.HEARTBEAT, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(timestamp, HeartbeatCodec.decodeTimestamp(buffer, 0));
        assertEquals(publisherId, HeartbeatCodec.decodePublisherId(buffer, 0));
    }

    @Test
    void shouldHandleZeroValues() {
        HeartbeatCodec.encode(buffer, 0, 0L, 0L);

        assertEquals(0L, HeartbeatCodec.decodeTimestamp(buffer, 0));
        assertEquals(0L, HeartbeatCodec.decodePublisherId(buffer, 0));
    }

    @Test
    void shouldHandleMaxValues() {
        HeartbeatCodec.encode(buffer, 0, Long.MAX_VALUE, Long.MAX_VALUE);

        assertEquals(Long.MAX_VALUE, HeartbeatCodec.decodeTimestamp(buffer, 0));
        assertEquals(Long.MAX_VALUE, HeartbeatCodec.decodePublisherId(buffer, 0));
    }

    @Test
    void shouldEncodeAtOffset() {
        int offset = 32;
        HeartbeatCodec.encode(buffer, offset, 5000L, 7L);

        assertEquals(5000L, HeartbeatCodec.decodeTimestamp(buffer, offset));
        assertEquals(7L, HeartbeatCodec.decodePublisherId(buffer, offset));
    }
}
