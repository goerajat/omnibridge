package com.omnibridge.fix.message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FastIntEncoder.
 */
class FastIntEncoderTest {

    @Test
    void encodeFixedWidth_zeroPadded() {
        byte[] buffer = new byte[10];
        int written = FastIntEncoder.encodeFixedWidth(42, buffer, 0, 8, '0');

        assertEquals(8, written);
        assertEquals("00000042", new String(buffer, 0, 8, StandardCharsets.US_ASCII));
    }

    @Test
    void encodeFixedWidth_zero() {
        byte[] buffer = new byte[10];
        int written = FastIntEncoder.encodeFixedWidth(0, buffer, 0, 5, '0');

        assertEquals(5, written);
        assertEquals("00000", new String(buffer, 0, 5, StandardCharsets.US_ASCII));
    }

    @Test
    void encodeFixedWidth_maxValue() {
        byte[] buffer = new byte[10];
        int written = FastIntEncoder.encodeFixedWidth(99999999, buffer, 0, 8, '0');

        assertEquals(8, written);
        assertEquals("99999999", new String(buffer, 0, 8, StandardCharsets.US_ASCII));
    }

    @Test
    void encodeFixedWidth_overflow() {
        byte[] buffer = new byte[10];
        assertThrows(IllegalArgumentException.class, () ->
                FastIntEncoder.encodeFixedWidth(100, buffer, 0, 2, '0'));
    }

    @Test
    void encodeFixedWidth_negative() {
        byte[] buffer = new byte[10];
        assertThrows(IllegalArgumentException.class, () ->
                FastIntEncoder.encodeFixedWidth(-1, buffer, 0, 8, '0'));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0",
            "1, 1",
            "42, 42",
            "123, 123",
            "-42, -42",
            "2147483647, 2147483647"
    })
    void encode_variableWidth(int value, int expected) {
        byte[] buffer = new byte[20];
        int written = FastIntEncoder.encode(value, buffer, 0);

        String result = new String(buffer, 0, written, StandardCharsets.US_ASCII);
        assertEquals(String.valueOf(expected), result);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 1",
            "9, 1",
            "10, 2",
            "99, 2",
            "100, 3",
            "999, 3",
            "1000, 4",
            "9999, 4",
            "10000, 5",
            "99999, 5",
            "100000, 6",
            "999999, 6",
            "1000000, 7",
            "9999999, 7",
            "10000000, 8",
            "99999999, 8",
            "100000000, 9",
            "999999999, 9",
            "1000000000, 10"
    })
    void digitCount_int(int value, int expected) {
        assertEquals(expected, FastIntEncoder.digitCount(value));
    }

    @Test
    void encode_long() {
        byte[] buffer = new byte[25];
        int written = FastIntEncoder.encode(9876543210L, buffer, 0);

        assertEquals("9876543210", new String(buffer, 0, written, StandardCharsets.US_ASCII));
    }

    @Test
    void encode_double_withDecimals() {
        byte[] buffer = new byte[30];
        int written = FastIntEncoder.encode(123.456, buffer, 0, 2);

        assertEquals("123.46", new String(buffer, 0, written, StandardCharsets.US_ASCII));
    }

    @Test
    void encode_double_wholeNumber() {
        byte[] buffer = new byte[30];
        int written = FastIntEncoder.encode(100.0, buffer, 0, 2);

        assertEquals("100.00", new String(buffer, 0, written, StandardCharsets.US_ASCII));
    }

    @Test
    void encode_double_zeroDecimals() {
        byte[] buffer = new byte[30];
        int written = FastIntEncoder.encode(123.456, buffer, 0, 0);

        assertEquals("123", new String(buffer, 0, written, StandardCharsets.US_ASCII));
    }

    @Test
    void encode_double_negative() {
        byte[] buffer = new byte[30];
        int written = FastIntEncoder.encode(-123.45, buffer, 0, 2);

        assertEquals("-123.45", new String(buffer, 0, written, StandardCharsets.US_ASCII));
    }

    @Test
    void encodeFixedWidth_long() {
        byte[] buffer = new byte[20];
        int written = FastIntEncoder.encodeFixedWidth(123456789012L, buffer, 0, 15, '0');

        assertEquals(15, written);
        assertEquals("000123456789012", new String(buffer, 0, 15, StandardCharsets.US_ASCII));
    }

    @Test
    void encode_atOffset() {
        byte[] buffer = new byte[20];
        buffer[0] = 'X';
        buffer[1] = 'X';

        int written = FastIntEncoder.encode(42, buffer, 2);

        assertEquals(2, written);
        assertEquals("XX42", new String(buffer, 0, 4, StandardCharsets.US_ASCII));
    }
}
