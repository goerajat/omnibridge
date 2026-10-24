package com.omnibridge.fix.message;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-message paths that used to allocate (getMsgType, getDouble, SendingTime, "ORD" + n)
 * give the same results as the String-based code they replaced.
 */
class AllocationFreeFieldsTest {

    private static IncomingFixMessage incoming(String body) {
        byte[] bytes = ("8=FIX.4.4|9=0|" + body).replace('|', (char) 1).getBytes(StandardCharsets.US_ASCII);
        IncomingFixMessage msg = new IncomingFixMessage();
        msg.wrap(bytes, 0, bytes.length);
        return msg;
    }

    @Test
    void msgTypeIsTheSameInstanceForEveryMessage() {
        for (String type : new String[] {"D", "8", "0", "A", "AE", "BE", "j", "x9"}) {
            String first = incoming("35=" + type + "|").getMsgType();
            String second = incoming("35=" + type + "|").getMsgType();
            assertEquals(type, first);
            assertSame(first, second, "MsgType " + type);
        }
        assertSame(FixTags.MsgTypes.NewOrderSingle, incoming("35=D|").getMsgType());
        assertEquals("ABC", incoming("35=ABC|").getMsgType());
        assertNull(incoming("34=1|").getMsgType());
    }

    @Test
    void decimalsParseExactlyAsDoubleParseDouble() {
        String[] values = {
                "0", "1", "-1", "150.25", "150.2500", "0.1", "0.3", "-0.0", "123456789012345",
                "1234567.891", "99999999.99999999", "5.", ".5", "-.5", "+2.5", "0.000000000000000000001",
                "9007199254740991", "9007199254740993", "12345678901234567890.5", "1e5", "1.5E-3"
        };
        for (String v : values) {
            assertEquals(Double.parseDouble(v), incoming("44=" + v + "|").getDouble(44), "Price=" + v);
        }
        Random random = new Random(42);
        for (int i = 0; i < 100_000; i++) {
            long mantissa = random.nextLong() % 1_000_000_000_000_000L;
            int scale = random.nextInt(10);
            String v = java.math.BigDecimal.valueOf(mantissa, scale).toPlainString();
            assertEquals(Double.parseDouble(v), incoming("44=" + v + "|").getDouble(44), "Price=" + v);
        }
    }

    @Test
    void malformedOrMissingDecimalsAreZero() {
        assertEquals(0.0, incoming("35=D|").getDouble(44));
        assertEquals(0.0, incoming("44=|").getDouble(44));
        assertEquals(0.0, incoming("44=abc|").getDouble(44));
        assertEquals(0.0, incoming("44=-|").getDouble(44));
        assertEquals(0.0, incoming("44=1.2.3|").getDouble(44));
    }

    @Test
    void sendingTimeMatchesJavaTime() {
        DateTimeFormatter fix = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
        RingBufferOutgoingMessage out = new RingBufferOutgoingMessage(MessagePoolConfig.builder()
                .maxMessageLength(256).beginString("FIX.4.4").senderCompId("S").targetCompId("T").build());
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(512));

        long[] fixed = {
                0L,                                   // 1970-01-01
                951_782_400_000L - 1, 951_782_400_000L, // 2000-02-29 starts (leap century)
                1_709_164_800_000L,                   // 2024-02-29
                4_107_542_400_000L,                   // 2100-03-01 (2100 is not a leap year)
                1_798_761_599_999L,                   // 2026-12-31 23:59:59.999
                253_402_300_799_999L                  // 9999-12-31 23:59:59.999
        };
        Random random = new Random(7);
        long[] times = new long[fixed.length + 20_000];
        System.arraycopy(fixed, 0, times, 0, fixed.length);
        for (int i = fixed.length; i < times.length; i++) {
            times[i] = (long) (random.nextDouble() * 253_402_300_799_999L);
        }
        // Same message instance throughout, so the cached day is exercised across changes
        for (long t : times) {
            out.wrap(buffer, 0, 512, 1, 0, "FIX.4.4", "S", "T", "0");
            int length = out.prepareForSend(t);
            byte[] bytes = new byte[length];
            buffer.getBytes(RingBufferOutgoingMessage.LENGTH_PREFIX_SIZE, bytes);
            IncomingFixMessage in = new IncomingFixMessage();
            in.wrap(bytes, 0, length);
            assertEquals(fix.format(Instant.ofEpochMilli(t)), in.getCharSequence(52).toString(), "epoch millis " + t);
        }
    }

    @Test
    void prefixedNumberField() {
        RingBufferOutgoingMessage out = new RingBufferOutgoingMessage(MessagePoolConfig.builder()
                .maxMessageLength(256).beginString("FIX.4.4").senderCompId("S").targetCompId("T").build());
        out.wrap(new UnsafeBuffer(ByteBuffer.allocateDirect(512)), 0, 512, 1, 0, "FIX.4.4", "S", "T", "8");
        out.setField(37, "ORD", 42L);
        out.setField(17, "EXEC", 0L);
        out.setField(198, "", 9_000_000_000L);
        String text = out.toString();
        assertTrue(text.contains("|37=ORD42|17=EXEC0|198=9000000000|"), text);
        assertThrows(DuplicateTagException.class, () -> out.setField(37, "ORD", 1L));
    }
}
