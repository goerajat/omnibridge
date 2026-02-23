package com.omnibridge.persistence.aeron;

import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.chronicle.ChronicleLogStore;
import com.omnibridge.persistence.config.PersistenceConfig;
import picocli.CommandLine;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * CLI tool that validates a local Chronicle store matches a remote Chronicle store.
 *
 * <p>Performs the following checks:
 * <ol>
 *   <li>Stream names match between local and remote</li>
 *   <li>Entry counts match per-stream and total</li>
 *   <li>Entry-by-entry comparison: timestamp, direction, sequenceNumber, rawMessage, metadata</li>
 *   <li>Direction coverage: both INBOUND and OUTBOUND exist</li>
 *   <li>FIX-specific (optional): checks for expected message types (35=A, 35=0, 35=5, 35=D, 35=8)</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 * java -cp ... com.omnibridge.persistence.aeron.StoreValidator \
 *     --local ./data/local-cache --remote ./data/remote-store [--fix-validate] [--verbose]
 * </pre>
 *
 * <p>Exit code: 0 = pass, 1 = fail.</p>
 */
@CommandLine.Command(
        name = "store-validator",
        description = "Validates local and remote Chronicle stores match",
        mixinStandardHelpOptions = true
)
public class StoreValidator implements Callable<Integer> {

    @CommandLine.Option(names = "--local", description = "Path to local Chronicle store", required = true)
    private String localPath;

    @CommandLine.Option(names = "--remote", description = "Path to remote Chronicle store", required = true)
    private String remotePath;

    @CommandLine.Option(names = "--fix-validate", description = "Enable FIX-specific message type validation")
    private boolean fixValidate;

    @CommandLine.Option(names = "--verbose", description = "Print detailed comparison output")
    private boolean verbose;

    private int failures = 0;
    private int checks = 0;

    @Override
    public Integer call() {
        System.out.println("==========================================================================");
        System.out.println("Store Validation Report");
        System.out.println("==========================================================================");
        System.out.println("Local store:  " + localPath);
        System.out.println("Remote store: " + remotePath);
        System.out.println("FIX validate: " + fixValidate);
        System.out.println("==========================================================================");
        System.out.println();

        PersistenceConfig localConfig = PersistenceConfig.builder()
                .basePath(localPath)
                .storeType(PersistenceConfig.StoreType.CHRONICLE)
                .build();
        PersistenceConfig remoteConfig = PersistenceConfig.builder()
                .basePath(remotePath)
                .storeType(PersistenceConfig.StoreType.CHRONICLE)
                .build();

        try (ChronicleLogStore local = new ChronicleLogStore(localConfig);
             ChronicleLogStore remote = new ChronicleLogStore(remoteConfig)) {

            local.initialize();
            local.startActive();
            remote.initialize();
            remote.startActive();

            validateStreamNames(local, remote);
            validateEntryCounts(local, remote);
            validateEntryByEntry(local, remote);
            validateDirectionCoverage(remote);

            if (fixValidate) {
                validateFixMessageTypes(remote);
            }

        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }

        System.out.println();
        System.out.println("==========================================================================");
        System.out.printf("Result: %d checks, %d failures%n", checks, failures);
        if (failures == 0) {
            System.out.println("VALIDATION PASSED");
        } else {
            System.out.println("VALIDATION FAILED");
        }
        System.out.println("==========================================================================");

        return failures == 0 ? 0 : 1;
    }

