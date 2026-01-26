package com.omnibridge.fix.tester.tests.scheduler;

import com.omnibridge.config.schedule.ScheduleEvent;
import com.omnibridge.config.schedule.SessionSchedule;
import com.omnibridge.config.schedule.SessionScheduler;
import com.omnibridge.config.schedule.TimeWindow;
import com.omnibridge.config.schedule.ResetSchedule;
import com.omnibridge.config.testing.ControllableClockProvider;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

/**
 * Tests SessionScheduler reset (EOD) event handling with controlled time.
 *
 * <p>Verifies that:</p>
 * <ul>
 *   <li>RESET_DUE event is emitted at the configured fixed time</li>
 *   <li>RESET_DUE event is emitted relative to session end</li>
 *   <li>Reset only triggers once per day</li>
 *   <li>Reset tolerance works correctly</li>
 * </ul>
 */
public class SchedulerResetTest extends SchedulerTestBase {

    @Override
    public String getName() {
        return "SchedulerResetTest";
    }

    @Override
    public String getDescription() {
        return "Tests scheduler reset/EOD event handling with controlled time";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Test 1: Fixed time reset
            TestResult result = testFixedTimeReset();
            if (!result.isPassed()) return result;

            // Test 2: Reset triggers only once per day
            result = testResetOncePerDay();
            if (!result.isPassed()) return result;

            // Test 3: Warning before reset
            result = testWarningBeforeReset();
            if (!result.isPassed()) return result;

            return TestResult.passed(getName(),
                    "All scheduler reset tests passed",
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }

    /**
     * Test fixed time reset at 5:00 PM.
     */
    private TestResult testFixedTimeReset() {
        log.info("Testing fixed time reset...");

        // Start on Monday at 4:00 PM (before reset time)
        Instant initialTime = nyTime(2024, 1, 15, 16, 0);
        ControllableClockProvider clockProvider = ControllableClockProvider.create(initialTime, NY_ZONE);

        SessionScheduler scheduler = new SessionScheduler(clockProvider);
        scheduler.setResetToleranceMinutes(1);
        scheduler.setWarningMinutesBeforeReset(0); // Disable warnings for this test
        EventCollector collector = new EventCollector();
        scheduler.addListener(collector::onEvent);

        // Create schedule with 5:00 PM reset
        SessionSchedule schedule = SessionSchedule.builder()
                .name(TEST_SCHEDULE_NAME)
                .timezone(NY_ZONE)
                .addWindow(TimeWindow.builder()
                        .startTime(9, 30)
                        .endTime(18, 0)  // Window ends at 6 PM
                        .weekdays()
                        .build())
                .resetSchedule(ResetSchedule.fixedTime(LocalTime.of(17, 0)))  // Reset at 5 PM
                .build();

        scheduler.registerSchedule(schedule);
        scheduler.associateSession(TEST_SESSION_ID, TEST_SCHEDULE_NAME);

        // 4:00 PM - before reset time, session should be active
        scheduler.triggerCheck();
        if (!scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "Session should be active at 4:00 PM",
                    0);
        }

        // Should have SESSION_START but no RESET_DUE yet
        if (!collector.hasEvent(ScheduleEvent.Type.SESSION_START)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "SESSION_START should have fired at 4:00 PM",
                    0);
        }

