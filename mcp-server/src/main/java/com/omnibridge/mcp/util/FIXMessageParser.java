package com.omnibridge.mcp.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses raw FIX message bytes into tag-value maps.
 * Handles both SOH (0x01) and pipe (|) delimiters.
 */
public final class FIXMessageParser {

    private static final byte SOH = 0x01;
    private static final byte PIPE = '|';
    private static final byte EQUALS = '=';

    private FIXMessageParser() {}

    /**
     * Parse a raw FIX message into a tag-value map.
     *
     * @param rawMessage the raw message bytes
     * @return map of tag number to value string, preserving field order
     */
    public static Map<Integer, String> parse(byte[] rawMessage) {
        if (rawMessage == null || rawMessage.length == 0) {
            return Collections.emptyMap();
        }

        Map<Integer, String> tags = new LinkedHashMap<>();
        byte delimiter = detectDelimiter(rawMessage);
        int i = 0;

        while (i < rawMessage.length) {
            // Find '='
            int eqPos = indexOf(rawMessage, EQUALS, i);
            if (eqPos < 0 || eqPos == i) break;

            // Parse tag number
            int tag;
            try {
                tag = parseIntFast(rawMessage, i, eqPos);
            } catch (NumberFormatException e) {
                // Skip malformed tag
                int nextDelim = indexOf(rawMessage, delimiter, eqPos + 1);
                i = nextDelim < 0 ? rawMessage.length : nextDelim + 1;
                continue;
            }

            // Find delimiter after value
            int valStart = eqPos + 1;
            int valEnd = indexOf(rawMessage, delimiter, valStart);
            if (valEnd < 0) valEnd = rawMessage.length;

            String value = new String(rawMessage, valStart, valEnd - valStart);
            tags.put(tag, value);

            i = valEnd + 1;
        }

        return tags;
    }

    /**
     * Extract a single tag value from raw FIX message bytes.
     * More efficient than full parse when only one tag is needed.
     *
     * @param rawMessage the raw message bytes
     * @param tag the FIX tag number to extract
     * @return the tag value, or null if not found
     */
    public static String extractTag(byte[] rawMessage, int tag) {
        if (rawMessage == null || rawMessage.length == 0) return null;

        byte delimiter = detectDelimiter(rawMessage);
        String tagPrefix = tag + "=";
        byte[] prefix = tagPrefix.getBytes();

        for (int i = 0; i < rawMessage.length; i++) {
            // Match must be at start or after delimiter
            if (i > 0 && rawMessage[i - 1] != delimiter) continue;

            // Check prefix match
            if (i + prefix.length >= rawMessage.length) continue;
            boolean match = true;
            for (int j = 0; j < prefix.length; j++) {
                if (rawMessage[i + j] != prefix[j]) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;

            // Extract value
            int valStart = i + prefix.length;
            int valEnd = indexOf(rawMessage, delimiter, valStart);
            if (valEnd < 0) valEnd = rawMessage.length;
            return new String(rawMessage, valStart, valEnd - valStart);
        }
        return null;
    }

    /**
     * Extract MsgType (tag 35) from raw FIX message.
     */
    public static String extractMsgType(byte[] rawMessage) {
        return extractTag(rawMessage, 35);
    }

    /**
     * Extract SenderCompID (tag 49) from raw FIX message.
     */
    public static String extractSenderCompId(byte[] rawMessage) {
        return extractTag(rawMessage, 49);
    }

    /**
     * Extract TargetCompID (tag 56) from raw FIX message.
     */
    public static String extractTargetCompId(byte[] rawMessage) {
        return extractTag(rawMessage, 56);
    }

    private static byte detectDelimiter(byte[] msg) {
        for (byte b : msg) {
            if (b == SOH) return SOH;
            if (b == PIPE) return PIPE;
        }
        return SOH;
    }

    private static int indexOf(byte[] arr, byte target, int from) {
        for (int i = from; i < arr.length; i++) {
            if (arr[i] == target) return i;
        }
        return -1;
    }

    private static int parseIntFast(byte[] arr, int from, int to) {
        int result = 0;
        for (int i = from; i < to; i++) {
            byte b = arr[i];
            if (b < '0' || b > '9') throw new NumberFormatException();
            result = result * 10 + (b - '0');
        }
        return result;
    }
}
