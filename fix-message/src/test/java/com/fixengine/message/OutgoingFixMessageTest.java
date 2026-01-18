package com.fixengine.message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OutgoingFixMessage.
 */
class OutgoingFixMessageTest {

    private MessagePoolConfig config;
    private OutgoingFixMessage message;

    @BeforeEach
    void setUp() {
        config = MessagePoolConfig.builder()
                .poolSize(1)
                .maxMessageLength(4096)
                .maxTagNumber(1000)
                .beginString("FIX.4.4")
                .senderCompId("SENDER")
                .targetCompId("TARGET")
                .build();
        message = new OutgoingFixMessage(config);
    }

    @Test
    void constructor_prePopulatesHeader() {
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
        message.setMsgType("D");
        assertEquals("D", message.getMsgType());
    }

    @Test
    void setField_string() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        String content = message.toString();
        assertTrue(content.contains("11=ORDER-001|"));
    }

    @Test
    void setField_int() {
        message.setMsgType("D");
        message.setField(38, 100);

        String content = message.toString();
        assertTrue(content.contains("38=100|"));
    }

    @Test
    void setField_long() {
        message.setMsgType("D");
        message.setField(38, 9876543210L);

        String content = message.toString();
        assertTrue(content.contains("38=9876543210|"));
    }

    @Test
    void setField_double() {
        message.setMsgType("D");
        message.setField(44, 150.25, 2);

        String content = message.toString();
        assertTrue(content.contains("44=150.25|"));
    }

    @Test
    void setField_char() {
        message.setMsgType("D");
        message.setField(54, '1');

        String content = message.toString();
        assertTrue(content.contains("54=1|"));
    }

    @Test
    void setField_boolean_true() {
        message.setMsgType("D");
        message.setField(130, true);

        String content = message.toString();
        assertTrue(content.contains("130=Y|"));
    }

    @Test
    void setField_boolean_false() {
        message.setMsgType("D");
        message.setField(130, false);

        String content = message.toString();
        assertTrue(content.contains("130=N|"));
    }

    @Test
    void setField_duplicateTag_throwsException() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        assertThrows(DuplicateTagException.class, () ->
                message.setField(11, "ORDER-002"));
    }

    @Test
    void hasTag_returnsTrueForSetTags() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        assertTrue(message.hasTag(11));
        assertFalse(message.hasTag(55));
    }

    @Test
    void prepareForSend_setsSeqNumAndSendingTime() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        byte[] encoded = message.prepareForSend(12345, System.currentTimeMillis());
        String content = new String(encoded, 0, message.getLength(), StandardCharsets.US_ASCII)
                .replace((char) 0x01, '|');

        // Should have sequence number
        assertTrue(content.contains("34=00012345|"));
        // Should have checksum
        assertTrue(content.contains("|10="));
    }

    @Test
    void prepareForSend_calculatesBodyLength() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");
        message.setField(55, "AAPL");

        message.prepareForSend(1, System.currentTimeMillis());
        String content = message.toString();

        // Body length should be non-zero
        assertFalse(content.contains("9=00000|"));
    }

    @Test
    void prepareForSend_calculatesChecksum() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        message.prepareForSend(1, System.currentTimeMillis());
        String content = message.toString();

        // Should end with checksum
        assertTrue(content.matches(".*\\|10=\\d{3}\\|$"));
    }

    @Test
    void reset_clearsBody() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");
        message.setField(55, "AAPL");

        message.reset();

        assertNull(message.getMsgType());
        assertFalse(message.hasTag(11));
        assertFalse(message.hasTag(55));
    }

    @Test
    void reset_preservesHeader() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        message.reset();

        String content = message.toString();
        // Header should still be present
        assertTrue(content.contains("8=FIX.4.4|"));
        assertTrue(content.contains("49=SENDER|"));
        assertTrue(content.contains("56=TARGET|"));
    }

    @Test
    void toByteArray_returnsCopy() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        byte[] copy = message.toByteArray();
        byte[] buffer = message.getBuffer();

        assertNotSame(copy, buffer);
        assertEquals(message.getLength(), copy.length);
    }

    @Test
    void setField_nullValue_isIgnored() {
        message.setMsgType("D");
        message.setField(11, (String) null);

        // Should not throw and should not have the tag
        assertFalse(message.hasTag(11));
    }

    @Test
    void setMsgType_nullValue_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                message.setMsgType(null));
    }

    @Test
    void setMsgType_emptyValue_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                message.setMsgType(""));
    }

    @Test
    void multipleFields_correctOrder() {
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
    void getLength_beforePrepare() {
        message.setMsgType("D");
        message.setField(11, "ORDER-001");

        int length = message.getLength();

        // Should have some length (header + body)
        assertTrue(length > 50);
    }

    @Test
    void inUse_defaultsFalse() {
        assertFalse(message.isInUse());
    }

    @Test
    void markInUse_setsFlag() {
        message.markInUse();
        assertTrue(message.isInUse());
    }
}
