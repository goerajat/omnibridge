package com.omnibridge.persistence.cli;

import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.chronicle.ChronicleLogStore;
import com.omnibridge.persistence.memory.MemoryMappedLogStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Command-line utility for viewing and managing message logs.
 *
 * <p>This tool provides access to the shared memory persistence files used by the
 * engine for message logging. It supports viewing, searching, filtering, and
 * exporting message logs.</p>
 *
 * <h2>Usage Examples</h2>
 * <pre>
 * # List all available streams/sessions
 * logview list /path/to/logs
 *
 * # Show all messages for a stream
 * logview show /path/to/logs --stream SESSION1
 *
 * # Show only specific message types (FIX: D=NewOrderSingle, 8=ExecutionReport)
 * logview show /path/to/logs --type D
 *
 * # Show messages with human-readable message types
 * logview show /path/to/logs --decode-types
 *
 * # Search for messages containing specific text
 * logview search /path/to/logs --pattern "ClOrdID=12345"
 *
 * # Show statistics
 * logview stats /path/to/logs
 *
 * # Follow new messages in real-time
 * logview tail /path/to/logs --stream SESSION1
 *
 * # Export to CSV
 * logview export /path/to/logs --format csv --output report.csv
 *
 * # List all known FIX message types
 * logview msgtypes
 * </pre>
 */
@Command(name = "logview",
        mixinStandardHelpOptions = true,
        version = "Log Viewer 1.0",
        description = "View and manage message logs from shared memory persistence.",
        subcommands = {
                LogViewer.ListCommand.class,
                LogViewer.ShowCommand.class,
                LogViewer.SearchCommand.class,
                LogViewer.StatsCommand.class,
                LogViewer.TailCommand.class,
                LogViewer.ExportCommand.class,
                LogViewer.MsgTypesCommand.class,
                CommandLine.HelpCommand.class
        })
