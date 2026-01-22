package com.fixengine.message;

import com.fixengine.config.ClockProvider;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Zero-allocation FIX timestamp encoder.
 *
 * <p>Encodes timestamps in the standard FIX format: {@code YYYYMMDD-HH:MM:SS.sss}
 * (21 bytes total). This class is designed for maximum performance in latency-sensitive
 * scenarios by avoiding any object allocation during encoding.</p>
 *
 * <p>The encoder uses a cached date approach where the date portion (YYYYMMDD-) is
 * pre-computed and only updated when the day changes. The time portion is calculated
 * directly from the epoch milliseconds.</p>
 */
public final class FastTimestampEncoder {

    /**
     * The fixed length of a FIX timestamp with milliseconds: YYYYMMDD-HH:MM:SS.sss
     */
    public static final int TIMESTAMP_LENGTH = 21;

    /**
     * The fixed length of a FIX timestamp without milliseconds: YYYYMMDD-HH:MM:SS
     */
    public static final int TIMESTAMP_LENGTH_NO_MILLIS = 17;

    // Cached date components for fast encoding
    private static volatile long cachedDayMillis = -1;
    private static volatile int cachedYear;
    private static volatile int cachedMonth;
    private static volatile int cachedDay;

    // Milliseconds per time unit
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND;
    private static final long MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE;
    private static final long MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR;

    private FastTimestampEncoder() {
    }

    /**
     * Encode the current time as a FIX timestamp with milliseconds.
     *
     * <p>Format: YYYYMMDD-HH:MM:SS.sss (21 bytes)</p>
     *
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @return the number of bytes written (always 21)
     * @deprecated Use {@link #encodeNow(Clock, byte[], int)} to allow for testable time sources
     */
    @Deprecated
    public static int encodeNow(byte[] buffer, int offset) {
        return encode(System.currentTimeMillis(), buffer, offset);
    }

    /**
     * Encode the current time as a FIX timestamp with milliseconds using the provided clock.
     *
     * <p>Format: YYYYMMDD-HH:MM:SS.sss (21 bytes)</p>
     *
     * @param clockProvider the clock provider to use for obtaining current time
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @return the number of bytes written (always 21)
     */
    public static int encodeNow(ClockProvider clockProvider, byte[] buffer, int offset) {
        return encode(clockProvider.currentTimeMillis(), buffer, offset);
    }

    /**
     * Encode an epoch milliseconds timestamp as a FIX timestamp with milliseconds.
     *
     * <p>Format: YYYYMMDD-HH:MM:SS.sss (21 bytes)</p>
     *
     * @param epochMillis the epoch milliseconds (UTC)
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @return the number of bytes written (always 21)
     */
    public static int encode(long epochMillis, byte[] buffer, int offset) {
        // Calculate the day boundary
        long dayMillis = (epochMillis / MILLIS_PER_DAY) * MILLIS_PER_DAY;

        // Update cached date if day changed
        if (dayMillis != cachedDayMillis) {
            updateCachedDate(epochMillis);
        }

        // Write date portion: YYYYMMDD-
        int pos = offset;
        pos += encodeYear(cachedYear, buffer, pos);
        pos += encodeMonth(cachedMonth, buffer, pos);
        pos += encodeDay(cachedDay, buffer, pos);
        buffer[pos++] = '-';

        // Calculate time within day
        long millisInDay = epochMillis - dayMillis;
        if (millisInDay < 0) {
            // Handle timezone edge case
            millisInDay += MILLIS_PER_DAY;
        }

        int hours = (int) (millisInDay / MILLIS_PER_HOUR);
        millisInDay %= MILLIS_PER_HOUR;

        int minutes = (int) (millisInDay / MILLIS_PER_MINUTE);
        millisInDay %= MILLIS_PER_MINUTE;

        int seconds = (int) (millisInDay / MILLIS_PER_SECOND);
        int millis = (int) (millisInDay % MILLIS_PER_SECOND);

        // Write time portion: HH:MM:SS.sss
        pos += encodeTwoDigits(hours, buffer, pos);
        buffer[pos++] = ':';
        pos += encodeTwoDigits(minutes, buffer, pos);
        buffer[pos++] = ':';
        pos += encodeTwoDigits(seconds, buffer, pos);
        buffer[pos++] = '.';
        pos += encodeThreeDigits(millis, buffer, pos);

        return TIMESTAMP_LENGTH;
    }

