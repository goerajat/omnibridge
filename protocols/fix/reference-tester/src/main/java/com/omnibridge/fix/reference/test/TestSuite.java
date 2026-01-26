package com.omnibridge.fix.reference.test;

import com.omnibridge.fix.reference.initiator.InitiatorConfig;
import com.omnibridge.fix.reference.initiator.ReferenceInitiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Test suite for running reference FIX tests.
 */
public class TestSuite {

    private static final Logger log = LoggerFactory.getLogger(TestSuite.class);

    private final List<ReferenceTest> tests = new ArrayList<>();
    private final List<TestResult> results = new ArrayList<>();
    private final InitiatorConfig initiatorConfig;
    private final TestContext context;

    private Instant startTime;
    private Instant endTime;

    public TestSuite(InitiatorConfig initiatorConfig) {
        this(initiatorConfig, new TestContext());
    }

    public TestSuite(InitiatorConfig initiatorConfig, TestContext context) {
        this.initiatorConfig = initiatorConfig;
        this.context = context;
    }

    /**
     * Add a test to the suite.
     */
    public TestSuite addTest(ReferenceTest test) {
        tests.add(test);
        return this;
    }

    /**
     * Add multiple tests to the suite.
     */
    public TestSuite addTests(Collection<ReferenceTest> tests) {
        this.tests.addAll(tests);
        return this;
    }

    /**
     * Run all tests in the suite.
     */
    public List<TestResult> runAll() {
        results.clear();
        startTime = Instant.now();

        log.info("========================================");
        log.info("Starting test suite with {} tests", tests.size());
        log.info("Target: {}:{}", initiatorConfig.getHost(), initiatorConfig.getPort());
        log.info("========================================");

        ReferenceInitiator initiator = null;

        try {
            initiator = new ReferenceInitiator(initiatorConfig);
            initiator.start();

            // Wait for logon
            if (!initiator.waitForLogon(context.getDefaultTimeoutMs())) {
                log.error("Failed to logon within timeout");
                results.add(TestResult.builder("Connection")
                        .failed("Failed to establish FIX session")
                        .build());
                return results;
            }

            log.info("Logged on successfully, running tests...");

            // Run each test
            for (ReferenceTest test : tests) {
                log.info("----------------------------------------");
                log.info("Running test: {}", test.getName());
                log.info("Description: {}", test.getDescription());

                TestResult result;
                try {
                    result = test.run(initiator, context);
                } catch (Exception e) {
                    log.error("Test {} threw an exception", test.getName(), e);
                    result = TestResult.builder(test.getName())
                            .error("Exception: " + e.getMessage(), e)
                            .build();
                }

                results.add(result);
                log.info("Test {}: {} - {}",
                        test.getName(),
                        result.getStatus(),
                        result.getMessage());
            }

        } catch (Exception e) {
            log.error("Test suite failed with exception", e);
            results.add(TestResult.builder("TestSuite")
                    .error("Suite exception: " + e.getMessage(), e)
                    .build());
        } finally {
            if (initiator != null) {
                initiator.stop();
            }
        }

        endTime = Instant.now();

        printSummary();
        return results;
    }

    /**
     * Run a specific test by name.
     */
    public TestResult runTest(String testName) {
        ReferenceTest test = tests.stream()
                .filter(t -> t.getName().equalsIgnoreCase(testName))
                .findFirst()
                .orElse(null);

        if (test == null) {
            return TestResult.builder(testName)
                    .failed("Test not found")
                    .build();
        }

        ReferenceInitiator initiator = null;
        try {
            initiator = new ReferenceInitiator(initiatorConfig);
            initiator.start();

            if (!initiator.waitForLogon(context.getDefaultTimeoutMs())) {
                return TestResult.builder(testName)
                        .failed("Failed to establish FIX session")
                        .build();
            }

            return test.run(initiator, context);

        } catch (Exception e) {
            return TestResult.builder(testName)
                    .error("Exception: " + e.getMessage(), e)
                    .build();
        } finally {
            if (initiator != null) {
                initiator.stop();
            }
        }
    }

    /**
     * Get all test results.
     */
    public List<TestResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    /**
     * Get the number of passed tests.
     */
    public long getPassedCount() {
        return results.stream().filter(TestResult::isPassed).count();
    }

    /**
     * Get the number of failed tests.
     */
    public long getFailedCount() {
        return results.stream().filter(TestResult::isFailed).count();
    }

    /**
     * Get total duration.
     */
    public Duration getTotalDuration() {
        if (startTime == null || endTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(startTime, endTime);
    }

    /**
     * Print a summary of test results.
     */
    public void printSummary() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("TEST SUITE SUMMARY");
        System.out.println("========================================");
        System.out.println();

        for (TestResult result : results) {
            String status = switch (result.getStatus()) {
                case PASSED -> "✓ PASS";
                case FAILED -> "✗ FAIL";
                case ERROR -> "! ERROR";
                case SKIPPED -> "- SKIP";
            };
            System.out.printf("  %s  %-30s  %s%n",
                    status, result.getTestName(), result.getMessage());
        }

        System.out.println();
        System.out.println("----------------------------------------");
        System.out.printf("Total: %d tests, %d passed, %d failed%n",
                results.size(), getPassedCount(), getFailedCount());
        System.out.printf("Duration: %.3f seconds%n", getTotalDuration().toMillis() / 1000.0);
        System.out.println("========================================");
    }

    /**
     * Check if all tests passed.
     */
    public boolean allPassed() {
        return getFailedCount() == 0 && !results.isEmpty();
    }

    /**
     * Get list of available tests.
     */
    public List<ReferenceTest> getTests() {
        return Collections.unmodifiableList(tests);
    }
}