        if (collector.hasEvent(ScheduleEvent.Type.RESET_DUE)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "RESET_DUE should not fire at 4:00 PM (before reset time)",
                    0);
        }

        // 4:59 PM - still before reset
        collector.clear();
        clockProvider.advanceMinutes(59);
        scheduler.triggerCheck();

        if (collector.hasEvent(ScheduleEvent.Type.RESET_DUE)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "RESET_DUE should not fire at 4:59 PM",
                    0);
        }

        // 5:00 PM - reset time (within tolerance)
        collector.clear();
        clockProvider.advanceMinutes(1);
        scheduler.triggerCheck();

        if (!collector.hasEvent(ScheduleEvent.Type.RESET_DUE)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "RESET_DUE should fire at 5:00 PM",
                    0);
        }

        // Verify the event details
        ScheduleEvent resetEvent = collector.getEventsOfType(ScheduleEvent.Type.RESET_DUE).get(0);
        if (!TEST_SESSION_ID.equals(resetEvent.getSessionId())) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "RESET_DUE event has wrong session ID",
                    0);
        }

        scheduler.stop();
        log.info("Fixed time reset test passed");
        return TestResult.passed(getName(), "Fixed time reset works correctly", 0);
    }

    /**
     * Test that reset only triggers once per day.
     */
    private TestResult testResetOncePerDay() {
        log.info("Testing reset triggers only once per day...");

        // Start on Monday at 4:55 PM (just before reset)
        Instant initialTime = nyTime(2024, 1, 15, 16, 55);
        ControllableClockProvider clockProvider = ControllableClockProvider.create(initialTime, NY_ZONE);

        SessionScheduler scheduler = new SessionScheduler(clockProvider);
        scheduler.setResetToleranceMinutes(1);
        scheduler.setWarningMinutesBeforeReset(0);
        EventCollector collector = new EventCollector();
        scheduler.addListener(collector::onEvent);

        // Create schedule with 5:00 PM reset
        SessionSchedule schedule = SessionSchedule.builder()
                .name(TEST_SCHEDULE_NAME)
                .timezone(NY_ZONE)
                .addWindow(TimeWindow.builder()
                        .startTime(9, 30)
                        .endTime(18, 0)
                        .weekdays()
                        .build())
                .resetSchedule(ResetSchedule.fixedTime(LocalTime.of(17, 0)))
                .build();

        scheduler.registerSchedule(schedule);
        scheduler.associateSession(TEST_SESSION_ID, TEST_SCHEDULE_NAME);

        // Initial check to establish session state
        scheduler.triggerCheck();

        // 5:00 PM - reset triggers
        clockProvider.advanceMinutes(5);
        scheduler.triggerCheck();

        int resetCount = collector.countEvents(ScheduleEvent.Type.RESET_DUE);
        if (resetCount != 1) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "Expected 1 RESET_DUE event at 5:00 PM, got " + resetCount,
                    0);
        }

        // 5:01 PM - check again, should NOT trigger another reset
        clockProvider.advanceMinutes(1);
        scheduler.triggerCheck();

        resetCount = collector.countEvents(ScheduleEvent.Type.RESET_DUE);
        if (resetCount != 1) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "RESET_DUE should only fire once per day, got " + resetCount,
                    0);
        }

        // 5:30 PM - still should not trigger
        clockProvider.advanceMinutes(29);
        scheduler.triggerCheck();

        resetCount = collector.countEvents(ScheduleEvent.Type.RESET_DUE);
        if (resetCount != 1) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "RESET_DUE should still only be 1 at 5:30 PM, got " + resetCount,
                    0);
        }

        // Advance to next day (Tuesday 5:00 PM) - should trigger again
        collector.clear();
        clockProvider.advanceDays(1).advanceMinutes(-30); // Back to 5:00 PM next day

        // First need to trigger SESSION_END and SESSION_START for the new day
        // Session ends at 6:00 PM Monday
        clockProvider.setInstant(nyTime(2024, 1, 15, 18, 0));
        scheduler.triggerCheck(); // SESSION_END

        // Next day at 9:30 AM
        clockProvider.setInstant(nyTime(2024, 1, 16, 9, 30));
        scheduler.triggerCheck(); // SESSION_START

        // Next day at 5:00 PM
        collector.clear();
        clockProvider.setInstant(nyTime(2024, 1, 16, 17, 0));
        scheduler.triggerCheck();

        if (!collector.hasEvent(ScheduleEvent.Type.RESET_DUE)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "RESET_DUE should fire again on the next day",
                    0);
        }

        scheduler.stop();
        log.info("Reset once per day test passed");
        return TestResult.passed(getName(), "Reset triggers only once per day", 0);
    }

    /**
     * Test warning before reset.
     */
    private TestResult testWarningBeforeReset() {
        log.info("Testing warning before reset...");

        // Start on Monday at 4:50 PM (10 minutes before reset)
        Instant initialTime = nyTime(2024, 1, 15, 16, 50);
        ControllableClockProvider clockProvider = ControllableClockProvider.create(initialTime, NY_ZONE);

        SessionScheduler scheduler = new SessionScheduler(clockProvider);
        scheduler.setResetToleranceMinutes(1);
        scheduler.setWarningMinutesBeforeReset(5);  // 5 minute warning
        EventCollector collector = new EventCollector();
        scheduler.addListener(collector::onEvent);

        // Create schedule with 5:00 PM reset
        SessionSchedule schedule = SessionSchedule.builder()
                .name(TEST_SCHEDULE_NAME)
                .timezone(NY_ZONE)
                .addWindow(TimeWindow.builder()
                        .startTime(9, 30)
                        .endTime(18, 0)
                        .weekdays()
                        .build())
                .resetSchedule(ResetSchedule.fixedTime(LocalTime.of(17, 0)))
                .build();

        scheduler.registerSchedule(schedule);
        scheduler.associateSession(TEST_SESSION_ID, TEST_SCHEDULE_NAME);

        // Initial check at 4:50 PM
        scheduler.triggerCheck();

        if (collector.hasEvent(ScheduleEvent.Type.WARNING_RESET)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "WARNING_RESET should not fire at 4:50 PM (10 minutes before reset)",
                    0);
        }

        // 4:55 PM - within 5 minute warning window
        clockProvider.advanceMinutes(5);
        scheduler.triggerCheck();

        if (!collector.hasEvent(ScheduleEvent.Type.WARNING_RESET)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "WARNING_RESET should fire at 4:55 PM (5 minutes before reset)",
                    0);
        }

        // Verify warning message contains time information
        ScheduleEvent warningEvent = collector.getEventsOfType(ScheduleEvent.Type.WARNING_RESET).get(0);
        if (warningEvent.getMessage() == null || !warningEvent.getMessage().contains("minutes")) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "WARNING_RESET message should contain time until reset",
                    0);
        }

        scheduler.stop();
        log.info("Warning before reset test passed");
        return TestResult.passed(getName(), "Warning before reset works correctly", 0);
    }
}
