package com.omnibridge.persistence.aeron.codec;

import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AckCodecTest {

    private final MutableDirectBuffer buffer = new ExpandableDirectByteBuffer(64);

    @Test
    void shouldRoundTripOk() {
        long correlationId = 42L;
        long timestamp = 1700000000000L;

        int length = AckCodec.encode(buffer, 0, correlationId, timestamp, MessageTypes.ACK_OK);
        assertEquals(AckCodec.ENCODED_LENGTH, length);

        assertEquals(MessageTypes.ACK, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(correlationId, AckCodec.decodeCorrelationId(buffer, 0));
        assertEquals(timestamp, AckCodec.decodeTimestamp(buffer, 0));
        assertEquals(MessageTypes.ACK_OK, AckCodec.decodeStatus(buffer, 0));
    }

    @Test
    void shouldRoundTripError() {
        AckCodec.encode(buffer, 0, 99L, 2000L, MessageTypes.ACK_ERROR);

        assertEquals(99L, AckCodec.decodeCorrelationId(buffer, 0));
        assertEquals(2000L, AckCodec.decodeTimestamp(buffer, 0));
        assertEquals(MessageTypes.ACK_ERROR, AckCodec.decodeStatus(buffer, 0));
    }

    @Test
    void shouldEncodeAtOffset() {
        int offset = 16;
        AckCodec.encode(buffer, offset, 1L, 3000L, MessageTypes.ACK_OK);

        assertEquals(1L, AckCodec.decodeCorrelationId(buffer, offset));
        assertEquals(3000L, AckCodec.decodeTimestamp(buffer, offset));
    }
}
