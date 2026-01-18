package com.fixengine.message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that OutgoingFixMessage encoding produces valid FIX messages
 * that can be parsed by FixReader and match expected structure.
 */
class EncodingCompatibilityTest {

    private MessagePoolConfig config;
    private MessagePool pool;
    private FixReader reader;
    private IncomingFixMessage incomingMessage;

    @BeforeEach
    void setUp() {
        config = MessagePoolConfig.builder()
                .poolSize(4)
                .maxMessageLength(4096)
                .maxTagNumber(1000)
                .beginString("FIX.4.4")
                .senderCompId("SENDER")
                .targetCompId("TARGET")
                .build();
        pool = new MessagePool(config);
        reader = new FixReader();
        incomingMessage = new IncomingFixMessage(4096, 1000);
    }

    private static String asString(CharSequence cs) {
        return cs != null ? cs.toString() : null;
    }

    @Test
    void pooledMessage_parsableByFixReader() throws InterruptedException {
        OutgoingFixMessage msg = pool.acquire();
        msg.setMsgType("D");
        msg.setField(11, "ORDER-001");
        msg.setField(55, "AAPL");
        msg.setField(54, '1');
        msg.setField(38, 100);
        msg.setField(44, 150.25, 2);
        msg.setField(60, "20240615-14:30:45.123");

        byte[] encoded = msg.prepareForSend(12345, System.currentTimeMillis());
        int length = msg.getLength();

        // Parse with FixReader
        reader.addData(java.nio.ByteBuffer.wrap(encoded, 0, length));
        boolean parsed = reader.readIncomingMessage(incomingMessage);

        assertTrue(parsed, "FixReader should parse the message");
        assertEquals("D", incomingMessage.getMsgType());
        assertEquals("ORDER-001", asString(incomingMessage.getCharSequence(11)));
        assertEquals("AAPL", asString(incomingMessage.getCharSequence(55)));
        assertEquals('1', incomingMessage.getChar(54));
        assertEquals(100, incomingMessage.getInt(38));
        assertEquals("150.25", asString(incomingMessage.getCharSequence(44)));
    }

