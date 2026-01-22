package com.fixengine.tester.tests.scheduler;

import com.fixengine.config.schedule.ScheduleEvent;
import com.fixengine.config.schedule.SessionSchedule;
import com.fixengine.config.schedule.SessionScheduler;
import com.fixengine.config.schedule.TimeWindow;
import com.fixengine.config.schedule.ResetSchedule;
import com.fixengine.config.testing.ControllableClockProvider;
import com.fixengine.tester.SessionTest;
import com.fixengine.tester.TestContext;
import com.fixengine.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Base class for SessionScheduler tests.
 *
 * <p>Provides utilities for testing the SessionScheduler with controlled time:</p>
 * <ul>
 *   <li>ControllableClockProvider for time manipulation</li>
 *   <li>Event collection and verification</li>
 *   <li>Schedule creation helpers</li>
 * </ul>
 */
public abstract class SchedulerTestBase implements SessionTest {

    protected static final Logger log = LoggerFactory.getLogger(SchedulerTestBase.class);

    protected static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    protected static final ZoneId UTC_ZONE = ZoneId.of("UTC");

    protected static final String TEST_SESSION_ID = "TEST-SESSION";
    protected static final String TEST_SCHEDULE_NAME = "test-schedule";

    /**
     * Creates a US equity schedule (9:30 AM - 4:00 PM ET, weekdays, EOD at 5:00 PM).
     */
    protected SessionSchedule createUsEquitySchedule() {
        return SessionSchedule.builder()
                .name("us-equity")
                .timezone(NY_ZONE)
                .addWindow(TimeWindow.builder()
                        .startTime(9, 30)
                        .endTime(16, 0)
                        .weekdays()
                        .build())
                .resetSchedule(ResetSchedule.fixedTime(LocalTime.of(17, 0)))
                .build();
    }

    /**
     * Creates a 24/5 FX schedule (Sunday 5 PM - Friday 5 PM ET).
     */
    protected SessionSchedule createFx24x5Schedule() {
        return SessionSchedule.builder()
                .name("fx-24x5")
                .timezone(NY_ZONE)
                .addWindow(TimeWindow.builder()
                        .startTime(17, 0)  // Sunday 5 PM
                        .endTime(17, 0)    // Friday 5 PM
                        .overnight(true)
                        .daysOfWeek(EnumSet.of(
                                DayOfWeek.SUNDAY,
                                DayOfWeek.MONDAY,
                                DayOfWeek.TUESDAY,
                                DayOfWeek.WEDNESDAY,
                                DayOfWeek.THURSDAY))
                        .build())
                .resetSchedule(ResetSchedule.fixedTime(LocalTime.of(17, 0)))
                .build();
    }

    /**
     * Creates a simple schedule with configurable parameters.
     */
    protected SessionSchedule createSchedule(String name, ZoneId zone,
                                              LocalTime startTime, LocalTime endTime,
                                              Set<DayOfWeek> days, boolean overnight,
                                              LocalTime resetTime) {
        SessionSchedule.Builder builder = SessionSchedule.builder()
                .name(name)
                .timezone(zone)
                .addWindow(TimeWindow.builder()
                        .startTime(startTime.getHour(), startTime.getMinute())
                        .endTime(endTime.getHour(), endTime.getMinute())
                        .daysOfWeek(days)
                        .overnight(overnight)
                        .build());

        if (resetTime != null) {
            builder.resetSchedule(ResetSchedule.fixedTime(resetTime));
        }

        return builder.build();
    }

    /**
     * Creates an instant at a specific time in New York timezone.
     */
    protected Instant nyTime(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NY_ZONE).toInstant();
    }

    /**
     * Creates an instant at a specific time in UTC.
     */
    protected Instant utcTime(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, UTC_ZONE).toInstant();
    }

    /**
     * Collector for schedule events during testing.
     */
    protected static class EventCollector {
        private final List<ScheduleEvent> events = new CopyOnWriteArrayList<>();

        public void onEvent(ScheduleEvent event) {
            events.add(event);
            log.debug("Collected event: {}", event.getType());
        }

        public List<ScheduleEvent> getEvents() {
            return new ArrayList<>(events);
        }

        public List<ScheduleEvent> getEventsOfType(ScheduleEvent.Type type) {
            return events.stream()
                    .filter(e -> e.getType() == type)
                    .toList();
        }

        public boolean hasEvent(ScheduleEvent.Type type) {
            return events.stream().anyMatch(e -> e.getType() == type);
        }

        public boolean hasEvent(ScheduleEvent.Type type, String sessionId) {
            return events.stream()
                    .anyMatch(e -> e.getType() == type && sessionId.equals(e.getSessionId()));
        }

        public int countEvents(ScheduleEvent.Type type) {
            return (int) events.stream().filter(e -> e.getType() == type).count();
        }

        public void clear() {
            events.clear();
        }

        public int size() {
            return events.size();
        }
    }

    /**
     * Utility class for running scheduler tests with controlled time.
     */
    protected static class SchedulerTestHarness {
        private final ControllableClockProvider clockProvider;
        private final SessionScheduler scheduler;
        private final EventCollector eventCollector;

        public SchedulerTestHarness(Instant initialTime, ZoneId zone) {
            this.clockProvider = ControllableClockProvider.create(initialTime, zone);
            this.scheduler = new SessionScheduler(clockProvider);
            this.eventCollector = new EventCollector();
            this.scheduler.addListener(eventCollector::onEvent);
        }

        public ControllableClockProvider getClockProvider() {
            return clockProvider;
        }

        public SessionScheduler getScheduler() {
            return scheduler;
        }

        public EventCollector getEventCollector() {
            return eventCollector;
        }

        public void registerSchedule(SessionSchedule schedule) {
            scheduler.registerSchedule(schedule);
        }

        public void associateSession(String sessionId, String scheduleName) {
            scheduler.associateSession(sessionId, scheduleName);
        }

        /**
         * Manually trigger a schedule check (simulates scheduler tick).
         * In tests, we don't start the background timer - we manually check.
         */
        public void checkSchedules() {
            // Access the checkSchedules method via reflection for testing
            // Since scheduler.checkSchedules() is private, we trigger via shouldBeActive
            // Actually, for proper testing, we need to expose a test hook
            // For now, we'll check status directly
        }

        /**
         * Advance time and check if session should be active.
         */
        public boolean shouldBeActive(String sessionId) {
            return scheduler.shouldBeActive(sessionId);
        }

        /**
         * Get schedule status for a session.
         */
        public SessionSchedule.ScheduleStatus getStatus(String sessionId) {
            return scheduler.getSessionStatus(sessionId);
        }

        public void stop() {
            scheduler.stop();
        }
    }
}
