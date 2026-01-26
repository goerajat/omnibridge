package com.omnibridge.fix.tester;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Collection of test results with summary reporting.
 */
public class TestSuite {

    private final String suiteName;
    private final Instant startTime;
    private Instant endTime;
    private final List<TestResult> results = new ArrayList<>();

    public TestSuite(String suiteName) {
        this.suiteName = suiteName;
        this.startTime = Instant.now();
    }

    /**
     * Add a test result to the suite.
     */
    public void addResult(TestResult result) {
        results.add(result);
    }

    /**
     * Mark the suite as complete.
     */
    public void complete() {
        this.endTime = Instant.now();
    }

    public String getSuiteName() {
        return suiteName;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public List<TestResult> getResults() {
        return new ArrayList<>(results);
    }

    public int getTotalTests() {
        return results.size();
    }

    public int getPassedCount() {
        return (int) results.stream().filter(TestResult::isPassed).count();
    }

    public int getFailedCount() {
        return (int) results.stream().filter(TestResult::isFailed).count();
    }

    public int getErrorCount() {
        return (int) results.stream().filter(TestResult::isError).count();
    }

    public int getSkippedCount() {
        return (int) results.stream()
                .filter(r -> r.getStatus() == TestResult.Status.SKIPPED)
                .count();
    }

    public long getTotalDurationMs() {
        return results.stream().mapToLong(TestResult::getDurationMs).sum();
    }

    public boolean isAllPassed() {
        return getFailedCount() == 0 && getErrorCount() == 0;
    }

    /**
     * Get a summary string for the test suite.
     */
    public String getSummary() {
        return String.format("Suite: %s | Total: %d | Passed: %d | Failed: %d | Errors: %d | Skipped: %d | Duration: %d ms",
                suiteName, getTotalTests(), getPassedCount(), getFailedCount(),
                getErrorCount(), getSkippedCount(), getTotalDurationMs());
    }
}