public class LogViewer implements Callable<Integer> {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new LogViewer())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // If no subcommand specified, show help
        CommandLine.usage(this, System.out);
        return 0;
    }

    /**
     * Extract the MsgType (tag 35) from a raw FIX message.
     * Returns null if not a FIX message or tag 35 not found.
     */
    static String extractMsgType(LogEntry entry) {
        byte[] rawMessage = entry.getRawMessage();
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
     * Auto-detect store type and create the appropriate LogStore.
     * If subdirectories contain .cq4 files, use ChronicleLogStore;
     * otherwise fall back to MemoryMappedLogStore.
     */
    static LogStore openStore(File logDir) {
        File[] subdirs = logDir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File subdir : subdirs) {
                File[] cq4Files = subdir.listFiles((d, name) -> name.endsWith(".cq4"));
                if (cq4Files != null && cq4Files.length > 0) {
                    return new ChronicleLogStore(logDir);
                }
            }
        }
        return openStore(logDir);
    }

    // ==================== Common Options Mixin ====================

    static class CommonOptions {
        @Parameters(index = "0", description = "Path to the log directory")
        File logDir;

        @Option(names = {"-s", "--stream"}, description = "Filter by stream/session name")
        String streamName;

        @Option(names = {"-d", "--direction"}, description = "Filter by direction: in, out, inbound, outbound")
        String direction;

        @Option(names = {"-t", "--type"}, description = "Filter by message type (e.g., D, 8, A for FIX)")
        String msgType;

        @Option(names = {"--from-seq"}, description = "Start sequence number (inclusive)")
        int fromSeqNum;

        @Option(names = {"--to-seq"}, description = "End sequence number (inclusive)")
        int toSeqNum;

        @Option(names = {"--from-time"}, description = "Start time (format: yyyy-MM-dd HH:mm:ss)")
        String fromTime;

        @Option(names = {"--to-time"}, description = "End time (format: yyyy-MM-dd HH:mm:ss)")
        String toTime;

        @Option(names = {"--decode-types"}, description = "Decode message types to human-readable names")
        boolean decodeMsgTypes;

        @Option(names = {"-v", "--verbose"}, description = "Show verbose output including raw message and metadata")
        boolean verbose;

        @Option(names = {"--timezone"}, description = "Timezone for timestamps (default: system)")
        String timezone;

        LogEntry.Direction parseDirection() {
            if (direction == null || direction.isEmpty()) return null;
            return switch (direction.toLowerCase()) {
                case "in", "inbound", "i" -> LogEntry.Direction.INBOUND;
                case "out", "outbound", "o" -> LogEntry.Direction.OUTBOUND;
                default -> null;
            };
        }

        ZoneId getTimeZone() {
            if (timezone != null && !timezone.isEmpty()) {
                try {
                    return ZoneId.of(timezone);
                } catch (Exception e) {
                    System.err.println("Invalid timezone: " + timezone + ", using system default");
                }
            }
            return ZoneId.systemDefault();
        }

        long parseTimestamp(String time) {
            if (time == null || time.isEmpty()) return 0;
            try {
                LocalDateTime ldt = LocalDateTime.parse(time,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return ldt.atZone(getTimeZone()).toInstant().toEpochMilli();
            } catch (Exception e) {
                System.err.println("Warning: Could not parse time: " + time);
                return 0;
            }
        }
    }

    // ==================== List Command ====================

    @Command(name = "list",
            description = "List all available streams in the log directory.",
            mixinStandardHelpOptions = true)
    static class ListCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the log directory")
        File logDir;

        @Option(names = {"-f", "--format"}, description = "Output format: text, json (default: text)")
        String format = "text";

        @Override
        public Integer call() throws Exception {
            if (!logDir.exists() || !logDir.isDirectory()) {
                System.err.println("Error: Log directory does not exist: " + logDir);
                return 1;
            }

            try (LogStore store = openStore(logDir)) {
                Collection<String> streams = store.getStreamNames();

                if (streams.isEmpty()) {
                    System.out.println("No streams found in " + logDir);
                    return 0;
                }

                if ("json".equalsIgnoreCase(format)) {
                    printJsonList(store, streams);
                } else {
                    printTextList(store, streams);
                }
            }
            return 0;
        }

        private void printTextList(LogStore store, Collection<String> streams) {
            System.out.println("Available Streams");
            System.out.println("=================");
            System.out.println();
            System.out.printf("%-30s %10s %15s %15s%n", "Stream Name", "Entries", "Last Inbound", "Last Outbound");
            System.out.println("-".repeat(75));

            for (String stream : streams.stream().sorted().toList()) {
                long count = store.getEntryCount(stream);
                LogEntry latestIn = store.getLatest(stream, LogEntry.Direction.INBOUND);
                LogEntry latestOut = store.getLatest(stream, LogEntry.Direction.OUTBOUND);

                String lastIn = latestIn != null ? "seq=" + latestIn.getSequenceNumber() : "-";
                String lastOut = latestOut != null ? "seq=" + latestOut.getSequenceNumber() : "-";

                System.out.printf("%-30s %10d %15s %15s%n", stream, count, lastIn, lastOut);
            }

            System.out.println();
            System.out.printf("Total: %d streams, %d entries%n", streams.size(), store.getEntryCount(null));
        }

        private void printJsonList(LogStore store, Collection<String> streams) {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"storePath\": \"").append(escapeJson(store.getStorePath())).append("\",\n");
            json.append("  \"totalEntries\": ").append(store.getEntryCount(null)).append(",\n");
            json.append("  \"streams\": [\n");

            List<String> sortedStreams = streams.stream().sorted().toList();
            for (int i = 0; i < sortedStreams.size(); i++) {
                String stream = sortedStreams.get(i);
                long count = store.getEntryCount(stream);
                LogEntry latestIn = store.getLatest(stream, LogEntry.Direction.INBOUND);
                LogEntry latestOut = store.getLatest(stream, LogEntry.Direction.OUTBOUND);

                json.append("    {\n");
                json.append("      \"name\": \"").append(escapeJson(stream)).append("\",\n");
                json.append("      \"entryCount\": ").append(count).append(",\n");
                json.append("      \"lastInboundSeq\": ").append(latestIn != null ? latestIn.getSequenceNumber() : "null").append(",\n");
                json.append("      \"lastOutboundSeq\": ").append(latestOut != null ? latestOut.getSequenceNumber() : "null").append("\n");
                json.append("    }").append(i < sortedStreams.size() - 1 ? "," : "").append("\n");
            }

            json.append("  ]\n");
            json.append("}\n");
            System.out.print(json);
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // ==================== Show Command ====================

    @Command(name = "show",
            description = "Show messages with optional filters.",
            mixinStandardHelpOptions = true)
    static class ShowCommand implements Callable<Integer> {

        @CommandLine.Mixin
        CommonOptions options = new CommonOptions();

        @Option(names = {"-f", "--format"}, description = "Output format: text, json, csv, raw (default: text)")
        String format = "text";

        @Option(names = {"-n", "--count"}, description = "Maximum number of messages to show")
        int maxCount = Integer.MAX_VALUE;

        @Option(names = {"--skip-admin"}, description = "Skip admin/session messages (FIX: Heartbeat, TestRequest, etc.)")
        boolean skipAdmin;

        private final AtomicLong messageCount = new AtomicLong(0);

        @Override
        public Integer call() throws Exception {
            if (!options.logDir.exists() || !options.logDir.isDirectory()) {
                System.err.println("Error: Log directory does not exist: " + options.logDir);
                return 1;
            }

            try (LogStore store = openStore(options.logDir);
                 PrintWriter out = new PrintWriter(System.out, true)) {

                OutputFormatter formatter = createFormatter(out);
                formatter.writeHeader();

                LogEntry.Direction dirFilter = options.parseDirection();
                long fromTimestamp = options.parseTimestamp(options.fromTime);
                long toTimestamp = options.parseTimestamp(options.toTime);

                if (fromTimestamp > 0 || toTimestamp > 0) {
                    store.replayByTime(options.streamName, dirFilter, fromTimestamp, toTimestamp,
                            entry -> processEntry(entry, formatter));
                } else {
                    store.replay(options.streamName, dirFilter, options.fromSeqNum, options.toSeqNum,
                            entry -> processEntry(entry, formatter));
                }

                formatter.writeFooter(messageCount.get());
            }
            return 0;
        }

        private boolean processEntry(LogEntry entry, OutputFormatter formatter) {
            // Apply message type filter
            String msgType = extractMsgType(entry);
            if (options.msgType != null && !options.msgType.isEmpty()) {
                if (msgType == null || !msgType.equals(options.msgType)) {
                    return true;
                }
            }

            // Skip admin messages if requested
            if (skipAdmin && MsgTypeDecoder.isAdminMessage(msgType)) {
                return true;
            }

            if (messageCount.get() >= maxCount) {
                return false;
            }

            messageCount.incrementAndGet();
            formatter.writeEntry(entry);
            return true;
        }

        private OutputFormatter createFormatter(PrintWriter out) {
            ZoneId tz = options.getTimeZone();
            return switch (format.toLowerCase()) {
                case "json" -> OutputFormatter.json(out, options.verbose, options.decodeMsgTypes, tz);
                case "csv" -> OutputFormatter.csv(out, options.verbose, options.decodeMsgTypes, tz);
                case "raw" -> OutputFormatter.raw(out);
                default -> OutputFormatter.text(out, options.verbose, options.decodeMsgTypes, tz);
            };
        }
    }

    // ==================== Search Command ====================

    @Command(name = "search",
            description = "Search for messages containing specific text or patterns.",
            mixinStandardHelpOptions = true)
    static class SearchCommand implements Callable<Integer> {

        @CommandLine.Mixin
        CommonOptions options = new CommonOptions();

        @Option(names = {"-p", "--pattern"}, description = "Search pattern (regex supported)", required = true)
        String pattern;

        @Option(names = {"-i", "--ignore-case"}, description = "Case-insensitive search")
        boolean ignoreCase;

        @Option(names = {"-n", "--count"}, description = "Maximum number of results")
        int maxCount = Integer.MAX_VALUE;

        @Option(names = {"-f", "--format"}, description = "Output format: text, json, csv (default: text)")
        String format = "text";

        private final AtomicLong matchCount = new AtomicLong(0);

        @Override
        public Integer call() throws Exception {
            if (!options.logDir.exists() || !options.logDir.isDirectory()) {
                System.err.println("Error: Log directory does not exist: " + options.logDir);
                return 1;
            }

            Pattern searchPattern;
            try {
                int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
                searchPattern = Pattern.compile(pattern, flags);
            } catch (Exception e) {
                System.err.println("Invalid regex pattern: " + pattern);
                return 1;
            }

            try (LogStore store = openStore(options.logDir);
                 PrintWriter out = new PrintWriter(System.out, true)) {

                OutputFormatter formatter = createFormatter(out);
                formatter.writeHeader();

                LogEntry.Direction dirFilter = options.parseDirection();
                long fromTimestamp = options.parseTimestamp(options.fromTime);
                long toTimestamp = options.parseTimestamp(options.toTime);

                if (fromTimestamp > 0 || toTimestamp > 0) {
                    store.replayByTime(options.streamName, dirFilter, fromTimestamp, toTimestamp,
                            entry -> searchEntry(entry, searchPattern, formatter));
                } else {
                    store.replay(options.streamName, dirFilter, options.fromSeqNum, options.toSeqNum,
                            entry -> searchEntry(entry, searchPattern, formatter));
                }

                formatter.writeFooter(matchCount.get());
            }
            return 0;
        }

        private boolean searchEntry(LogEntry entry, Pattern searchPattern, OutputFormatter formatter) {
            // Apply message type filter
            String msgType = extractMsgType(entry);
            if (options.msgType != null && !options.msgType.isEmpty()) {
                if (msgType == null || !msgType.equals(options.msgType)) {
                    return true;
                }
            }

            if (matchCount.get() >= maxCount) {
                return false;
            }

            // Search in raw message
            String rawMsg = entry.getRawMessageString();
            if (rawMsg != null && searchPattern.matcher(rawMsg).find()) {
                matchCount.incrementAndGet();
                formatter.writeEntry(entry);
                return true;
            }

            // Search in metadata
            String metadata = entry.getMetadataString();
            if (metadata != null && searchPattern.matcher(metadata).find()) {
                matchCount.incrementAndGet();
                formatter.writeEntry(entry);
            }

            return true;
        }

        private OutputFormatter createFormatter(PrintWriter out) {
            ZoneId tz = options.getTimeZone();
            return switch (format.toLowerCase()) {
                case "json" -> OutputFormatter.json(out, options.verbose, options.decodeMsgTypes, tz);
                case "csv" -> OutputFormatter.csv(out, options.verbose, options.decodeMsgTypes, tz);
                default -> OutputFormatter.text(out, options.verbose, options.decodeMsgTypes, tz);
            };
        }
    }

    // ==================== Stats Command ====================

    @Command(name = "stats",
            description = "Show statistics for logs.",
            mixinStandardHelpOptions = true)
    static class StatsCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the log directory")
        File logDir;

        @Option(names = {"-s", "--stream"}, description = "Show stats for specific stream only")
        String streamName;

        @Option(names = {"-f", "--format"}, description = "Output format: text, json (default: text)")
        String format = "text";

        @Option(names = {"--by-type"}, description = "Show breakdown by message type")
        boolean byType;

        @Override
        public Integer call() throws Exception {
            if (!logDir.exists() || !logDir.isDirectory()) {
                System.err.println("Error: Log directory does not exist: " + logDir);
                return 1;
            }

            try (LogStore store = openStore(logDir)) {
                if ("json".equalsIgnoreCase(format)) {
                    printJsonStats(store);
                } else {
                    printTextStats(store);
                }
            }
            return 0;
        }

        private void printTextStats(LogStore store) {
            System.out.println("Log Statistics");
            System.out.println("==============");
            System.out.println();
            System.out.println("Store path: " + store.getStorePath());
            System.out.println("Total entries: " + store.getEntryCount(null));
            System.out.println();

            Collection<String> streams = streamName != null ?
                    List.of(streamName) : store.getStreamNames();

            for (String stream : streams.stream().sorted().toList()) {
                printStreamStats(store, stream);
            }
        }

        private void printStreamStats(LogStore store, String stream) {
            System.out.println("Stream: " + stream);
            System.out.println("-".repeat(50));

            long count = store.getEntryCount(stream);
            System.out.printf("  Total entries: %d%n", count);

            LogEntry latestIn = store.getLatest(stream, LogEntry.Direction.INBOUND);
            LogEntry latestOut = store.getLatest(stream, LogEntry.Direction.OUTBOUND);

            if (latestIn != null) {
                System.out.printf("  Last inbound:  seq=%-6d at %s%n",
                        latestIn.getSequenceNumber(), formatTimestamp(latestIn.getTimestamp()));
            }
            if (latestOut != null) {
                System.out.printf("  Last outbound: seq=%-6d at %s%n",
                        latestOut.getSequenceNumber(), formatTimestamp(latestOut.getTimestamp()));
            }

            if (byType) {
                Map<String, Long> typeCounts = new TreeMap<>();
                Map<String, Long> directionCounts = new HashMap<>();

                store.replay(stream, null, 0, 0, entry -> {
                    String msgType = extractMsgType(entry);
                    msgType = msgType != null ? msgType : "Unknown";
                    typeCounts.merge(msgType, 1L, Long::sum);
                    directionCounts.merge(entry.getDirection().name(), 1L, Long::sum);
                    return true;
                });

                System.out.println("  By direction:");
                directionCounts.forEach((dir, cnt) ->
                        System.out.printf("    %-10s: %d%n", dir, cnt));

                System.out.println("  By message type:");
                typeCounts.forEach((type, cnt) -> {
                    String typeName = MsgTypeDecoder.decode(type);
                    if (!typeName.equals(type)) {
                        System.out.printf("    %-5s (%s): %d%n", type, typeName, cnt);
                    } else {
                        System.out.printf("    %-5s: %d%n", type, cnt);
                    }
                });
            }

            System.out.println();
        }

        private void printJsonStats(LogStore store) {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"storePath\": \"").append(escapeJson(store.getStorePath())).append("\",\n");
            json.append("  \"totalEntries\": ").append(store.getEntryCount(null)).append(",\n");
            json.append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n");
            json.append("  \"streams\": [\n");

            Collection<String> streams = streamName != null ?
                    List.of(streamName) : store.getStreamNames();
            List<String> sortedStreams = streams.stream().sorted().toList();

            for (int i = 0; i < sortedStreams.size(); i++) {
                String stream = sortedStreams.get(i);
                long count = store.getEntryCount(stream);
                LogEntry latestIn = store.getLatest(stream, LogEntry.Direction.INBOUND);
                LogEntry latestOut = store.getLatest(stream, LogEntry.Direction.OUTBOUND);

                json.append("    {\n");
                json.append("      \"name\": \"").append(escapeJson(stream)).append("\",\n");
                json.append("      \"entryCount\": ").append(count).append(",\n");

                if (latestIn != null) {
                    json.append("      \"lastInbound\": {\"seqNum\": ").append(latestIn.getSequenceNumber())
                            .append(", \"timestamp\": \"").append(Instant.ofEpochMilli(latestIn.getTimestamp())).append("\"},\n");
                } else {
                    json.append("      \"lastInbound\": null,\n");
                }

                if (latestOut != null) {
                    json.append("      \"lastOutbound\": {\"seqNum\": ").append(latestOut.getSequenceNumber())
                            .append(", \"timestamp\": \"").append(Instant.ofEpochMilli(latestOut.getTimestamp())).append("\"}\n");
                } else {
                    json.append("      \"lastOutbound\": null\n");
                }

                json.append("    }").append(i < sortedStreams.size() - 1 ? "," : "").append("\n");
            }

            json.append("  ]\n");
            json.append("}\n");
            System.out.print(json);
        }

        private String formatTimestamp(long epochMillis) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    // ==================== Tail Command ====================

    @Command(name = "tail",
            description = "Follow new messages in real-time (like tail -f).",
            mixinStandardHelpOptions = true)
    static class TailCommand implements Callable<Integer> {

        @CommandLine.Mixin
        CommonOptions options = new CommonOptions();

        @Option(names = {"-n", "--lines"}, description = "Number of recent messages to show initially (default: 10)")
        int initialLines = 10;

        @Option(names = {"--skip-admin"}, description = "Skip admin/session messages")
        boolean skipAdmin;

        @Option(names = {"--interval"}, description = "Poll interval in milliseconds (default: 100)")
        int pollInterval = 100;

        @Override
        public Integer call() throws Exception {
            if (!options.logDir.exists() || !options.logDir.isDirectory()) {
                System.err.println("Error: Log directory does not exist: " + options.logDir);
                return 1;
            }

            try (LogStore store = openStore(options.logDir);
                 PrintWriter out = new PrintWriter(System.out, true)) {

                OutputFormatter formatter = OutputFormatter.text(out, options.verbose, options.decodeMsgTypes, options.getTimeZone());

                // Get initial messages (last N)
                List<LogEntry> recentEntries = new ArrayList<>();
                LogEntry.Direction dirFilter = options.parseDirection();

                store.replay(options.streamName, dirFilter, 0, 0, entry -> {
                    String msgType = extractMsgType(entry);
                    if (options.msgType != null && !options.msgType.equals(msgType)) {
                        return true;
                    }
                    if (skipAdmin && MsgTypeDecoder.isAdminMessage(msgType)) {
                        return true;
                    }
                    // Create a copy since we're storing entries for later use
                    LogEntry copy = LogEntry.create(entry.getTimestamp(), entry.getDirection(),
                            entry.getSequenceNumber(), entry.getStreamName(),
                            entry.getMetadata(), entry.getRawMessage());
                    recentEntries.add(copy);
                    return true;
                });

                // Show last N entries
                int startIdx = Math.max(0, recentEntries.size() - initialLines);
                formatter.writeHeader();
                for (int i = startIdx; i < recentEntries.size(); i++) {
                    formatter.writeEntry(recentEntries.get(i));
                }

                System.out.println();
                System.out.println("-- Following new messages (Ctrl+C to exit) --");
                System.out.println();

                // Track seen entries
                Set<String> seenEntries = new HashSet<>();
                for (LogEntry entry : recentEntries) {
                    seenEntries.add(entryKey(entry));
                }

                // Poll for new entries
                while (!Thread.interrupted()) {
                    Thread.sleep(pollInterval);

                    store.replay(options.streamName, dirFilter, 0, 0, entry -> {
                        String key = entryKey(entry);
                        if (seenEntries.contains(key)) {
                            return true;
                        }

                        String msgType = extractMsgType(entry);
                        if (options.msgType != null && !options.msgType.equals(msgType)) {
                            return true;
                        }
                        if (skipAdmin && MsgTypeDecoder.isAdminMessage(msgType)) {
                            return true;
                        }

                        seenEntries.add(key);
                        formatter.writeEntry(entry);
                        return true;
                    });
                }
            } catch (InterruptedException e) {
                // Normal exit via Ctrl+C
            }

            return 0;
        }

        private String entryKey(LogEntry entry) {
            return entry.getStreamName() + ":" + entry.getDirection() + ":" +
                    entry.getSequenceNumber() + ":" + entry.getTimestamp();
        }
    }

    // ==================== Export Command ====================

    @Command(name = "export",
            description = "Export logs to a file.",
            mixinStandardHelpOptions = true)
    static class ExportCommand implements Callable<Integer> {

        @CommandLine.Mixin
        CommonOptions options = new CommonOptions();

        @Option(names = {"-f", "--format"}, description = "Output format: text, json, csv, raw (default: csv)")
        String format = "csv";

        @Option(names = {"-o", "--output"}, description = "Output file path", required = true)
        File outputFile;

        @Option(names = {"--skip-admin"}, description = "Skip admin/session messages")
        boolean skipAdmin;

        @Option(names = {"--append"}, description = "Append to existing file instead of overwriting")
        boolean append;

        private final AtomicLong exportCount = new AtomicLong(0);

        @Override
        public Integer call() throws Exception {
            if (!options.logDir.exists() || !options.logDir.isDirectory()) {
                System.err.println("Error: Log directory does not exist: " + options.logDir);
                return 1;
            }

            try (LogStore store = openStore(options.logDir);
                 PrintWriter out = new PrintWriter(new FileWriter(outputFile, append))) {

                OutputFormatter formatter = createFormatter(out);

                if (!append) {
                    formatter.writeHeader();
                }

                LogEntry.Direction dirFilter = options.parseDirection();
                long fromTimestamp = options.parseTimestamp(options.fromTime);
                long toTimestamp = options.parseTimestamp(options.toTime);

                if (fromTimestamp > 0 || toTimestamp > 0) {
                    store.replayByTime(options.streamName, dirFilter, fromTimestamp, toTimestamp,
                            this::processEntry);
                } else {
                    store.replay(options.streamName, dirFilter, options.fromSeqNum, options.toSeqNum,
                            entry -> {
                                processEntry(entry);
                                formatter.writeEntry(entry);
                                return true;
                            });
                }

                formatter.writeFooter(exportCount.get());

                System.out.printf("Exported %d entries to %s%n", exportCount.get(), outputFile.getAbsolutePath());
            }
            return 0;
        }

        private boolean processEntry(LogEntry entry) {
            String msgType = extractMsgType(entry);
            if (options.msgType != null && !options.msgType.isEmpty()) {
                if (msgType == null || !msgType.equals(options.msgType)) {
                    return true;
                }
            }

            if (skipAdmin && MsgTypeDecoder.isAdminMessage(msgType)) {
                return true;
            }

            exportCount.incrementAndGet();
            return true;
        }

        private OutputFormatter createFormatter(PrintWriter out) {
            ZoneId tz = options.getTimeZone();
            return switch (format.toLowerCase()) {
                case "json" -> OutputFormatter.json(out, options.verbose, options.decodeMsgTypes, tz);
                case "csv" -> OutputFormatter.csv(out, options.verbose, options.decodeMsgTypes, tz);
                case "raw" -> OutputFormatter.raw(out);
                default -> OutputFormatter.text(out, options.verbose, options.decodeMsgTypes, tz);
            };
        }
    }

    // ==================== MsgTypes Command ====================

    @Command(name = "msgtypes",
            description = "List all known FIX message types.",
            mixinStandardHelpOptions = true)
    static class MsgTypesCommand implements Callable<Integer> {

        @Option(names = {"-f", "--format"}, description = "Output format: text, json (default: text)")
        String format = "text";

        @Option(names = {"--admin-only"}, description = "Show only admin/session message types")
        boolean adminOnly;

        @Option(names = {"--app-only"}, description = "Show only application message types")
        boolean appOnly;

        @Override
        public Integer call() {
            Map<String, String> allTypes = MsgTypeDecoder.getAllMessageTypes();

            if ("json".equalsIgnoreCase(format)) {
                printJsonTypes(allTypes);
            } else {
                printTextTypes(allTypes);
            }
            return 0;
        }

        private void printTextTypes(Map<String, String> allTypes) {
            System.out.println("FIX Message Types");
            System.out.println("=================");
            System.out.println();
            System.out.printf("%-8s %-35s %s%n", "Code", "Name", "Category");
            System.out.println("-".repeat(60));

            allTypes.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> {
                        String code = entry.getKey();
                        String name = entry.getValue();
                        boolean isAdmin = MsgTypeDecoder.isAdminMessage(code);

                        if (adminOnly && !isAdmin) return;
                        if (appOnly && isAdmin) return;

                        String category = isAdmin ? "Admin" : "Application";
                        System.out.printf("%-8s %-35s %s%n", code, name, category);
                    });

            System.out.println();
            System.out.printf("Total: %d message types%n", allTypes.size());
        }

        private void printJsonTypes(Map<String, String> allTypes) {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"messageTypes\": [\n");

            List<Map.Entry<String, String>> entries = allTypes.entrySet().stream()
                    .filter(e -> {
                        boolean isAdmin = MsgTypeDecoder.isAdminMessage(e.getKey());
                        if (adminOnly && !isAdmin) return false;
                        if (appOnly && isAdmin) return false;
                        return true;
                    })
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .toList();

            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, String> entry = entries.get(i);
                boolean isAdmin = MsgTypeDecoder.isAdminMessage(entry.getKey());

                json.append("    {\"code\": \"").append(entry.getKey())
                        .append("\", \"name\": \"").append(entry.getValue())
                        .append("\", \"category\": \"").append(isAdmin ? "Admin" : "Application")
                        .append("\"}").append(i < entries.size() - 1 ? "," : "").append("\n");
            }

            json.append("  ],\n");
            json.append("  \"totalCount\": ").append(entries.size()).append("\n");
            json.append("}\n");
            System.out.print(json);
        }
    }
}
