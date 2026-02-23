package com.omnibridge.persistence.aeron.codec;

import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamInfoCodecTest {

    private final MutableDirectBuffer buffer = new ExpandableDirectByteBuffer(256);

    @Test
    void shouldRoundTripStreamInfoRequest() {
        int length = StreamInfoRequestCodec.encode(buffer, 0, 77L, "session-1");
        assertTrue(length > 0);

        assertEquals(MessageTypes.STREAM_INFO_REQUEST, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(77L, StreamInfoRequestCodec.decodeCorrelationId(buffer, 0));
        assertEquals("session-1", StreamInfoRequestCodec.decodeStreamName(buffer, 0));
    }

    @Test
    void shouldHandleEmptyStreamNameInRequest() {
        StreamInfoRequestCodec.encode(buffer, 0, 1L, "");

        assertEquals("", StreamInfoRequestCodec.decodeStreamName(buffer, 0));
    }

    @Test
    void shouldHandleNullStreamNameInRequest() {
        StreamInfoRequestCodec.encode(buffer, 0, 1L, null);

        assertEquals("", StreamInfoRequestCodec.decodeStreamName(buffer, 0));
    }

    @Test
    void shouldRoundTripStreamInfoRequestWithPublisherId() {
        long publisherId = 99L;
        int length = StreamInfoRequestCodec.encode(buffer, 0, 77L, "session-1", publisherId);
        assertTrue(length > 0);

        assertEquals(77L, StreamInfoRequestCodec.decodeCorrelationId(buffer, 0));
        assertEquals(publisherId, StreamInfoRequestCodec.decodePublisherId(buffer, 0));
        assertEquals("session-1", StreamInfoRequestCodec.decodeStreamName(buffer, 0));
    }

    @Test
    void shouldDefaultPublisherIdToZeroInRequest() {
        StreamInfoRequestCodec.encode(buffer, 0, 1L, "test");

        assertEquals(0L, StreamInfoRequestCodec.decodePublisherId(buffer, 0));
    }

    @Test
    void shouldDecodeOldFormatStreamInfoAsPublisherIdZero() {
        // Simulate old 8-byte block format
        AeronMessageHeader.write(buffer, 0, 8, MessageTypes.STREAM_INFO_REQUEST,
                MessageTypes.SCHEMA_ID, MessageTypes.SCHEMA_VERSION);
        int pos = AeronMessageHeader.HEADER_SIZE;
        buffer.putLong(pos, 33L, java.nio.ByteOrder.LITTLE_ENDIAN); // correlationId
        pos += 8;
        buffer.putInt(pos, 0, java.nio.ByteOrder.LITTLE_ENDIAN); // empty streamName

        assertEquals(0L, StreamInfoRequestCodec.decodePublisherId(buffer, 0));
        assertEquals(33L, StreamInfoRequestCodec.decodeCorrelationId(buffer, 0));
        assertEquals("", StreamInfoRequestCodec.decodeStreamName(buffer, 0));
    }

    @Test
    void shouldRoundTripStreamInfoResponse() {
        int length = StreamInfoResponseCodec.encode(buffer, 0, 77L,
                5000L, 100, 200, 1700000000000L, "session-1");
        assertTrue(length > 0);

        assertEquals(MessageTypes.STREAM_INFO_RESPONSE, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(77L, StreamInfoResponseCodec.decodeCorrelationId(buffer, 0));
        assertEquals(5000L, StreamInfoResponseCodec.decodeEntryCount(buffer, 0));
        assertEquals(100, StreamInfoResponseCodec.decodeLastInboundSeqNum(buffer, 0));
        assertEquals(200, StreamInfoResponseCodec.decodeLastOutboundSeqNum(buffer, 0));
        assertEquals(1700000000000L, StreamInfoResponseCodec.decodeLastTimestamp(buffer, 0));
        assertEquals("session-1", StreamInfoResponseCodec.decodeStreamName(buffer, 0));
    }

    @Test
    void shouldHandleZeroCountsInResponse() {
        StreamInfoResponseCodec.encode(buffer, 0, 1L, 0L, 0, 0, 0L, "");

        assertEquals(0L, StreamInfoResponseCodec.decodeEntryCount(buffer, 0));
        assertEquals(0, StreamInfoResponseCodec.decodeLastInboundSeqNum(buffer, 0));
        assertEquals(0, StreamInfoResponseCodec.decodeLastOutboundSeqNum(buffer, 0));
        assertEquals(0L, StreamInfoResponseCodec.decodeLastTimestamp(buffer, 0));
    }
}
