package com.omnibridge.persistence.aeron.codec;

import com.omnibridge.persistence.LogEntry;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogEntryCodecTest {

    private final MutableDirectBuffer buffer = new ExpandableDirectByteBuffer(8192);

    @Test
    void shouldRoundTripInboundEntry() {
        LogEntry original = LogEntry.builder()
                .timestamp(1700000000000L)
                .inbound()
                .sequenceNumber(42)
                .streamName("FIX.4.4:SENDER->TARGET")
                .metadata("type=D".getBytes())
                .rawMessage("8=FIX.4.4|35=D|49=SENDER|".getBytes())
                .build();
        long publisherId = 12345L;

        int length = LogEntryCodec.encode(buffer, 0, original, publisherId);
        assertTrue(length > 0);

        // Verify header
        assertEquals(MessageTypes.LOG_ENTRY, AeronMessageHeader.readTemplateId(buffer, 0));
        assertEquals(MessageTypes.SCHEMA_ID, AeronMessageHeader.readSchemaId(buffer, 0));

        LogEntry decoded = LogEntryCodec.decode(buffer, 0);

        assertEquals(original.getTimestamp(), decoded.getTimestamp());
        assertEquals(LogEntry.Direction.INBOUND, decoded.getDirection());
        assertEquals(42, decoded.getSequenceNumber());
        assertEquals("FIX.4.4:SENDER->TARGET", decoded.getStreamName());
        assertArrayEquals(original.getMetadata(), decoded.getMetadata());
        assertArrayEquals(original.getRawMessage(), decoded.getRawMessage());

        assertEquals(publisherId, LogEntryCodec.decodePublisherId(buffer, 0));
    }

    @Test
    void shouldRoundTripOutboundEntry() {
        LogEntry original = LogEntry.builder()
                .timestamp(1700000001000L)
                .outbound()
                .sequenceNumber(100)
                .streamName("session-1")
                .rawMessage("response data".getBytes())
                .build();

        int length = LogEntryCodec.encode(buffer, 0, original, 99L);
        LogEntry decoded = LogEntryCodec.decode(buffer, 0);

        assertEquals(LogEntry.Direction.OUTBOUND, decoded.getDirection());
        assertEquals(100, decoded.getSequenceNumber());
        assertEquals("session-1", decoded.getStreamName());
        assertNull(decoded.getMetadata());
        assertArrayEquals("response data".getBytes(), decoded.getRawMessage());
    }

    @Test
    void shouldHandleNullMetadataAndMessage() {
        LogEntry original = LogEntry.builder()
                .timestamp(1000L)
                .inbound()
                .sequenceNumber(1)
                .streamName("test")
                .build();

        int length = LogEntryCodec.encode(buffer, 0, original, 0);
        LogEntry decoded = LogEntryCodec.decode(buffer, 0);

        assertEquals(1000L, decoded.getTimestamp());
        assertEquals("test", decoded.getStreamName());
        assertNull(decoded.getMetadata());
        assertNull(decoded.getRawMessage());
    }

    @Test
    void shouldHandleEmptyStreamName() {
        LogEntry original = LogEntry.builder()
                .timestamp(2000L)
                .inbound()
                .sequenceNumber(5)
                .streamName("")
                .rawMessage("data".getBytes())
                .build();

        int length = LogEntryCodec.encode(buffer, 0, original, 0);
        LogEntry decoded = LogEntryCodec.decode(buffer, 0);

        assertEquals("", decoded.getStreamName());
    }

    @Test
    void shouldHandleLargeMessage() {
        byte[] largeMessage = new byte[65536];
        for (int i = 0; i < largeMessage.length; i++) {
            largeMessage[i] = (byte) (i % 256);
        }

        LogEntry original = LogEntry.builder()
                .timestamp(3000L)
                .outbound()
                .sequenceNumber(Integer.MAX_VALUE)
                .streamName("large-stream")
                .metadata(new byte[1024])
                .rawMessage(largeMessage)
                .build();

        int length = LogEntryCodec.encode(buffer, 0, original, Long.MAX_VALUE);
        LogEntry decoded = LogEntryCodec.decode(buffer, 0);

        assertEquals(Integer.MAX_VALUE, decoded.getSequenceNumber());
        assertEquals(1024, decoded.getMetadata().length);
        assertArrayEquals(largeMessage, decoded.getRawMessage());
        assertEquals(Long.MAX_VALUE, LogEntryCodec.decodePublisherId(buffer, 0));
    }

    @Test
    void shouldEncodeAtNonZeroOffset() {
        int offset = 100;
        LogEntry original = LogEntry.builder()
                .timestamp(5000L)
                .inbound()
                .sequenceNumber(10)
                .streamName("offset-test")
                .rawMessage("test".getBytes())
                .build();

        int length = LogEntryCodec.encode(buffer, offset, original, 7L);
        LogEntry decoded = LogEntryCodec.decode(buffer, offset);

        assertEquals(5000L, decoded.getTimestamp());
        assertEquals("offset-test", decoded.getStreamName());
    }
}
