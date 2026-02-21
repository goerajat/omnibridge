package com.omnibridge.fix.tester;

import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.config.EngineConfig;
import com.omnibridge.fix.engine.config.SessionConfig;
import com.omnibridge.fix.engine.session.FixSession;
import com.omnibridge.fix.message.FixVersion;
import com.omnibridge.fix.tester.tests.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Command-line FIX session tester application.
 */
@Command(name = "session-tester",
        mixinStandardHelpOptions = true,
        version = "FIX Session Tester 1.0",
        description = "Tests FIX session functionality against a target acceptor")
public class SessionTester implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SessionTester.class);

    @Option(names = {"--host", "-h"}, description = "Target host", defaultValue = "localhost")
    private String host;

    @Option(names = {"--port", "-p"}, description = "Target port", defaultValue = "9880")
    private int port;

    @Option(names = {"--sender"}, description = "SenderCompID", defaultValue = "TESTER")
    private String senderCompId;

    @Option(names = {"--target"}, description = "TargetCompID", defaultValue = "ACCEPTOR")
    private String targetCompId;

    @Option(names = {"--tests", "-t"}, description = "Comma-separated test names (or 'all')", defaultValue = "all")
    private String tests;

    @Option(names = {"--report-format", "-f"}, description = "Report format: text, json, html", defaultValue = "text")
    private String reportFormat;

    @Option(names = {"--output", "-o"}, description = "Output file for report (stdout if not specified)")
    private String outputFile;

    @Option(names = {"--timeout"}, description = "Default timeout in seconds", defaultValue = "30")
    private int timeoutSeconds;

    @Option(names = {"--heartbeat"}, description = "Heartbeat interval in seconds", defaultValue = "30")
    private int heartbeatInterval;

    @Option(names = {"--fix-version"}, description = "FIX version (FIX.4.2, FIX.4.4, FIX.5.0, FIX.5.0SP1, FIX.5.0SP2)", defaultValue = "FIX.4.4")
    private String fixVersionStr;

    @Option(names = {"--list-tests", "-l"}, description = "List available tests and exit")
    private boolean listTests;

    // Registry of available tests
    private final Map<String, SessionTest> availableTests = new LinkedHashMap<>();

    public SessionTester() {
        // Register all available tests
        registerTest(new LogonLogoutTest());
        registerTest(new SequenceNumberTest());
        registerTest(new TestRequestTest());
        registerTest(new HeartbeatTest());
        registerTest(new HeartbeatTimeoutTest());
        registerTest(new ResendRequestTest());
        registerTest(new ConcurrentOrderTest());
        registerTest(new DuplicateLogonTest());
        registerTest(new ResetSeqNumOnLogonTest());
        registerTest(new ResetOnLogoutTest());
        registerTest(new ResetOnDisconnectTest());
        registerTest(new MultipleReconnectTest());
        registerTest(new SendInWrongStateTest());
        registerTest(new GapDetectionTest());
        registerTest(new DuplicateMessageTest());
        registerTest(new PossDupFlagTest());
        registerTest(new ResendReplayTest());
        registerTest(new SequenceResetNonGapFillTest());
        registerTest(new SequenceGapBothDirectionsTest());
        registerTest(new BackpressureTest());
        registerTest(new LargeMessageTest());
        registerTest(new ConcurrentReconnectTest());
        registerTest(new CompIdValidationTest());
        registerTest(new LogonWhileConnectedTest());
        registerTest(new MessageLoggingTest());
        registerTest(new MaxReconnectAttemptsTest());
        registerTest(new SessionStateListenerTest());
        registerTest(new LogoutAcknowledgmentTest());
        registerTest(new RejectNotificationTest());
        registerTest(new Fix50LogonTest());
    }

    private void registerTest(SessionTest test) {
        availableTests.put(test.getName().toLowerCase(), test);
    }

    @Override
    public Integer call() throws Exception {
        if (listTests) {
            listAvailableTests();
            return 0;
        }

        log.info("FIX Session Tester starting...");
        log.info("Target: {}:{}", host, port);
        log.info("Session: {} -> {}", senderCompId, targetCompId);

        // Parse report format
        ReportGenerator.Format format;
        try {
            format = ReportGenerator.Format.valueOf(reportFormat.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid report format: " + reportFormat);
            return 1;
        }

        // Determine which tests to run
        List<SessionTest> testsToRun = selectTests();
        if (testsToRun.isEmpty()) {
            System.err.println("No tests selected to run");
            return 1;
        }

        log.info("Running {} test(s): {}", testsToRun.size(),
                testsToRun.stream().map(SessionTest::getName).toList());

        // Parse FIX version
        FixVersion fixVersion;
        try {
            fixVersion = FixVersion.fromString(fixVersionStr);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid FIX version: " + fixVersionStr);
            System.err.println("Valid versions: FIX.4.2, FIX.4.4, FIX.5.0, FIX.5.0SP1, FIX.5.0SP2");
            return 1;
        }

        log.info("FIX Version: {} (BeginString: {})", fixVersion, fixVersion.getBeginString());
        if (fixVersion.usesFixt()) {
            log.info("Using FIXT.1.1 transport with DefaultApplVerID: {}", fixVersion.getDefaultApplVerID());
        }

        // Create engine and session
        FixEngine engine = null;
        try {
            SessionConfig sessionConfig = SessionConfig.builder()
                    .sessionName("TestSession")
                    .senderCompId(senderCompId)
                    .targetCompId(targetCompId)
                    .host(host)
                    .port(port)
                    .initiator()
                    .heartbeatInterval(heartbeatInterval)
                    .resetOnLogon(true)
                    .fixVersion(fixVersion)
                    .build();

            EngineConfig engineConfig = EngineConfig.builder()
                    .addSession(sessionConfig)
                    .build();

            engine = new FixEngine(engineConfig);
            engine.start();

            // Create session
            FixSession session = engine.createSession(sessionConfig);

            // Create test context
            TestContext context = new TestContext(engine, session, timeoutSeconds * 1000L);

            // Connect and wait for logon
            log.info("Connecting to {}:{}...", host, port);
            engine.connect(session.getConfig().getSessionId());

            if (!context.waitForLogon()) {
                System.err.println("Failed to establish connection and logon");
                return 1;
            }

            log.info("Connected and logged on successfully");

            // Run tests
            TestSuite suite = new TestSuite("FIX Session Tests - " + senderCompId + " -> " + targetCompId);

            for (SessionTest test : testsToRun) {
                log.info("Running test: {}", test.getName());
                TestResult result = test.execute(context);
                result.setDescription(test.getDescription());
                suite.addResult(result);
                log.info("Test {}: {} - {}", test.getName(), result.getStatus(), result.getMessage());
            }

            suite.complete();

            // Generate report
            ReportGenerator generator = new ReportGenerator();
            String report = generator.generate(suite, format);

            // Output report
            if (outputFile != null && !outputFile.isEmpty()) {
                try (FileWriter writer = new FileWriter(outputFile)) {
                    writer.write(report);
                }
                log.info("Report written to: {}", outputFile);
            } else {
                System.out.println(report);
            }

            return suite.isAllPassed() ? 0 : 1;

        } catch (Exception e) {
            log.error("Error during testing", e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        } finally {
            if (engine != null) {
                engine.stop();
            }
        }
    }

    private void listAvailableTests() {
        System.out.println("Available Tests:");
        System.out.println("-".repeat(60));
        for (SessionTest test : availableTests.values()) {
            System.out.printf("  %-25s %s%n", test.getName(), test.getDescription());
        }
    }

    private List<SessionTest> selectTests() {
        if ("all".equalsIgnoreCase(tests)) {
            return new ArrayList<>(availableTests.values());
        }

        List<SessionTest> selected = new ArrayList<>();
        for (String name : tests.split(",")) {
            String trimmed = name.trim().toLowerCase();
            SessionTest test = availableTests.get(trimmed);
            if (test != null) {
                selected.add(test);
            } else {
                log.warn("Unknown test: {}", name);
            }
        }
        return selected;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SessionTester()).execute(args);
        System.exit(exitCode);
    }
}
