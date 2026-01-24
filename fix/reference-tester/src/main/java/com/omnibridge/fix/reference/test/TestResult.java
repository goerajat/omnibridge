package com.omnibridge.fix.reference.test;

import java.time.Duration;
import java.time.Instant;

/**
 * Result of a single test execution.
 */
public class TestResult {

    public enum Status {
        PASSED,
        FAILED,
        ERROR,
        SKIPPED
    }

    private final String testName;
    private final Status status;
    private final String message;
    private final Instant startTime;
    private final Instant endTime;
    private final Duration duration;
    private final Throwable exception;

    private TestResult(Builder builder) {
        this.testName = builder.testName;
        this.status = builder.status;
        this.message = builder.message;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.duration = builder.duration;
        this.exception = builder.exception;
    }

    public static Builder builder(String testName) {
        return new Builder(testName);
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

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public Duration getDuration() {
        return duration;
    }

    public Throwable getException() {
        return exception;
    }

    public boolean isPassed() {
        return status == Status.PASSED;
    }

    public boolean isFailed() {
        return status == Status.FAILED || status == Status.ERROR;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s - %s (%.3fs)",
                status, testName, message, duration.toMillis() / 1000.0);
    }

    public static class Builder {
        private final String testName;
        private Status status = Status.PASSED;
        private String message = "";
        private Instant startTime;
        private Instant endTime;
        private Duration duration = Duration.ZERO;
        private Throwable exception;

        private Builder(String testName) {
            this.testName = testName;
            this.startTime = Instant.now();
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder passed(String message) {
            this.status = Status.PASSED;
            this.message = message;
            return this;
        }

        public Builder failed(String message) {
            this.status = Status.FAILED;
            this.message = message;
            return this;
        }

        public Builder error(String message, Throwable exception) {
            this.status = Status.ERROR;
            this.message = message;
            this.exception = exception;
            return this;
        }

        public Builder skipped(String reason) {
            this.status = Status.SKIPPED;
            this.message = reason;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            if (this.startTime != null) {
                this.duration = Duration.between(this.startTime, endTime);
            }
            return this;
        }

        public Builder exception(Throwable exception) {
            this.exception = exception;
            return this;
        }

        public TestResult build() {
            if (endTime == null) {
                endTime = Instant.now();
                duration = Duration.between(startTime, endTime);
            }
            return new TestResult(this);
        }
    }
}
