package com.omnibridge.fix.message;

/**
 * Zero-allocation integer encoder for FIX protocol messages.
 *
 * <p>This class provides static methods for encoding integers directly into byte
 * arrays without creating intermediate String or StringBuilder objects. All methods
 * are designed for maximum performance in latency-sensitive scenarios.</p>
 *
 * <p>Supports both variable-width encoding (minimum digits needed) and fixed-width
 * encoding (with zero-padding for a specific number of digits).</p>
 */
public final class FastIntEncoder {

    // Pre-computed digit pairs for fast two-digit encoding
    private static final byte[] DIGIT_TENS = new byte[100];
    private static final byte[] DIGIT_ONES = new byte[100];

    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_TENS[i] = (byte) ('0' + i / 10);
            DIGIT_ONES[i] = (byte) ('0' + i % 10);
        }
    }

    private FastIntEncoder() {
    }

    /**
     * Encode an integer with fixed width, zero-padded.
     *
     * <p>Example: encodeFixedWidth(42, buffer, 0, 8, '0') produces "00000042"</p>
     *
     * @param value the integer value to encode
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @param width the fixed width (number of digits)
     * @param padChar the padding character (typically '0' or ' ')
     * @return the number of bytes written (always equals width)
     * @throws IllegalArgumentException if value doesn't fit in specified width
     */
    public static int encodeFixedWidth(int value, byte[] buffer, int offset, int width, char padChar) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative values not supported for fixed-width encoding: " + value);
        }

        // Check if value fits in width
        int maxValue = getMaxValue(width);
        if (value > maxValue) {
            throw new IllegalArgumentException("Value " + value + " exceeds maximum for " + width + " digits (" + maxValue + ")");
        }

        // Fill with padding first
        byte pad = (byte) padChar;
        for (int i = 0; i < width; i++) {
            buffer[offset + i] = pad;
        }

        // Write digits from right to left
        int pos = offset + width - 1;
        if (value == 0) {
            buffer[pos] = '0';
        } else {
            while (value > 0 && pos >= offset) {
                buffer[pos--] = (byte) ('0' + value % 10);
                value /= 10;
            }
        }

        return width;
    }

    /**
     * Encode a long with fixed width, zero-padded.
     *
     * @param value the long value to encode
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @param width the fixed width (number of digits)
     * @param padChar the padding character (typically '0' or ' ')
     * @return the number of bytes written (always equals width)
     * @throws IllegalArgumentException if value doesn't fit in specified width
     */
    public static int encodeFixedWidth(long value, byte[] buffer, int offset, int width, char padChar) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative values not supported for fixed-width encoding: " + value);
        }

        // Fill with padding first
        byte pad = (byte) padChar;
        for (int i = 0; i < width; i++) {
            buffer[offset + i] = pad;
        }

        // Write digits from right to left
        int pos = offset + width - 1;
        if (value == 0) {
            buffer[pos] = '0';
        } else {
            while (value > 0 && pos >= offset) {
                buffer[pos--] = (byte) ('0' + value % 10);
                value /= 10;
            }
        }

        // Check for overflow (digits remaining after filling all positions)
        if (value > 0) {
            throw new IllegalArgumentException("Value exceeds maximum for " + width + " digits");
        }

        return width;
    }

    /**
     * Encode an integer using variable width (minimum digits needed).
     *
     * <p>Example: encode(42, buffer, 0) produces "42" (2 bytes)</p>
     *
     * @param value the integer value to encode (can be negative)
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @return the number of bytes written
     */
    public static int encode(int value, byte[] buffer, int offset) {
        int pos = offset;

        if (value < 0) {
            buffer[pos++] = '-';
            value = -value;
        }

        if (value == 0) {
            buffer[pos++] = '0';
            return pos - offset;
        }

        // Find number of digits
        int digits = digitCount(value);
        int endPos = pos + digits;

        // Write digits from right to left
        int writePos = endPos - 1;
        while (value > 0) {
            buffer[writePos--] = (byte) ('0' + value % 10);
            value /= 10;
        }

        return endPos - offset;
    }

    /**
     * Encode a long using variable width (minimum digits needed).
     *
     * @param value the long value to encode (can be negative)
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @return the number of bytes written
     */
    public static int encode(long value, byte[] buffer, int offset) {
        int pos = offset;

        if (value < 0) {
            buffer[pos++] = '-';
            value = -value;
        }

        if (value == 0) {
            buffer[pos++] = '0';
            return pos - offset;
        }

        // Find number of digits
        int digits = digitCount(value);
        int endPos = pos + digits;

        // Write digits from right to left
        int writePos = endPos - 1;
        while (value > 0) {
            buffer[writePos--] = (byte) ('0' + value % 10);
            value /= 10;
        }

        return endPos - offset;
    }

    /**
     * Encode a double value with specified decimal places.
     *
     * <p>Example: encode(123.456, buffer, 0, 2) produces "123.46" (rounded)</p>
     *
     * @param value the double value to encode
     * @param buffer the destination byte array
     * @param offset the position in buffer to start writing
     * @param decimalPlaces number of digits after decimal point
     * @return the number of bytes written
     */
    public static int encode(double value, byte[] buffer, int offset, int decimalPlaces) {
        int pos = offset;

        if (value < 0) {
            buffer[pos++] = '-';
            value = -value;
        }

        // Scale to get the decimal places
        long multiplier = 1;
        for (int i = 0; i < decimalPlaces; i++) {
            multiplier *= 10;
        }

        // Round and convert to long
        long scaled = Math.round(value * multiplier);

        // Extract integer and fractional parts
        long intPart = scaled / multiplier;
        long fracPart = scaled % multiplier;

        // Write integer part
        if (intPart == 0) {
            buffer[pos++] = '0';
        } else {
            int intDigits = digitCount(intPart);
            int intEnd = pos + intDigits;
            int writePos = intEnd - 1;
            while (intPart > 0) {
                buffer[writePos--] = (byte) ('0' + intPart % 10);
                intPart /= 10;
            }
            pos = intEnd;
        }

        // Write decimal point and fractional part
        if (decimalPlaces > 0) {
            buffer[pos++] = '.';
            // Write fractional part with leading zeros
            pos += encodeFixedWidth(fracPart, buffer, pos, decimalPlaces, '0');
        }

        return pos - offset;
    }

    /**
     * Count the number of decimal digits needed to represent a positive integer.
     *
     * @param value the positive integer value
     * @return the number of digits
     */
    public static int digitCount(int value) {
        if (value == 0) return 1;
        if (value < 0) value = -value;

        if (value < 10) return 1;
        if (value < 100) return 2;
        if (value < 1000) return 3;
        if (value < 10000) return 4;
        if (value < 100000) return 5;
        if (value < 1000000) return 6;
        if (value < 10000000) return 7;
        if (value < 100000000) return 8;
        if (value < 1000000000) return 9;
        return 10;
    }

    /**
     * Count the number of decimal digits needed to represent a positive long.
     *
     * @param value the positive long value
     * @return the number of digits
     */
    public static int digitCount(long value) {
        if (value == 0) return 1;
        if (value < 0) value = -value;

        if (value < 10L) return 1;
        if (value < 100L) return 2;
        if (value < 1000L) return 3;
        if (value < 10000L) return 4;
        if (value < 100000L) return 5;
        if (value < 1000000L) return 6;
        if (value < 10000000L) return 7;
        if (value < 100000000L) return 8;
        if (value < 1000000000L) return 9;
        if (value < 10000000000L) return 10;
        if (value < 100000000000L) return 11;
        if (value < 1000000000000L) return 12;
        if (value < 10000000000000L) return 13;
        if (value < 100000000000000L) return 14;
        if (value < 1000000000000000L) return 15;
        if (value < 10000000000000000L) return 16;
        if (value < 100000000000000000L) return 17;
        if (value < 1000000000000000000L) return 18;
        return 19;
    }

    /**
     * Get the maximum value that fits in the specified number of digits.
     *
     * @param digits the number of digits
     * @return the maximum value
     */
    private static int getMaxValue(int digits) {
        return switch (digits) {
            case 1 -> 9;
            case 2 -> 99;
            case 3 -> 999;
            case 4 -> 9999;
            case 5 -> 99999;
            case 6 -> 999999;
            case 7 -> 9999999;
            case 8 -> 99999999;
            case 9 -> 999999999;
            default -> Integer.MAX_VALUE;
        };
    }
}
