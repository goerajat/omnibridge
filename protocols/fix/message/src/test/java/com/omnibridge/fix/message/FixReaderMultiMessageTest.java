package com.omnibridge.fix.message;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When one read delivers several messages (TLS records, port-sharing handoff of the
 * bytes buffered behind a Logon), the message just returned by readIncomingMessage
 * must stay intact until the caller has processed it.
 */
class FixReaderMultiMessageTest {

    static String fix(String body) {
        String b = body.replace('|', '\u0001');
        String head = "8=FIX.4.4\u00019=" + b.length() + "\u0001";
        int sum = 0;
        for (byte x : (head + b).getBytes(StandardCharsets.US_ASCII)) {
            sum += x & 0xFF;
        }
        return head + b + String.format("10=%03d\u0001", sum % 256);
    }

    private static void feed(FixReader reader, String data) {
        byte[] bytes = data.getBytes(StandardCharsets.US_ASCII);
        reader.addData(new UnsafeBuffer(bytes), 0, bytes.length);
    }

    @Test
    void firstMessageSurvivesWhenTwoArriveTogether() {
        FixReader reader = new FixReader();
        feed(reader, fix("35=D|34=2|49=CLIENT|56=BROKER|11=ORDER-1|55=AAPL|")
                + fix("35=D|34=3|49=CLIENT|56=BROKER|11=ORDER-2|55=MSFT|"));

        IncomingFixMessage first = new IncomingFixMessage();
        assertTrue(reader.readIncomingMessage(first));

        // The session processes `first` here, before reading the next message.
        assertEquals(2, first.getSeqNum());
        assertEquals("ORDER-1", first.getCharSequence(11).toString());
        assertEquals("AAPL", first.getCharSequence(55).toString());

        IncomingFixMessage second = new IncomingFixMessage();
        assertTrue(reader.readIncomingMessage(second));
        assertEquals(3, second.getSeqNum());
        assertEquals("ORDER-2", second.getCharSequence(11).toString());
    }

    @Test
    void logonSurvivesWhenOrderFollowsInSameBuffer() {
        FixReader reader = new FixReader();
        feed(reader, fix("35=A|34=1|49=CLIENT|56=BROKER|98=0|108=30|")
                + fix("35=D|34=2|49=CLIENT|56=BROKER|11=ORDER-1|55=AAPL|54=1|38=100|"));

        IncomingFixMessage logon = new IncomingFixMessage();
        assertTrue(reader.readIncomingMessage(logon));

        assertEquals("A", logon.getMsgType());
        assertEquals(1, logon.getSeqNum());
        assertEquals(30, logon.getInt(108));
        assertEquals("CLIENT", logon.getSenderCompId());
    }
}