    private void validateStreamNames(ChronicleLogStore local, ChronicleLogStore remote) {
        System.out.println("--- Stream Names ---");
        Collection<String> localStreams = local.getStreamNames();
        Collection<String> remoteStreams = remote.getStreamNames();

        check("Stream count", localStreams.size(), remoteStreams.size());

        Set<String> localSet = new TreeSet<>(localStreams);
        Set<String> remoteSet = new TreeSet<>(remoteStreams);

        Set<String> onlyLocal = new TreeSet<>(localSet);
        onlyLocal.removeAll(remoteSet);
        Set<String> onlyRemote = new TreeSet<>(remoteSet);
        onlyRemote.removeAll(localSet);

        if (!onlyLocal.isEmpty()) {
            fail("Streams only in local: " + onlyLocal);
        }
        if (!onlyRemote.isEmpty()) {
            fail("Streams only in remote: " + onlyRemote);
        }
        if (onlyLocal.isEmpty() && onlyRemote.isEmpty()) {
            pass("All streams present in both stores: " + localSet);
        }
        System.out.println();
    }

    private void validateEntryCounts(ChronicleLogStore local, ChronicleLogStore remote) {
        System.out.println("--- Entry Counts ---");
        long totalLocal = 0;
        long totalRemote = 0;

        Set<String> allStreams = new TreeSet<>(local.getStreamNames());
        allStreams.addAll(remote.getStreamNames());

        for (String stream : allStreams) {
            long lc = local.getEntryCount(stream);
            long rc = remote.getEntryCount(stream);
            totalLocal += lc;
            totalRemote += rc;
            check("Entry count [" + stream + "]", lc, rc);
        }

        check("Total entry count", totalLocal, totalRemote);
        System.out.println();
    }

    private void validateEntryByEntry(ChronicleLogStore local, ChronicleLogStore remote) {
        System.out.println("--- Entry-by-Entry Comparison ---");

        Set<String> allStreams = new TreeSet<>(local.getStreamNames());
        allStreams.retainAll(remote.getStreamNames());

        int totalCompared = 0;
        int totalMismatches = 0;

        for (String stream : allStreams) {
            List<LogEntry> localEntries = replayAll(local, stream);
            List<LogEntry> remoteEntries = replayAll(remote, stream);

            int limit = Math.min(localEntries.size(), remoteEntries.size());
            int streamMismatches = 0;

            for (int i = 0; i < limit; i++) {
                LogEntry le = localEntries.get(i);
                LogEntry re = remoteEntries.get(i);

                boolean match = le.getTimestamp() == re.getTimestamp()
                        && le.getDirection() == re.getDirection()
                        && le.getSequenceNumber() == re.getSequenceNumber()
                        && Arrays.equals(le.getRawMessage(), re.getRawMessage())
                        && Arrays.equals(le.getMetadata(), re.getMetadata());

                if (!match) {
                    streamMismatches++;
                    if (verbose) {
                        System.out.printf("  MISMATCH [%s] entry %d:%n", stream, i);
                        if (le.getTimestamp() != re.getTimestamp())
                            System.out.printf("    timestamp: local=%d remote=%d%n", le.getTimestamp(), re.getTimestamp());
                        if (le.getDirection() != re.getDirection())
                            System.out.printf("    direction: local=%s remote=%s%n", le.getDirection(), re.getDirection());
                        if (le.getSequenceNumber() != re.getSequenceNumber())
                            System.out.printf("    seqNum: local=%d remote=%d%n", le.getSequenceNumber(), re.getSequenceNumber());
                        if (!Arrays.equals(le.getRawMessage(), re.getRawMessage()))
                            System.out.printf("    rawMessage: local=%d bytes remote=%d bytes%n",
                                    le.getRawMessage() != null ? le.getRawMessage().length : 0,
                                    re.getRawMessage() != null ? re.getRawMessage().length : 0);
                        if (!Arrays.equals(le.getMetadata(), re.getMetadata()))
                            System.out.printf("    metadata: local=%d bytes remote=%d bytes%n",
                                    le.getMetadata() != null ? le.getMetadata().length : 0,
                                    re.getMetadata() != null ? re.getMetadata().length : 0);
                    }
                }
                totalCompared++;
            }
            totalMismatches += streamMismatches;

            if (streamMismatches == 0) {
                pass("Stream [" + stream + "]: " + limit + " entries match");
            } else {
                fail("Stream [" + stream + "]: " + streamMismatches + " / " + limit + " mismatches");
            }
        }

        checks++;
        if (totalMismatches == 0) {
            System.out.println("  PASS: All " + totalCompared + " entries match exactly");
        } else {
            failures++;
            System.out.println("  FAIL: " + totalMismatches + " / " + totalCompared + " entries differ");
        }
        System.out.println();
    }

