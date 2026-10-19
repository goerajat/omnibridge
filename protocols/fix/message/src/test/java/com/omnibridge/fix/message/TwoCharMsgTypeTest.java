package com.omnibridge.fix.message;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The header reserves one character for MsgType (35), so a two-character type
 * (AE, BE, ...) must not spill into the MsgSeqNum (34) field that follows it.
 */
class TwoCharMsgTypeTest {

    private static final int BUFFER_SIZE = 4096;
    private static final int SEQ_NUM = 7;

    private RingBufferOutgoingMessage message;
    private UnsafeBuffer buffer;

    @BeforeEach
    void setUp() {
        MessagePoolConfig config = MessagePoolConfig.builder()
                .maxMessageLength(BUFFER_SIZE)
                .maxTagNumber(1000)
                .beginString("FIX.4.4")
                .senderCompId("SENDER")
                .targetCompId("TARGET")
                .build();
        message = new RingBufferOutgoingMessage(config);
        buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE + RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE));
    }

    private byte[] encode(String msgType) {
        message.wrap(buffer, 0, BUFFER_SIZE + RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE,
                SEQ_NUM, 0, "FIX.4.4", "SENDER", "TARGET");
        message.setMsgType(msgType);
        message.setField(58, "text");
        int length = message.prepareForSend(1_700_000_000_000L);
        byte[] bytes = new byte[length];
        buffer.getBytes(RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE, bytes);
        return bytes;
    }

    @ParameterizedTest
    @ValueSource(strings = {"D", "AE", "BE"})
    void msgTypeIsFollowedByIntactSeqNumField(String msgType) {
        String wire = new String(encode(msgType), StandardCharsets.US_ASCII);

        assertTrue(wire.contains("\u000135=" + msgType + "\u000134="),
                "35=" + msgType + " must be followed by 34=, got: " + wire.replace('\u0001', '|'));
    }

    @ParameterizedTest
    @ValueSource(strings = {"D", "AE", "XYZ"})
    void encodesExactWireBytes(String msgType) {
        String body = "35=" + msgType + "|34=00000007|49=SENDER|56=TARGET|52=20231114-22:13:20.000|58=text|";
        String head = "8=FIX.4.4|9=" + String.format("%05d", body.length()) + "|";
        int sum = 0;
        for (char c : (head + body).replace('|', '\u0001').toCharArray()) {
            sum += c;
        }
        String expected = head + body + String.format("10=%03d|", sum % 256);

        String wire = new String(encode(msgType), StandardCharsets.US_ASCII).replace('\u0001', '|');

        assertEquals(expected, wire);
    }

    @Test
    void msgTypeLengthCannotChangeAfterBodyFields() {
        message.wrap(buffer, 0, BUFFER_SIZE + RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE,
                SEQ_NUM, 0, "FIX.4.4", "SENDER", "TARGET");
        message.setMsgType("D");
        message.setField(11, "ORD-1");

        assertThrows(IllegalStateException.class, () -> message.setMsgType("AE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"D", "AE", "BE"})
    void parsedBackWithSameMsgTypeAndSeqNum(String msgType) {
        byte[] bytes = encode(msgType);

        FixReader reader = new FixReader();
        reader.addData(new UnsafeBuffer(bytes), 0, bytes.length);
        IncomingFixMessage parsed = new IncomingFixMessage();

        assertTrue(reader.readIncomingMessage(parsed), "encoded message must parse");
        assertEquals(msgType, parsed.getMsgType());
        assertTrue(parsed.hasField(34), "MsgSeqNum (34) missing from encoded " + msgType);
        assertEquals(SEQ_NUM, parsed.getSeqNum());
    }
}
