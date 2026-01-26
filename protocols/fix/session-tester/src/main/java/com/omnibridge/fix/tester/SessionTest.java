package com.omnibridge.fix.tester;

/**
 * Interface for individual FIX session tests.
 */
public interface SessionTest {

    /**
     * Get the name of this test.
     */
    String getName();

    /**
     * Get a description of what this test validates.
     */
    String getDescription();

    /**
     * Execute the test.
     *
     * @param context the test context providing session access and utilities
     * @return the test result
     */
    TestResult execute(TestContext context);
}
