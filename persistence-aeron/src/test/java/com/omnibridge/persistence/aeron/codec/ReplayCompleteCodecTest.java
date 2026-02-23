package com.omnibridge.persistence.aeron.codec;

import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayCompleteCodecTest {

    private final MutableDirectBuffer buffer = new ExpandableDirectByteBuffer(256);

    @Test
    void shouldRoundTripSuccess() {
        int length = ReplayCompleteCodec.encode(buffer, 0, 42L, 1000L,
                MessageTypes.STATUS_SUCCESS, null);
        assertTrue(length > 0);

        assertEquals(MessageTypes.REPLAY_COMPLETE, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(42L, ReplayCompleteCodec.decodeCorrelationId(buffer, 0));
        assertEquals(1000L, ReplayCompleteCodec.decodeEntryCount(buffer, 0));
        assertEquals(MessageTypes.STATUS_SUCCESS, ReplayCompleteCodec.decodeStatus(buffer, 0));
        assertEquals("", ReplayCompleteCodec.decodeErrorMessage(buffer, 0));
    }

    @Test
    void shouldRoundTripError() {
        String error = "Stream not found: unknown-stream";
        int length = ReplayCompleteCodec.encode(buffer, 0, 99L, 0L,
                MessageTypes.STATUS_ERROR, error);

        assertEquals(99L, ReplayCompleteCodec.decodeCorrelationId(buffer, 0));
        assertEquals(0L, ReplayCompleteCodec.decodeEntryCount(buffer, 0));
        assertEquals(MessageTypes.STATUS_ERROR, ReplayCompleteCodec.decodeStatus(buffer, 0));
        assertEquals(error, ReplayCompleteCodec.decodeErrorMessage(buffer, 0));
    }

    @Test
    void shouldRoundTripPartial() {
        int length = ReplayCompleteCodec.encode(buffer, 0, 5L, 500L,
                MessageTypes.STATUS_PARTIAL, "timeout reached");

        assertEquals(MessageTypes.STATUS_PARTIAL, ReplayCompleteCodec.decodeStatus(buffer, 0));
        assertEquals(500L, ReplayCompleteCodec.decodeEntryCount(buffer, 0));
        assertEquals("timeout reached", ReplayCompleteCodec.decodeErrorMessage(buffer, 0));
    }
}