    /**
     * Encode an epoch milliseconds timestamp as a FIX timestamp without milliseconds.
     *
     * <p>Format: YYYYMMDD-HH:MM:SS (17 bytes)</p>
     *
     * @param epochMillis the epoch milliseconds (UTC)
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @return the number of bytes written (always 17)
     */
    public static int encodeNoMillis(long epochMillis, byte[] buffer, int offset) {
        // Calculate the day boundary
        long dayMillis = (epochMillis / MILLIS_PER_DAY) * MILLIS_PER_DAY;

        // Update cached date if day changed
        if (dayMillis != cachedDayMillis) {
            updateCachedDate(epochMillis);
        }

        // Write date portion: YYYYMMDD-
        int pos = offset;
        pos += encodeYear(cachedYear, buffer, pos);
        pos += encodeMonth(cachedMonth, buffer, pos);
        pos += encodeDay(cachedDay, buffer, pos);
        buffer[pos++] = '-';

        // Calculate time within day
        long millisInDay = epochMillis - dayMillis;
        if (millisInDay < 0) {
            millisInDay += MILLIS_PER_DAY;
        }

        int hours = (int) (millisInDay / MILLIS_PER_HOUR);
        millisInDay %= MILLIS_PER_HOUR;

        int minutes = (int) (millisInDay / MILLIS_PER_MINUTE);
        millisInDay %= MILLIS_PER_MINUTE;

        int seconds = (int) (millisInDay / MILLIS_PER_SECOND);

        // Write time portion: HH:MM:SS
        pos += encodeTwoDigits(hours, buffer, pos);
        buffer[pos++] = ':';
        pos += encodeTwoDigits(minutes, buffer, pos);
        buffer[pos++] = ':';
        pos += encodeTwoDigits(seconds, buffer, pos);

        return TIMESTAMP_LENGTH_NO_MILLIS;
    }

    /**
     * Encode an Instant as a FIX timestamp with milliseconds.
     *
     * @param instant the instant to encode
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @return the number of bytes written (always 21)
     */
    public static int encode(Instant instant, byte[] buffer, int offset) {
        return encode(instant.toEpochMilli(), buffer, offset);
    }

    /**
     * Update the cached date components from epoch milliseconds.
     */
    private static synchronized void updateCachedDate(long epochMillis) {
        // Use ZonedDateTime to get correct date components
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC);
        cachedYear = zdt.getYear();
        cachedMonth = zdt.getMonthValue();
        cachedDay = zdt.getDayOfMonth();
        cachedDayMillis = (epochMillis / MILLIS_PER_DAY) * MILLIS_PER_DAY;
    }

    /**
     * Encode a 4-digit year.
     */
    private static int encodeYear(int year, byte[] buffer, int offset) {
        buffer[offset] = (byte) ('0' + year / 1000);
        buffer[offset + 1] = (byte) ('0' + (year / 100) % 10);
        buffer[offset + 2] = (byte) ('0' + (year / 10) % 10);
        buffer[offset + 3] = (byte) ('0' + year % 10);
        return 4;
    }

    /**
     * Encode a 2-digit month (01-12).
     */
    private static int encodeMonth(int month, byte[] buffer, int offset) {
        buffer[offset] = (byte) ('0' + month / 10);
        buffer[offset + 1] = (byte) ('0' + month % 10);
        return 2;
    }

    /**
     * Encode a 2-digit day (01-31).
     */
    private static int encodeDay(int day, byte[] buffer, int offset) {
        buffer[offset] = (byte) ('0' + day / 10);
        buffer[offset + 1] = (byte) ('0' + day % 10);
        return 2;
    }

    /**
     * Encode a 2-digit value (00-99), zero-padded.
     */
    private static int encodeTwoDigits(int value, byte[] buffer, int offset) {
        buffer[offset] = (byte) ('0' + value / 10);
        buffer[offset + 1] = (byte) ('0' + value % 10);
        return 2;
    }

    /**
     * Encode a 3-digit value (000-999), zero-padded.
     */
    private static int encodeThreeDigits(int value, byte[] buffer, int offset) {
        buffer[offset] = (byte) ('0' + value / 100);
        buffer[offset + 1] = (byte) ('0' + (value / 10) % 10);
        buffer[offset + 2] = (byte) ('0' + value % 10);
        return 3;
    }

    /**
     * Reset the cached date (useful for testing).
     */
    static void resetCache() {
        cachedDayMillis = -1;
    }
}
