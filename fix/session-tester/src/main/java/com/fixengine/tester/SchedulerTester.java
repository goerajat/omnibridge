package com.fixengine.tester;

import com.fixengine.tester.tests.scheduler.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.FileWriter;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Command-line scheduler tester application.
 *
 * <p>Tests SessionScheduler functionality with controlled time.
 * Unlike SessionTester, this doesn't require a remote acceptor -
 * it tests the scheduling logic directly with mock clocks.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Run all scheduler tests
 * java -jar session-tester.jar scheduler --tests all
 *
 * # Run specific tests
 * java -jar session-tester.jar scheduler --tests SchedulerStartEndTest,SchedulerOvernightTest
 *
 * # List available tests
 * java -jar session-tester.jar scheduler --list-tests
 * </pre>
 */
@Command(name = "scheduler",
        mixinStandardHelpOptions = true,
        version = "Scheduler Tester 1.0",
        description = "Tests SessionScheduler functionality with controlled time")
public class SchedulerTester implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTester.class);

    @Option(names = {"--tests", "-t"}, description = "Comma-separated test names (or 'all')", defaultValue = "all")
    private String tests;

    @Option(names = {"--report-format", "-f"}, description = "Report format: text, json, html", defaultValue = "text")
    private String reportFormat;

    @Option(names = {"--output", "-o"}, description = "Output file for report (stdout if not specified)")
    private String outputFile;

    @Option(names = {"--list-tests", "-l"}, description = "List available tests and exit")
    private boolean listTests;

    // Registry of available scheduler tests
    private final Map<String, SessionTest> availableTests = new LinkedHashMap<>();

    public SchedulerTester() {
        // Register all available scheduler tests
        registerTest(new SchedulerStartEndTest());
        registerTest(new SchedulerOvernightTest());
        registerTest(new SchedulerResetTest());
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

        log.info("Scheduler Tester starting...");
        log.info("Testing SessionScheduler with controlled time");

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

        log.info("Running {} scheduler test(s): {}", testsToRun.size(),
                testsToRun.stream().map(SessionTest::getName).toList());

        // Run tests (no context needed - scheduler tests manage their own setup)
        TestSuite suite = new TestSuite("SessionScheduler Tests");

        for (SessionTest test : testsToRun) {
            log.info("Running test: {}", test.getName());
            log.info("Description: {}", test.getDescription());

            // Scheduler tests don't need a real TestContext
            TestResult result = test.execute(null);
            suite.addResult(result);

            String status = result.isPassed() ? "PASSED" : "FAILED";
            log.info("Test {}: {} - {}", test.getName(), status, result.getMessage());
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

        // Print summary
        if (suite.isAllPassed()) {
            log.info("ALL SCHEDULER TESTS PASSED ({}/{})",
                    suite.getPassedCount(), suite.getTotalTests());
        } else {
            log.error("SOME TESTS FAILED ({}/{} passed)",
                    suite.getPassedCount(), suite.getTotalTests());
        }

        return suite.isAllPassed() ? 0 : 1;
    }

    private void listAvailableTests() {
        System.out.println("Available Scheduler Tests:");
        System.out.println("-".repeat(70));
        for (SessionTest test : availableTests.values()) {
            System.out.printf("  %-30s %s%n", test.getName(), test.getDescription());
        }
        System.out.println();
        System.out.println("These tests verify SessionScheduler functionality with controlled time.");
        System.out.println("No remote FIX acceptor is required.");
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
        int exitCode = new CommandLine(new SchedulerTester()).execute(args);
        System.exit(exitCode);
    }
}
