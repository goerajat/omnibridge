package com.fixengine.persistence.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fixengine.persistence.FixLogEntry;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Formats FIX log entries for output.
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
     * Write the header (if any).
     */
    public abstract void writeHeader();

    /**
     * Write a single log entry.
     */
    public abstract void writeEntry(FixLogEntry entry);

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
        public void writeEntry(FixLogEntry entry) {
            count++;
            String direction = entry.getDirection() == FixLogEntry.Direction.INBOUND ? "IN " : "OUT";
            String msgType = formatMsgType(entry.getMsgType());
            String timestamp = formatTimestamp(entry.getTimestamp());

            out.printf("[%s] %s | SeqNum: %-6d | Type: %-25s | Stream: %s%n",
                    timestamp, direction, entry.getSeqNum(), msgType, entry.getStreamName());

            if (verbose) {
                if (entry.getTransactionId() != 0) {
                    out.printf("         TxnId: %d%n", entry.getTransactionId());
                }
                if (entry.getMetadata() != null && !entry.getMetadata().isEmpty()) {
                    out.printf("         Metadata: %s%n", entry.getMetadata());
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
        public void writeEntry(FixLogEntry entry) {
            ObjectNode node = entries.addObject();
            node.put("timestamp", entry.getTimestamp());
            node.put("timestampFormatted", formatTimestamp(entry.getTimestamp()));
            node.put("seqNum", entry.getSeqNum());
            node.put("direction", entry.getDirection().name());
            node.put("msgType", entry.getMsgType());
            if (decodeMsgTypes) {
                node.put("msgTypeName", MsgTypeDecoder.decode(entry.getMsgType()));
            }
            node.put("streamName", entry.getStreamName());

            if (verbose) {
                node.put("transactionId", entry.getTransactionId());
                if (entry.getMetadata() != null) {
                    node.put("metadata", entry.getMetadata());
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
                out.println("Timestamp,SeqNum,Direction,MsgType,MsgTypeName,StreamName,TransactionId,Metadata,RawMessage");
            } else {
                out.println("Timestamp,SeqNum,Direction,MsgType,MsgTypeName,StreamName");
            }
        }

        @Override
        public void writeEntry(FixLogEntry entry) {
            String timestamp = formatTimestamp(entry.getTimestamp());
            String msgTypeName = decodeMsgTypes ? MsgTypeDecoder.decode(entry.getMsgType()) : "";

            if (verbose) {
                out.printf("%s,%d,%s,%s,%s,%s,%d,%s,%s%n",
                        timestamp,
                        entry.getSeqNum(),
                        entry.getDirection(),
                        entry.getMsgType(),
                        csvEscape(msgTypeName),
                        csvEscape(entry.getStreamName()),
                        entry.getTransactionId(),
                        csvEscape(entry.getMetadata()),
                        csvEscape(entry.getRawMessageString()));
            } else {
                out.printf("%s,%d,%s,%s,%s,%s%n",
                        timestamp,
                        entry.getSeqNum(),
                        entry.getDirection(),
                        entry.getMsgType(),
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
        public void writeEntry(FixLogEntry entry) {
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
