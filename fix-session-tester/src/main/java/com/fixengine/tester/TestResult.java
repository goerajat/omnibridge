package com.fixengine.tester;

/**
 * Result of a single test execution.
 */
public class TestResult {

    /**
     * Test execution status.
     */
    public enum Status {
        PASSED,
        FAILED,
        ERROR,
        SKIPPED
    }

    private final String testName;
    private final Status status;
    private final String message;
    private final long durationMs;
    private final Throwable error;

    private TestResult(String testName, Status status, String message, long durationMs, Throwable error) {
        this.testName = testName;
        this.status = status;
        this.message = message;
        this.durationMs = durationMs;
        this.error = error;
    }

    /**
     * Create a passed result.
     */
    public static TestResult passed(String testName, String message, long durationMs) {
        return new TestResult(testName, Status.PASSED, message, durationMs, null);
    }

    /**
     * Create a failed result.
     */
    public static TestResult failed(String testName, String message, long durationMs) {
        return new TestResult(testName, Status.FAILED, message, durationMs, null);
    }

    /**
     * Create an error result.
     */
    public static TestResult error(String testName, String message, long durationMs, Throwable error) {
        return new TestResult(testName, Status.ERROR, message, durationMs, error);
    }

    /**
     * Create a skipped result.
     */
    public static TestResult skipped(String testName, String message) {
        return new TestResult(testName, Status.SKIPPED, message, 0, null);
    }

    public String getTestName() {
        return testName;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Throwable getError() {
        return error;
    }

    public boolean isPassed() {
        return status == Status.PASSED;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s - %s (%d ms)",
                status, testName, message, durationMs);
    }
}