    private void validateDirectionCoverage(ChronicleLogStore store) {
        System.out.println("--- Direction Coverage ---");
        boolean hasInbound = false;
        boolean hasOutbound = false;

        for (String stream : store.getStreamNames()) {
            long[] inCount = {0};
            long[] outCount = {0};
            store.replay(stream, null, 0, 0, entry -> {
                if (entry.getDirection() == LogEntry.Direction.INBOUND) inCount[0]++;
                else if (entry.getDirection() == LogEntry.Direction.OUTBOUND) outCount[0]++;
                return true;
            });
            if (inCount[0] > 0) hasInbound = true;
            if (outCount[0] > 0) hasOutbound = true;

            if (verbose) {
                System.out.printf("  Stream [%s]: INBOUND=%d, OUTBOUND=%d%n", stream, inCount[0], outCount[0]);
            }
        }

        checks++;
        if (hasInbound && hasOutbound) {
            System.out.println("  PASS: Both INBOUND and OUTBOUND directions present");
        } else if (!hasInbound && !hasOutbound) {
            System.out.println("  WARN: No entries found (empty store)");
        } else {
            System.out.println("  WARN: Only " + (hasInbound ? "INBOUND" : "OUTBOUND") + " direction present");
        }
        System.out.println();
    }

    private void validateFixMessageTypes(ChronicleLogStore store) {
        System.out.println("--- FIX Message Type Validation ---");

        Set<String> messageTypes = new TreeSet<>();

        for (String stream : store.getStreamNames()) {
            store.replay(stream, null, 0, 0, entry -> {
                byte[] msg = entry.getRawMessage();
                if (msg != null) {
                    String msgStr = new String(msg);
                    // Extract 35= tag value
                    int idx = msgStr.indexOf("35=");
                    if (idx >= 0) {
                        int end = msgStr.indexOf('\u0001', idx);
                        if (end < 0) end = msgStr.length();
                        messageTypes.add(msgStr.substring(idx + 3, end));
                    }
                }
                return true;
            });
        }

        System.out.println("  Found message types (35=): " + messageTypes);

        // Check for expected FIX message types
        String[][] expectedTypes = {
                {"A", "Logon"},
                {"0", "Heartbeat"},
                {"5", "Logout"},
                {"D", "NewOrderSingle"},
                {"8", "ExecutionReport"}
        };

        for (String[] type : expectedTypes) {
            checks++;
            if (messageTypes.contains(type[0])) {
                System.out.printf("  PASS: Found 35=%s (%s)%n", type[0], type[1]);
            } else {
                System.out.printf("  WARN: Missing 35=%s (%s)%n", type[0], type[1]);
                // Not a hard failure - some test suites may not generate all types
            }
        }
        System.out.println();
    }

    private void check(String name, long expected, long actual) {
        checks++;
        if (expected == actual) {
            pass(name + ": " + actual);
        } else {
            fail(name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private void pass(String message) {
        System.out.println("  PASS: " + message);
    }

    private void fail(String message) {
        failures++;
        System.out.println("  FAIL: " + message);
    }

    private List<LogEntry> replayAll(ChronicleLogStore store, String stream) {
        List<LogEntry> entries = new ArrayList<>();
        store.replay(stream, null, 0, 0, entry -> {
            entries.add(LogEntry.create(
                    entry.getTimestamp(),
                    entry.getDirection(),
                    entry.getSequenceNumber(),
                    entry.getStreamName(),
                    entry.getMetadata(),
                    entry.getRawMessage()
            ));
            return true;
        });
        return entries;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new StoreValidator()).execute(args);
        System.exit(exitCode);
    }
}