    @Test
    void pooledMessage_hasCorrectHeaderFields() throws InterruptedException {
        OutgoingFixMessage msg = pool.acquire();
        msg.setMsgType("D");
        msg.setField(11, "ORDER-001");

        long sendTime = System.currentTimeMillis();
        byte[] encoded = msg.prepareForSend(42, sendTime);
        int length = msg.getLength();

        reader.addData(java.nio.ByteBuffer.wrap(encoded, 0, length));
        boolean parsed = reader.readIncomingMessage(incomingMessage);

        assertTrue(parsed);
        assertEquals("FIX.4.4", asString(incomingMessage.getCharSequence(8)));
        assertEquals("D", incomingMessage.getMsgType());
        assertEquals(42, incomingMessage.getSeqNum());
        assertEquals("SENDER", asString(incomingMessage.getCharSequence(49)));
        assertEquals("TARGET", asString(incomingMessage.getCharSequence(56)));

        // SendingTime should be present
        String sendingTime = asString(incomingMessage.getCharSequence(52));
        assertNotNull(sendingTime);
        assertTrue(sendingTime.matches("\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
    }

    @Test
    void pooledMessage_checksumIsValid() throws InterruptedException {
        OutgoingFixMessage msg = pool.acquire();
        msg.setMsgType("D");
        msg.setField(11, "ORDER-001");

        byte[] encoded = msg.prepareForSend(1, System.currentTimeMillis());
        int length = msg.getLength();

        // Calculate checksum manually
        int expectedChecksum = 0;
        int checksumStart = -1;
        for (int i = 0; i < length - 7; i++) {  // -7 for "10=XXX|"
            if (encoded[i] == '1' && encoded[i + 1] == '0' && encoded[i + 2] == '=') {
                checksumStart = i;
                break;
            }
            expectedChecksum += (encoded[i] & 0xFF);
        }
        expectedChecksum %= 256;

        // Extract actual checksum from message
        String content = new String(encoded, 0, length, StandardCharsets.US_ASCII);
        int checksumIdx = content.lastIndexOf("10=");
        String checksumStr = content.substring(checksumIdx + 3, checksumIdx + 6);
        int actualChecksum = Integer.parseInt(checksumStr);

        assertEquals(expectedChecksum, actualChecksum, "Checksum should match");
    }

    @Test
    void pooledMessage_bodyLengthIsCorrect() throws InterruptedException {
        OutgoingFixMessage msg = pool.acquire();
        msg.setMsgType("D");
        msg.setField(11, "ORDER-001");
        msg.setField(55, "AAPL");

        byte[] encoded = msg.prepareForSend(1, System.currentTimeMillis());
        int length = msg.getLength();

        reader.addData(java.nio.ByteBuffer.wrap(encoded, 0, length));
        boolean parsed = reader.readIncomingMessage(incomingMessage);

        // The fact that FixReader successfully parsed the message means
        // the body length is correct (FixReader validates this)
        assertTrue(parsed, "Message should be parseable, indicating valid body length");

        // Additionally verify body length is reasonable
        int bodyLength = incomingMessage.getInt(9);
        assertTrue(bodyLength > 0, "Body length should be positive");
        assertTrue(bodyLength < length, "Body length should be less than total message length");
    }

    @Test
    void pooledMessage_multipleMessages_independent() throws InterruptedException {
        OutgoingFixMessage msg1 = pool.acquire();
        msg1.setMsgType("D");
        msg1.setField(11, "ORDER-001");
        msg1.setField(55, "AAPL");

        OutgoingFixMessage msg2 = pool.acquire();
        msg2.setMsgType("F");
        msg2.setField(11, "CANCEL-001");
        msg2.setField(55, "GOOGL");

        byte[] encoded1 = msg1.prepareForSend(1, System.currentTimeMillis());
        int length1 = msg1.getLength();

        byte[] encoded2 = msg2.prepareForSend(2, System.currentTimeMillis());
        int length2 = msg2.getLength();

        // Parse first message
        reader.addData(java.nio.ByteBuffer.wrap(encoded1, 0, length1));
        boolean parsed1 = reader.readIncomingMessage(incomingMessage);

        assertTrue(parsed1);
        assertEquals("D", incomingMessage.getMsgType());
        assertEquals("ORDER-001", asString(incomingMessage.getCharSequence(11)));
        assertEquals("AAPL", asString(incomingMessage.getCharSequence(55)));

        // Reset and parse second message
        incomingMessage.reset();
        reader.addData(java.nio.ByteBuffer.wrap(encoded2, 0, length2));
        boolean parsed2 = reader.readIncomingMessage(incomingMessage);

        assertTrue(parsed2);
        assertEquals("F", incomingMessage.getMsgType());
        assertEquals("CANCEL-001", asString(incomingMessage.getCharSequence(11)));
        assertEquals("GOOGL", asString(incomingMessage.getCharSequence(55)));
    }

    @Test
    void pooledMessage_afterReset_stillValid() throws InterruptedException {
        OutgoingFixMessage msg = pool.acquire();

        // First use
        msg.setMsgType("D");
        msg.setField(11, "ORDER-001");
        byte[] encoded1 = msg.prepareForSend(1, System.currentTimeMillis());
        int length1 = msg.getLength();

        reader.addData(java.nio.ByteBuffer.wrap(encoded1, 0, length1));
        boolean parsed1 = reader.readIncomingMessage(incomingMessage);
        assertTrue(parsed1);
        assertEquals("D", incomingMessage.getMsgType());

        // Reset and reuse
        msg.reset();
        incomingMessage.reset();
        msg.setMsgType("F");
        msg.setField(11, "CANCEL-001");
        byte[] encoded2 = msg.prepareForSend(2, System.currentTimeMillis());
        int length2 = msg.getLength();

        reader.addData(java.nio.ByteBuffer.wrap(encoded2, 0, length2));
        boolean parsed2 = reader.readIncomingMessage(incomingMessage);
        assertTrue(parsed2);
        assertEquals("F", incomingMessage.getMsgType());
        assertEquals("CANCEL-001", asString(incomingMessage.getCharSequence(11)));

        // Should not have old fields
        assertNull(incomingMessage.getCharSequence(55));  // Was never set on msg after reset
    }

    @Test
    void pooledMessage_negativeNumbers() throws InterruptedException {
        OutgoingFixMessage msg = pool.acquire();
        msg.setMsgType("8");  // ExecutionReport
        msg.setField(11, "ORDER-001");
        msg.setField(38, -100);  // Negative quantity (edge case)

        byte[] encoded = msg.prepareForSend(1, System.currentTimeMillis());
        int length = msg.getLength();

        reader.addData(java.nio.ByteBuffer.wrap(encoded, 0, length));
        boolean parsed = reader.readIncomingMessage(incomingMessage);

        assertTrue(parsed);
        assertEquals(-100, incomingMessage.getInt(38));
    }

    @Test
    void pooledMessage_largeSequenceNumber() throws InterruptedException {
        OutgoingFixMessage msg = pool.acquire();
        msg.setMsgType("0");  // Heartbeat

        // Use a large sequence number (but within 8 digits)
        byte[] encoded = msg.prepareForSend(99999999, System.currentTimeMillis());
        int length = msg.getLength();

        reader.addData(java.nio.ByteBuffer.wrap(encoded, 0, length));
        boolean parsed = reader.readIncomingMessage(incomingMessage);

        assertTrue(parsed);
        assertEquals(99999999, incomingMessage.getSeqNum());
    }

    @Test
    void pooledMessage_specialCharactersInString() throws InterruptedException {
        OutgoingFixMessage msg = pool.acquire();
        msg.setMsgType("D");
        msg.setField(11, "ORDER-ABC.123_456");
        msg.setField(58, "Test message with spaces");

        byte[] encoded = msg.prepareForSend(1, System.currentTimeMillis());
        int length = msg.getLength();

        reader.addData(java.nio.ByteBuffer.wrap(encoded, 0, length));
        boolean parsed = reader.readIncomingMessage(incomingMessage);

        assertTrue(parsed);
        assertEquals("ORDER-ABC.123_456", asString(incomingMessage.getCharSequence(11)));
        assertEquals("Test message with spaces", asString(incomingMessage.getCharSequence(58)));
    }
}
