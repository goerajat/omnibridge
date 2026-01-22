package com.fixengine.persistence.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fixengine.persistence.Decoder;
import com.fixengine.persistence.LogEntry;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Formats log entries for output.
 */
public abstract class OutputFormatter {

    protected final PrintWriter out;
    protected final boolean verbose;
    protected final boolean decodeMsgTypes;
    protected final ZoneId timeZone;
    protected final DateTimeFormatter dateFormatter;

    protected OutputFormatter(PrintWriter out, boolean verbose, boolean decodeMsgTypes, ZoneId timeZone) {
        this.out = out;
        this.verbose = verbose;
        this.decodeMsgTypes = decodeMsgTypes;
        this.timeZone = timeZone;
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(timeZone);
    }

    /**
     * Extract the MsgType (tag 35) from a raw FIX message.
     * Returns null if not a FIX message or tag 35 not found.
     */
    protected static String extractMsgType(byte[] rawMessage) {
        if (rawMessage == null || rawMessage.length == 0) return null;
        String msg = new String(rawMessage);
        int start = msg.indexOf("35=");
        if (start < 0) return null;
        start += 3; // Skip "35="
        int end = msg.indexOf('\u0001', start);
        if (end < 0) end = msg.length();
        return msg.substring(start, end);
    }

    /**
     * Get the message type from a log entry.
     * Extracts from raw message for FIX protocol.
     */
    protected String getMsgType(LogEntry entry) {
        return extractMsgType(entry.getRawMessage());
    }

    /**
     * Write the header (if any).
     */
    public abstract void writeHeader();

    /**
     * Write a single log entry.
     */
    public abstract void writeEntry(LogEntry entry);

    /**
     * Write the footer (if any).
     */
    public abstract void writeFooter(long totalEntries);

    /**
     * Format the message type.
     */
    protected String formatMsgType(String msgType) {
        if (decodeMsgTypes) {
            return MsgTypeDecoder.decodeWithCode(msgType);
        }
        return msgType;
    }

    /**
     * Format the timestamp.
     */
    protected String formatTimestamp(long timestamp) {
        return dateFormatter.format(Instant.ofEpochMilli(timestamp));
    }

    // ==================== Factory Methods ====================

    public static OutputFormatter text(PrintWriter out, boolean verbose, boolean decodeMsgTypes, ZoneId timeZone) {
        return new TextFormatter(out, verbose, decodeMsgTypes, timeZone);
    }

    public static OutputFormatter json(PrintWriter out, boolean verbose, boolean decodeMsgTypes, ZoneId timeZone) {
        return new JsonFormatter(out, verbose, decodeMsgTypes, timeZone);
    }

    public static OutputFormatter csv(PrintWriter out, boolean verbose, boolean decodeMsgTypes, ZoneId timeZone) {
        return new CsvFormatter(out, verbose, decodeMsgTypes, timeZone);
    }

    public static OutputFormatter raw(PrintWriter out) {
        return new RawFormatter(out);
    }

    // ==================== Text Formatter ====================

    private static class TextFormatter extends OutputFormatter {
        private long count = 0;

        TextFormatter(PrintWriter out, boolean verbose, boolean decodeMsgTypes, ZoneId timeZone) {
            super(out, verbose, decodeMsgTypes, timeZone);
        }

        @Override
        public void writeHeader() {
            out.println("=".repeat(100));
            out.println("FIX Log Viewer");
            out.println("=".repeat(100));
            out.println();
        }

        @Override
        public void writeEntry(LogEntry entry) {
            count++;
            String direction = entry.getDirection() == LogEntry.Direction.INBOUND ? "IN " : "OUT";
            String msgType = formatMsgType(getMsgType(entry));
            String timestamp = formatTimestamp(entry.getTimestamp());

            out.printf("[%s] %s | SeqNum: %-6d | Type: %-25s | Stream: %s%n",
                    timestamp, direction, entry.getSequenceNumber(), msgType, entry.getStreamName());

            if (verbose) {
                String metadata = entry.getMetadataString();
                if (metadata != null && !metadata.isEmpty()) {
                    out.printf("         Metadata: %s%n", metadata);
                }
                if (entry.getRawMessage() != null && entry.getRawMessage().length > 0) {
                    out.printf("         Message: %s%n", entry.getRawMessageString());
                }
                out.println();
            }
        }

