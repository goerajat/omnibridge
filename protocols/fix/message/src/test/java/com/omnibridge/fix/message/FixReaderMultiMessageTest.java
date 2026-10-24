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
    void sameMessagesWhateverTheChunkBoundaries() {
        StringBuilder stream = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            stream.append(fix("35=D|34=" + i + "|49=CLIENT|56=BROKER|11=ORD-" + i + "|58=" + "x".repeat(i * 7) + "|"));
        }
        byte[] bytes = stream.toString().getBytes(StandardCharsets.US_ASCII);

        java.util.Random random = new java.util.Random(42);
        for (int maxChunk : new int[] {1, 2, 7, 64, 1000, bytes.length}) {
            FixReader reader = new FixReader();
            IncomingFixMessage msg = new IncomingFixMessage();
            int expected = 1;
            int pos = 0;
            while (pos < bytes.length) {
                int n = Math.min(bytes.length - pos, 1 + random.nextInt(maxChunk));
                reader.addData(new UnsafeBuffer(bytes), pos, n);
                pos += n;
                while (reader.readIncomingMessage(msg)) {
                    assertEquals(expected, msg.getSeqNum(), "chunk size up to " + maxChunk);
                    assertEquals("ORD-" + expected, msg.getCharSequence(11).toString());
                    expected++;
                }
            }
            assertEquals(21, expected, "all messages read with chunk size up to " + maxChunk);
        }
    }

    @Test
    void severalMessagesAndASplitOneInOneBuffer() {
        FixReader reader = new FixReader();
        String split = fix("35=D|34=4|49=CLIENT|56=BROKER|11=ORDER-4|55=IBM|");
        int cut = split.length() / 2;
        feed(reader, fix("35=D|34=1|49=CLIENT|56=BROKER|11=ORDER-1|")
                + fix("35=D|34=2|49=CLIENT|56=BROKER|11=ORDER-2|")
                + fix("35=D|34=3|49=CLIENT|56=BROKER|11=ORDER-3|")
                + split.substring(0, cut));

        IncomingFixMessage msg = new IncomingFixMessage();
        for (int seq = 1; seq <= 3; seq++) {
            assertTrue(reader.readIncomingMessage(msg), "message " + seq);
            assertEquals(seq, msg.getSeqNum());
            assertEquals("ORDER-" + seq, msg.getCharSequence(11).toString());
        }
        assertFalse(reader.readIncomingMessage(msg), "half a message is not a message");

        // The next read completes it and brings another
        feed(reader, split.substring(cut) + fix("35=D|34=5|49=CLIENT|56=BROKER|11=ORDER-5|"));
        assertTrue(reader.readIncomingMessage(msg));
        assertEquals(4, msg.getSeqNum());
        assertEquals("ORDER-4", msg.getCharSequence(11).toString());
        assertEquals("IBM", msg.getCharSequence(55).toString());
        assertTrue(reader.readIncomingMessage(msg));
        assertEquals(5, msg.getSeqNum());
        assertFalse(reader.readIncomingMessage(msg));
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
