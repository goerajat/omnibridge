package com.fixengine.reference.test;

import com.fixengine.reference.initiator.ReferenceInitiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.fix44.ExecutionReport;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Context for running tests with utility methods.
 */
public class TestContext {

    private static final Logger log = LoggerFactory.getLogger(TestContext.class);

    private final long defaultTimeoutMs;

    public TestContext() {
        this(30000); // 30 seconds default
    }

    public TestContext(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    /**
     * Wait for a condition to be true.
     */
    public boolean waitForCondition(Supplier<Boolean> condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /**
     * Wait for logon.
     */
    public boolean waitForLogon(ReferenceInitiator initiator, long timeoutMs) throws InterruptedException {
        return initiator.waitForLogon(timeoutMs);
    }

    /**
     * Wait for logout.
     */
    public boolean waitForLogout(ReferenceInitiator initiator, long timeoutMs) throws InterruptedException {
        return waitForCondition(() -> !initiator.isLoggedOn(), timeoutMs);
    }

    /**
     * Wait for an execution report.
     */
    public ExecutionReport waitForExecutionReport(ReferenceInitiator initiator, long timeoutMs)
            throws InterruptedException {
        return initiator.pollExecutionReport(timeoutMs);
    }

    /**
     * Sleep for the specified duration.
     */
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    /**
     * Verify a condition is true.
     */
    public void verify(boolean condition, String message) throws AssertionError {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Assert two values are equal.
     */
    public void assertEquals(Object expected, Object actual, String message) throws AssertionError {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || !expected.equals(actual)) {
            throw new AssertionError(message + " - Expected: " + expected + ", Actual: " + actual);
        }
    }

    /**
     * Assert a value is not null.
     */
    public void assertNotNull(Object value, String message) throws AssertionError {
        if (value == null) {
            throw new AssertionError(message + " - Value was null");
        }
    }

    /**
     * Assert a value is true.
     */
    public void assertTrue(boolean value, String message) throws AssertionError {
        if (!value) {
            throw new AssertionError(message + " - Expected true, got false");
        }
    }

    /**
     * Assert a value is false.
     */
    public void assertFalse(boolean value, String message) throws AssertionError {
        if (value) {
            throw new AssertionError(message + " - Expected false, got true");
        }
    }

    /**
     * Log test information.
     */
    public void log(String message) {
        log.info("[TEST] {}", message);
    }

    /**
     * Log test debug information.
     */
    public void debug(String message) {
        log.debug("[TEST] {}", message);
    }
}
