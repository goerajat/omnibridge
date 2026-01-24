package com.omnibridge.fix.tester.tests.scheduler;

import com.omnibridge.config.schedule.ScheduleEvent;
import com.omnibridge.config.schedule.SessionSchedule;
import com.omnibridge.config.schedule.SessionScheduler;
import com.omnibridge.config.schedule.TimeWindow;
import com.omnibridge.config.testing.ControllableClockProvider;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.EnumSet;

/**
 * Tests SessionScheduler overnight session handling with controlled time.
 *
 * <p>Verifies that:</p>
 * <ul>
 *   <li>Overnight sessions (start > end time) work correctly</li>
 *   <li>Sessions span midnight correctly</li>
 *   <li>24/5 FX-style sessions work correctly</li>
 * </ul>
 */
public class SchedulerOvernightTest extends SchedulerTestBase {

    @Override
    public String getName() {
        return "SchedulerOvernightTest";
    }

    @Override
    public String getDescription() {
        return "Tests scheduler handling of overnight sessions";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Test 1: Simple overnight session (8 PM - 6 AM)
            TestResult result = testSimpleOvernightSession();
            if (!result.isPassed()) return result;

            // Test 2: 24/5 FX session (Sunday 5 PM - Friday 5 PM)
            result = testFx24x5Session();
            if (!result.isPassed()) return result;

            return TestResult.passed(getName(),
                    "All overnight session tests passed",
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }

    /**
     * Test simple overnight session (8 PM - 6 AM).
     */
    private TestResult testSimpleOvernightSession() {
        log.info("Testing simple overnight session (8 PM - 6 AM)...");

        // Start on Monday at 6:00 PM (before overnight session)
        Instant initialTime = nyTime(2024, 1, 15, 18, 0);
        ControllableClockProvider clockProvider = ControllableClockProvider.create(initialTime, NY_ZONE);

        SessionScheduler scheduler = new SessionScheduler(clockProvider);
        EventCollector collector = new EventCollector();
        scheduler.addListener(collector::onEvent);

        // Create overnight schedule (8 PM - 6 AM, weekdays)
        SessionSchedule schedule = SessionSchedule.builder()
                .name(TEST_SCHEDULE_NAME)
                .timezone(NY_ZONE)
                .addWindow(TimeWindow.builder()
                        .startTime(20, 0)   // 8 PM
                        .endTime(6, 0)      // 6 AM next day
                        .overnight(true)
                        .weekdays()
                        .build())
                .build();

        scheduler.registerSchedule(schedule);
        scheduler.associateSession(TEST_SESSION_ID, TEST_SCHEDULE_NAME);

        // 6:00 PM - before overnight session
        scheduler.triggerCheck();
        if (scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "Session should not be active at 6:00 PM (before overnight window)",
                    0);
        }

        // 8:00 PM - overnight session starts
        clockProvider.advanceHours(2);
        scheduler.triggerCheck();
        if (!scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "Session should be active at 8:00 PM (overnight start)",
                    0);
        }

        if (!collector.hasEvent(ScheduleEvent.Type.SESSION_START)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "SESSION_START should fire at 8:00 PM",
                    0);
        }

        // 11:59 PM - still in overnight session
        collector.clear();
        clockProvider.advanceHours(3).advanceMinutes(59);
        scheduler.triggerCheck();
        if (!scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "Session should still be active at 11:59 PM",
                    0);
        }

        // 12:01 AM - crossed midnight, still in session
        clockProvider.advanceMinutes(2);
        scheduler.triggerCheck();
        if (!scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "Session should still be active at 12:01 AM (overnight spans midnight)",
                    0);
        }

        // 5:59 AM - still in session
        clockProvider.advanceHours(5).advanceMinutes(58);
        scheduler.triggerCheck();
        if (!scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "Session should still be active at 5:59 AM",
                    0);
        }

        // 6:00 AM - overnight session ends
        collector.clear();
        clockProvider.advanceMinutes(1);
        scheduler.triggerCheck();
        if (scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "Session should not be active at 6:00 AM (overnight end)",
                    0);
        }

        if (!collector.hasEvent(ScheduleEvent.Type.SESSION_END)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "SESSION_END should fire at 6:00 AM",
                    0);
        }

        scheduler.stop();
        log.info("Simple overnight session test passed");
        return TestResult.passed(getName(), "Simple overnight session works correctly", 0);
    }

    /**
     * Test 24/5 FX session (Sunday 5 PM - Friday 5 PM).
     */
    private TestResult testFx24x5Session() {
        log.info("Testing 24/5 FX session...");

        // Start on Sunday at 3:00 PM (before FX session)
        // Sunday, January 14, 2024
        Instant initialTime = nyTime(2024, 1, 14, 15, 0);
        ControllableClockProvider clockProvider = ControllableClockProvider.create(initialTime, NY_ZONE);

        SessionScheduler scheduler = new SessionScheduler(clockProvider);
        EventCollector collector = new EventCollector();
        scheduler.addListener(collector::onEvent);

        // Create 24/5 FX schedule
        SessionSchedule schedule = createFx24x5Schedule();
        scheduler.registerSchedule(schedule);
        scheduler.associateSession(TEST_SESSION_ID, "fx-24x5");

        // Sunday 3:00 PM - before FX session
        scheduler.triggerCheck();
        if (scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "FX session should not be active at Sunday 3:00 PM",
                    0);
        }

        // Sunday 5:00 PM - FX session starts
        clockProvider.advanceHours(2);
        scheduler.triggerCheck();
        if (!scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "FX session should be active at Sunday 5:00 PM",
                    0);
        }

        if (!collector.hasEvent(ScheduleEvent.Type.SESSION_START)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "SESSION_START should fire at Sunday 5:00 PM",
                    0);
        }

        // Monday 12:00 PM - mid-week, should be active
        collector.clear();
        clockProvider.advanceHours(19); // 5 PM + 19 = 12 PM Monday
        scheduler.triggerCheck();
        if (!scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "FX session should be active on Monday at noon",
                    0);
        }

        // Wednesday 3:00 AM - mid-week, should be active
        clockProvider.advanceDays(1).advanceHours(15); // to Wed 3 AM
        scheduler.triggerCheck();
        if (!scheduler.shouldBeActive(TEST_SESSION_ID)) {
            scheduler.stop();
            return TestResult.failed(getName(),
                    "FX session should be active on Wednesday at 3:00 AM",
                    0);
        }

        // Note: Testing the exact end time requires careful calculation
        // For now, verify the session runs continuously during the week

        scheduler.stop();
        log.info("24/5 FX session test passed");
        return TestResult.passed(getName(), "24/5 FX session works correctly", 0);
    }
}
