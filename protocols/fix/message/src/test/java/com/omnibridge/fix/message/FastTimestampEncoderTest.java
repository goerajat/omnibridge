package com.omnibridge.fix.message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FastTimestampEncoder.
 */
class FastTimestampEncoderTest {

    @BeforeEach
    void setUp() {
        FastTimestampEncoder.resetCache();
    }

    @Test
    void encode_returnsCorrectLength() {
        byte[] buffer = new byte[30];
        int written = FastTimestampEncoder.encode(System.currentTimeMillis(), buffer, 0);

        assertEquals(FastTimestampEncoder.TIMESTAMP_LENGTH, written);
    }

    @Test
    void encode_correctFormat() {
        // 2024-06-15 14:30:45.123 UTC
        ZonedDateTime zdt = ZonedDateTime.of(2024, 6, 15, 14, 30, 45, 123_000_000, ZoneOffset.UTC);
        long millis = zdt.toInstant().toEpochMilli();

        byte[] buffer = new byte[30];
        FastTimestampEncoder.encode(millis, buffer, 0);

        String result = new String(buffer, 0, 21, StandardCharsets.US_ASCII);
        assertEquals("20240615-14:30:45.123", result);
    }

    @Test
    void encode_midnight() {
        ZonedDateTime zdt = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        long millis = zdt.toInstant().toEpochMilli();

        byte[] buffer = new byte[30];
        FastTimestampEncoder.encode(millis, buffer, 0);

        String result = new String(buffer, 0, 21, StandardCharsets.US_ASCII);
        assertEquals("20240101-00:00:00.000", result);
    }

    @Test
    void encode_endOfDay() {
        ZonedDateTime zdt = ZonedDateTime.of(2024, 12, 31, 23, 59, 59, 999_000_000, ZoneOffset.UTC);
        long millis = zdt.toInstant().toEpochMilli();

        byte[] buffer = new byte[30];
        FastTimestampEncoder.encode(millis, buffer, 0);

        String result = new String(buffer, 0, 21, StandardCharsets.US_ASCII);
        assertEquals("20241231-23:59:59.999", result);
    }

    @Test
    void encodeNoMillis_correctFormat() {
        ZonedDateTime zdt = ZonedDateTime.of(2024, 6, 15, 14, 30, 45, 0, ZoneOffset.UTC);
        long millis = zdt.toInstant().toEpochMilli();

        byte[] buffer = new byte[30];
        int written = FastTimestampEncoder.encodeNoMillis(millis, buffer, 0);

        assertEquals(FastTimestampEncoder.TIMESTAMP_LENGTH_NO_MILLIS, written);
        String result = new String(buffer, 0, 17, StandardCharsets.US_ASCII);
        assertEquals("20240615-14:30:45", result);
    }

    @Test
    void encode_atOffset() {
        ZonedDateTime zdt = ZonedDateTime.of(2024, 6, 15, 14, 30, 45, 123_000_000, ZoneOffset.UTC);
        long millis = zdt.toInstant().toEpochMilli();

        byte[] buffer = new byte[30];
        buffer[0] = 'X';
        buffer[1] = 'X';

        FastTimestampEncoder.encode(millis, buffer, 2);

        assertEquals('X', (char) buffer[0]);
        assertEquals('X', (char) buffer[1]);
        String result = new String(buffer, 2, 21, StandardCharsets.US_ASCII);
        assertEquals("20240615-14:30:45.123", result);
    }

    @Test
    void encode_instant() {
        ZonedDateTime zdt = ZonedDateTime.of(2024, 6, 15, 14, 30, 45, 123_000_000, ZoneOffset.UTC);
        Instant instant = zdt.toInstant();

        byte[] buffer = new byte[30];
        FastTimestampEncoder.encode(instant, buffer, 0);

        String result = new String(buffer, 0, 21, StandardCharsets.US_ASCII);
        assertEquals("20240615-14:30:45.123", result);
    }

    @Test
    void encodeNow_returnsValidTimestamp() {
        byte[] buffer = new byte[30];
        int written = FastTimestampEncoder.encodeNow(buffer, 0);

        assertEquals(21, written);
        String result = new String(buffer, 0, 21, StandardCharsets.US_ASCII);

        // Verify format: YYYYMMDD-HH:MM:SS.sss
        assertTrue(result.matches("\\d{8}-\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
    }

    @Test
    void encode_cachesDate() {
        // First encode
        ZonedDateTime zdt1 = ZonedDateTime.of(2024, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);
        byte[] buffer1 = new byte[30];
        FastTimestampEncoder.encode(zdt1.toInstant().toEpochMilli(), buffer1, 0);

        // Second encode same day, different time
        ZonedDateTime zdt2 = ZonedDateTime.of(2024, 6, 15, 14, 30, 45, 0, ZoneOffset.UTC);
        byte[] buffer2 = new byte[30];
        FastTimestampEncoder.encode(zdt2.toInstant().toEpochMilli(), buffer2, 0);

        // Both should have same date part
        String date1 = new String(buffer1, 0, 8, StandardCharsets.US_ASCII);
        String date2 = new String(buffer2, 0, 8, StandardCharsets.US_ASCII);
        assertEquals("20240615", date1);
        assertEquals("20240615", date2);
    }
}