        @Override
        public void writeFooter(long totalEntries) {
            out.println();
            out.println("-".repeat(100));
            out.printf("Total entries: %d%n", totalEntries);
        }
    }

    // ==================== JSON Formatter ====================

    private static class JsonFormatter extends OutputFormatter {
        private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        private final ArrayNode entries;

        JsonFormatter(PrintWriter out, boolean verbose, boolean decodeMsgTypes, ZoneId timeZone) {
            super(out, verbose, decodeMsgTypes, timeZone);
            this.entries = mapper.createArrayNode();
        }

        @Override
        public void writeHeader() {
            // JSON is written all at once in footer
        }

        @Override
        public void writeEntry(LogEntry entry) {
            ObjectNode node = entries.addObject();
            node.put("timestamp", entry.getTimestamp());
            node.put("timestampFormatted", formatTimestamp(entry.getTimestamp()));
            node.put("seqNum", entry.getSequenceNumber());
            node.put("direction", entry.getDirection().name());
            String msgType = getMsgType(entry);
            node.put("msgType", msgType);
            if (decodeMsgTypes) {
                node.put("msgTypeName", MsgTypeDecoder.decode(msgType));
            }
            node.put("streamName", entry.getStreamName());

            if (verbose) {
                String metadata = entry.getMetadataString();
                if (metadata != null) {
                    node.put("metadata", metadata);
                }
                if (entry.getRawMessage() != null) {
                    node.put("rawMessage", entry.getRawMessageString());
                }
            }
        }

        @Override
        public void writeFooter(long totalEntries) {
            try {
                ObjectNode root = mapper.createObjectNode();
                root.put("totalEntries", totalEntries);
                root.put("generatedAt", Instant.now().toString());
                root.set("entries", entries);
                out.println(mapper.writeValueAsString(root));
            } catch (Exception e) {
                out.println("{\"error\":\"" + e.getMessage() + "\"}");
            }
        }
    }

    // ==================== CSV Formatter ====================

    private static class CsvFormatter extends OutputFormatter {
        CsvFormatter(PrintWriter out, boolean verbose, boolean decodeMsgTypes, ZoneId timeZone) {
            super(out, verbose, decodeMsgTypes, timeZone);
        }

        @Override
        public void writeHeader() {
            if (verbose) {
                out.println("Timestamp,SeqNum,Direction,MsgType,MsgTypeName,StreamName,Metadata,RawMessage");
            } else {
                out.println("Timestamp,SeqNum,Direction,MsgType,MsgTypeName,StreamName");
            }
        }

        @Override
        public void writeEntry(LogEntry entry) {
            String timestamp = formatTimestamp(entry.getTimestamp());
            String msgType = getMsgType(entry);
            String msgTypeName = decodeMsgTypes ? MsgTypeDecoder.decode(msgType) : "";

            if (verbose) {
                out.printf("%s,%d,%s,%s,%s,%s,%s,%s%n",
                        timestamp,
                        entry.getSequenceNumber(),
                        entry.getDirection(),
                        msgType,
                        csvEscape(msgTypeName),
                        csvEscape(entry.getStreamName()),
                        csvEscape(entry.getMetadataString()),
                        csvEscape(entry.getRawMessageString()));
            } else {
                out.printf("%s,%d,%s,%s,%s,%s%n",
                        timestamp,
                        entry.getSequenceNumber(),
                        entry.getDirection(),
                        msgType,
                        csvEscape(msgTypeName),
                        csvEscape(entry.getStreamName()));
            }
        }

        @Override
        public void writeFooter(long totalEntries) {
            // No footer for CSV
        }

        private String csvEscape(String value) {
            if (value == null) return "";
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }
    }

    // ==================== Raw Formatter ====================

    private static class RawFormatter extends OutputFormatter {
        RawFormatter(PrintWriter out) {
            super(out, false, false, ZoneId.systemDefault());
        }

        @Override
        public void writeHeader() {
            // No header for raw output
        }

        @Override
        public void writeEntry(LogEntry entry) {
            if (entry.getRawMessage() != null && entry.getRawMessage().length > 0) {
                out.println(entry.getRawMessageString());
            }
        }

        @Override
        public void writeFooter(long totalEntries) {
            // No footer for raw output
        }
    }
}
