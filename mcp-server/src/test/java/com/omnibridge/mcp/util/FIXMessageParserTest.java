package com.omnibridge.mcp.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FIXMessageParserTest {

    // SOH-delimited FIX message
    private static final String SOH_MSG =
            "8=FIX.4.2\u000135=D\u000149=SENDER\u000156=TARGET\u000134=1\u0001" +
            "52=20240115-10:30:00\u000111=ORD001\u000155=AAPL\u000154=1\u000138=100\u000144=150.50\u000140=2\u000110=123\u0001";

    // Pipe-delimited FIX message
    private static final String PIPE_MSG =
            "8=FIX.4.2|35=8|49=TARGET|56=SENDER|34=2|52=20240115-10:30:01|" +
            "11=ORD001|37=EX001|55=AAPL|150=0|39=0|10=456|";

    @Test
    void parseSOHMessage() {
        Map<Integer, String> tags = FIXMessageParser.parse(SOH_MSG.getBytes());
        assertEquals("FIX.4.2", tags.get(8));
        assertEquals("D", tags.get(35));
        assertEquals("SENDER", tags.get(49));
        assertEquals("TARGET", tags.get(56));
        assertEquals("ORD001", tags.get(11));
        assertEquals("AAPL", tags.get(55));
        assertEquals("100", tags.get(38));
        assertEquals("150.50", tags.get(44));
    }

    @Test
    void parsePipeMessage() {
        Map<Integer, String> tags = FIXMessageParser.parse(PIPE_MSG.getBytes());
        assertEquals("8", tags.get(35));
        assertEquals("TARGET", tags.get(49));
        assertEquals("EX001", tags.get(37));
        assertEquals("0", tags.get(150));
    }

    @Test
    void extractTag() {
        byte[] raw = SOH_MSG.getBytes();
        assertEquals("D", FIXMessageParser.extractTag(raw, 35));
        assertEquals("SENDER", FIXMessageParser.extractTag(raw, 49));
        assertEquals("TARGET", FIXMessageParser.extractTag(raw, 56));
        assertEquals("ORD001", FIXMessageParser.extractTag(raw, 11));
        assertEquals("AAPL", FIXMessageParser.extractTag(raw, 55));
        assertNull(FIXMessageParser.extractTag(raw, 37)); // not present
    }

    @Test
    void extractMsgType() {
        assertEquals("D", FIXMessageParser.extractMsgType(SOH_MSG.getBytes()));
        assertEquals("8", FIXMessageParser.extractMsgType(PIPE_MSG.getBytes()));
    }

    @Test
    void extractCompIds() {
        byte[] raw = SOH_MSG.getBytes();
        assertEquals("SENDER", FIXMessageParser.extractSenderCompId(raw));
        assertEquals("TARGET", FIXMessageParser.extractTargetCompId(raw));
    }

    @Test
    void nullAndEmptyHandling() {
        assertNull(FIXMessageParser.extractTag(null, 35));
        assertNull(FIXMessageParser.extractTag(new byte[0], 35));
        assertNull(FIXMessageParser.extractMsgType(null));
        assertTrue(FIXMessageParser.parse(null).isEmpty());
        assertTrue(FIXMessageParser.parse(new byte[0]).isEmpty());
    }

    @Test
    void parsePreservesFieldOrder() {
        Map<Integer, String> tags = FIXMessageParser.parse(SOH_MSG.getBytes());
        Integer[] keys = tags.keySet().toArray(new Integer[0]);
        // First tag should be 8 (BeginString)
        assertEquals(8, keys[0]);
        // Second tag should be 35 (MsgType)
        assertEquals(35, keys[1]);
    }

    @Test
    void extractTagDoesNotMatchPartialTagNumber() {
        // Tag 4 should not match tag 40 or 44
        String msg = "8=FIX.4.2\u000135=D\u000140=2\u000144=150.50\u00014=X\u000110=123\u0001";
        byte[] raw = msg.getBytes();
        assertEquals("X", FIXMessageParser.extractTag(raw, 4));
        assertEquals("2", FIXMessageParser.extractTag(raw, 40));
        assertEquals("150.50", FIXMessageParser.extractTag(raw, 44));
    }
}
