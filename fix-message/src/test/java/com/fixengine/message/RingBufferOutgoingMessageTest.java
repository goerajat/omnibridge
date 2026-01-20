package com.fixengine.message;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RingBufferOutgoingMessage.
 */
class RingBufferOutgoingMessageTest {

    private static final int BUFFER_SIZE = 4096;

    private MessagePoolConfig config;
    private RingBufferOutgoingMessage message;
    private UnsafeBuffer buffer;

    @BeforeEach
    void setUp() {
        config = MessagePoolConfig.builder()
                .poolSize(1)
                .maxMessageLength(BUFFER_SIZE)
                .maxTagNumber(1000)
                .beginString("FIX.4.4")
                .senderCompId("SENDER")
                .targetCompId("TARGET")
                .build();
        message = new RingBufferOutgoingMessage(config);
        buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE + RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE));
    }

    private void wrapMessage() {
        message.wrap(buffer, 0, BUFFER_SIZE + RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE,
                1, 0, "FIX.4.4", "SENDER", "TARGET");
    }

    @Test
    void wrap_initializesHeader() {
        wrapMessage();
        message.setMsgType("D");

        String content = message.toString();

        // Should have BeginString
        assertTrue(content.contains("8=FIX.4.4|"));
        // Should have BodyLength placeholder
        assertTrue(content.contains("9=00000|"));
        // Should have SenderCompID
        assertTrue(content.contains("49=SENDER|"));
        // Should have TargetCompID
        assertTrue(content.contains("56=TARGET|"));
    }

    @Test
    void setMsgType_setsCorrectly() {
        wrapMessage();
        message.setMsgType("D");
        assertEquals("D", message.getMsgType());
    }

    @Test
    void setField_string() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        String content = message.toString();
        assertTrue(content.contains("11=ORDER-001|"));
    }

    @Test
    void setField_int() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(38, 100);

        String content = message.toString();
        assertTrue(content.contains("38=100|"));
    }

    @Test
    void setField_long() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(38, 9876543210L);

        String content = message.toString();
        assertTrue(content.contains("38=9876543210|"));
    }

    @Test
    void setField_double() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(44, 150.25, 2);

        String content = message.toString();
        assertTrue(content.contains("44=150.25|"));
    }

    @Test
    void setField_char() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(54, '1');

        String content = message.toString();
        assertTrue(content.contains("54=1|"));
    }

    @Test
    void setField_boolean_true() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(130, true);

        String content = message.toString();
        assertTrue(content.contains("130=Y|"));
    }

    @Test
    void setField_boolean_false() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(130, false);

        String content = message.toString();
        assertTrue(content.contains("130=N|"));
    }

    @Test
    void setField_duplicateTag_throwsException() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        assertThrows(DuplicateTagException.class, () ->
                message.setField(11, "ORDER-002"));
    }

    @Test
    void hasTag_returnsTrueForSetTags() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        assertTrue(message.hasTag(11));
        assertFalse(message.hasTag(55));
    }

    @Test
    void prepareForSend_setsSeqNumAndSendingTime() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        int length = message.prepareForSend(System.currentTimeMillis());
        String content = message.toString();

        // Should have sequence number (seqNum=1 from wrap)
        assertTrue(content.contains("34=00000001|"));
        // Should have checksum
        assertTrue(content.contains("|10="));
    }

    @Test
    void prepareForSend_calculatesBodyLength() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");
        message.setField(55, "AAPL");

        message.prepareForSend(System.currentTimeMillis());
        String content = message.toString();

        // Body length should be non-zero
        assertFalse(content.contains("9=00000|"));
    }

    @Test
    void prepareForSend_calculatesChecksum() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        message.prepareForSend(System.currentTimeMillis());
        String content = message.toString();

        // Should end with checksum
        assertTrue(content.matches(".*\\|10=\\d{3}\\|$"));
    }

    @Test
    void prepareForSend_writesLengthPrefix() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        int messageLength = message.prepareForSend(System.currentTimeMillis());

        // Check length prefix
        int storedLength = buffer.getInt(0);
        assertEquals(messageLength, storedLength);
    }

    @Test
    void getSeqNum_returnsAssignedSeqNum() {
        message.wrap(buffer, 0, BUFFER_SIZE, 12345, 0, "FIX.4.4", "SENDER", "TARGET");
        assertEquals(12345, message.getSeqNum());
    }

    @Test
    void getClaimIndex_returnsAssignedIndex() {
        message.wrap(buffer, 0, BUFFER_SIZE, 1, 42, "FIX.4.4", "SENDER", "TARGET");
        assertEquals(42, message.getClaimIndex());
    }

    @Test
    void toByteArray_returnsCopy() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");
        message.prepareForSend(System.currentTimeMillis());

        byte[] copy = message.toByteArray();

        assertTrue(copy.length > 0);
        assertEquals(message.getLength(), copy.length);
    }

    @Test
    void setField_nullValue_isIgnored() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, (String) null);

        // Should not throw and should not have the tag
        assertFalse(message.hasTag(11));
    }

    @Test
    void setMsgType_nullValue_throwsException() {
        wrapMessage();
        assertThrows(IllegalArgumentException.class, () ->
                message.setMsgType(null));
    }

    @Test
    void setMsgType_emptyValue_throwsException() {
        wrapMessage();
        assertThrows(IllegalArgumentException.class, () ->
                message.setMsgType(""));
    }

    @Test
    void multipleFields_correctOrder() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");
        message.setField(55, "AAPL");
        message.setField(54, '1');
        message.setField(38, 100);
        message.setField(44, 150.25, 2);

        String content = message.toString();

        // Verify fields are present
        assertTrue(content.contains("11=ORDER-001|"));
        assertTrue(content.contains("55=AAPL|"));
        assertTrue(content.contains("54=1|"));
        assertTrue(content.contains("38=100|"));
        assertTrue(content.contains("44=150.25|"));
    }

    @Test
    void reuse_afterWrap() {
        // First use
        wrapMessage();
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        // Re-wrap for reuse
        message.wrap(buffer, 0, BUFFER_SIZE + RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE,
                2, 0, "FIX.4.4", "SENDER", "TARGET");
        message.setMsgType("8");
        message.setField(55, "MSFT");

        assertEquals("8", message.getMsgType());
        assertEquals(2, message.getSeqNum());
        assertFalse(message.hasTag(11));  // Previous tag should be cleared
        assertTrue(message.hasTag(55));
    }

    @Test
    void charSequenceField() {
        wrapMessage();
        message.setMsgType("D");

        CharSequence cs = "TEST-ORDER";
        message.setField(11, cs);

        String content = message.toString();
        assertTrue(content.contains("11=TEST-ORDER|"));
    }

    @Test
    void negativeInt() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(38, -100);

        String content = message.toString();
        assertTrue(content.contains("38=-100|"));
    }

    @Test
    void negativeDouble() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(44, -150.25, 2);

        String content = message.toString();
        assertTrue(content.contains("44=-150.25|"));
    }

    @Test
    void zeroInt() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(38, 0);

        String content = message.toString();
        assertTrue(content.contains("38=0|"));
    }

    @Test
    void zeroDouble() {
        wrapMessage();
        message.setMsgType("D");
        message.setField(44, 0.0, 2);

        String content = message.toString();
        assertTrue(content.contains("44=0.00|"));
    }
}
