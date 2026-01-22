package com.fixengine.reference.test;

import com.fixengine.reference.initiator.ReferenceInitiator;

/**
 * Interface for reference FIX tests.
 */
public interface ReferenceTest {

    /**
     * Get the test name.
     */
    String getName();

    /**
     * Get a description of what this test does.
     */
    String getDescription();

    /**
     * Run the test.
     *
     * @param initiator the reference initiator to use
     * @param context test context with utilities
     * @return the test result
     */
    TestResult run(ReferenceInitiator initiator, TestContext context);
}
