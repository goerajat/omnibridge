package com.omnibridge.config.schedule;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration parser for session schedules from HOCON config.
 *
 * <p>Example configuration:</p>
 * <pre>{@code
 * schedulers {
 *   us-equity {
 *     timezone = "America/New_York"
 *     windows = [
 *       { start = "09:30", end = "16:00", days = ["MON", "TUE", "WED", "THU", "FRI"] }
 *     ]
 *     reset {
 *       time = "17:00"
 *       days = ["MON", "TUE", "WED", "THU", "FRI"]
 *     }
 *   }
 *
 *   overnight-fx {
 *     timezone = "America/New_York"
 *     windows = [
 *       { start = "17:00", end = "17:00", overnight = true, days = ["SUN", "MON", "TUE", "WED", "THU"] }
 *     ]
 *     reset {
 *       time = "17:00"
 *       days = ["MON", "TUE", "WED", "THU", "FRI"]
 *     }
 *   }
 *
 *   weekly-batch {
 *     timezone = "UTC"
 *     windows = [
 *       { start = "02:00", end = "04:00", days = ["SAT"] }
 *     ]
 *     reset {
 *       relative-to-end = "5m"  # 5 minutes before end
 *     }
 *   }
 * }
 *
 * scheduler {
 *   check-interval = 1s
 *   reset-tolerance = 1m
 *   warning-before-end = 5m
 *   warning-before-reset = 5m
 * }
 * }</pre>
 */
public class SchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm");

    private final List<SessionSchedule> schedules;
    private final long checkIntervalSeconds;
    private final int resetToleranceMinutes;
    private final int warningMinutesBeforeEnd;
    private final int warningMinutesBeforeReset;

    private SchedulerConfig(List<SessionSchedule> schedules, long checkIntervalSeconds,
                            int resetToleranceMinutes, int warningMinutesBeforeEnd,
                            int warningMinutesBeforeReset) {
        this.schedules = schedules;
        this.checkIntervalSeconds = checkIntervalSeconds;
        this.resetToleranceMinutes = resetToleranceMinutes;
        this.warningMinutesBeforeEnd = warningMinutesBeforeEnd;
        this.warningMinutesBeforeReset = warningMinutesBeforeReset;
    }

    /**
     * Parse scheduler configuration from HOCON Config.
     *
     * @param config the root config
     * @return the parsed configuration
     */
    public static SchedulerConfig fromConfig(Config config) {
        List<SessionSchedule> schedules = new ArrayList<>();

        // Parse schedule definitions
        if (config.hasPath("schedulers")) {
            Config schedulersConfig = config.getConfig("schedulers");
            for (String scheduleName : schedulersConfig.root().keySet()) {
                try {
                    Config scheduleConfig = schedulersConfig.getConfig(scheduleName);
                    SessionSchedule schedule = parseSchedule(scheduleName, scheduleConfig);
                    schedules.add(schedule);
                    log.info("Parsed schedule: {}", scheduleName);
                } catch (Exception e) {
                    log.error("Error parsing schedule '{}': {}", scheduleName, e.getMessage());
                    throw new IllegalArgumentException("Invalid schedule configuration: " + scheduleName, e);
                }
            }
        }

        // Parse scheduler settings
        long checkIntervalSeconds = 1;
        int resetToleranceMinutes = 1;
        int warningMinutesBeforeEnd = 5;
        int warningMinutesBeforeReset = 5;

        if (config.hasPath("scheduler")) {
            Config schedulerConfig = config.getConfig("scheduler");

            if (schedulerConfig.hasPath("check-interval")) {
                checkIntervalSeconds = schedulerConfig.getDuration("check-interval").toSeconds();
            }
            if (schedulerConfig.hasPath("reset-tolerance")) {
                resetToleranceMinutes = (int) schedulerConfig.getDuration("reset-tolerance").toMinutes();
            }
            if (schedulerConfig.hasPath("warning-before-end")) {
                warningMinutesBeforeEnd = (int) schedulerConfig.getDuration("warning-before-end").toMinutes();
            }
            if (schedulerConfig.hasPath("warning-before-reset")) {
                warningMinutesBeforeReset = (int) schedulerConfig.getDuration("warning-before-reset").toMinutes();
            }
        }

        return new SchedulerConfig(schedules, checkIntervalSeconds, resetToleranceMinutes,
                warningMinutesBeforeEnd, warningMinutesBeforeReset);
    }

    /**
     * Parse a single schedule definition.
     */
    private static SessionSchedule parseSchedule(String name, Config config) {
        SessionSchedule.Builder builder = SessionSchedule.builder().name(name);

        // Timezone
        if (config.hasPath("timezone")) {
            builder.timezone(ZoneId.of(config.getString("timezone")));
        }

        // Enabled flag
        if (config.hasPath("enabled")) {
            builder.enabled(config.getBoolean("enabled"));
        }

        // Time windows
        if (config.hasPath("windows")) {
            List<? extends Config> windowConfigs = config.getConfigList("windows");
            for (Config windowConfig : windowConfigs) {
                TimeWindow window = parseTimeWindow(windowConfig);
                builder.addWindow(window);
            }
        }

        // Reset schedule
        if (config.hasPath("reset")) {
            ResetSchedule resetSchedule = parseResetSchedule(config.getConfig("reset"));
            builder.resetSchedule(resetSchedule);
        }

        return builder.build();
    }

    /**
     * Parse a time window configuration.
     */
    private static TimeWindow parseTimeWindow(Config config) {
        TimeWindow.Builder builder = TimeWindow.builder();

        // Start time (required)
        String startStr = config.getString("start");
        builder.startTime(parseTime(startStr));

        // End time (required)
        String endStr = config.getString("end");
        builder.endTime(parseTime(endStr));

        // Overnight flag
        if (config.hasPath("overnight")) {
            builder.overnight(config.getBoolean("overnight"));
        }

        // Days of week
        if (config.hasPath("days")) {
            List<String> dayStrings = config.getStringList("days");
            Set<DayOfWeek> days = parseDaysOfWeek(dayStrings);
            builder.daysOfWeek(days);
        }

        return builder.build();
    }

    /**
     * Parse a reset schedule configuration.
     */
    private static ResetSchedule parseResetSchedule(Config config) {
        ResetSchedule.Builder builder = ResetSchedule.builder();

        // Fixed time or relative to end
        if (config.hasPath("time")) {
            String timeStr = config.getString("time");
            builder.fixedTime(parseTime(timeStr));
        } else if (config.hasPath("relative-to-end")) {
            Duration duration = config.getDuration("relative-to-end");
            builder.relativeToEnd(duration);
        } else {
            builder.noReset();
        }

        // Days of week for reset
        if (config.hasPath("days")) {
            List<String> dayStrings = config.getStringList("days");
            Set<DayOfWeek> days = parseDaysOfWeek(dayStrings);
            builder.daysOfWeek(days);
        }

        return builder.build();
    }

    /**
     * Parse a time string (HH:mm or H:mm format).
     */
    private static LocalTime parseTime(String timeStr) {
        try {
            return LocalTime.parse(timeStr, TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            // Try standard ISO format
            return LocalTime.parse(timeStr);
        }
    }

    /**
     * Parse day of week strings.
     */
    private static Set<DayOfWeek> parseDaysOfWeek(List<String> dayStrings) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String dayStr : dayStrings) {
            DayOfWeek day = parseDayOfWeek(dayStr.toUpperCase().trim());
            days.add(day);
        }
        return days;
    }

    /**
     * Parse a single day of week string.
     */
    private static DayOfWeek parseDayOfWeek(String dayStr) {
        return switch (dayStr) {
            case "MON", "MONDAY" -> DayOfWeek.MONDAY;
            case "TUE", "TUESDAY" -> DayOfWeek.TUESDAY;
            case "WED", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "THU", "THURSDAY" -> DayOfWeek.THURSDAY;
            case "FRI", "FRIDAY" -> DayOfWeek.FRIDAY;
            case "SAT", "SATURDAY" -> DayOfWeek.SATURDAY;
            case "SUN", "SUNDAY" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("Invalid day of week: " + dayStr);
        };
    }

    /**
     * Apply this configuration to a SessionScheduler.
     *
     * @param scheduler the scheduler to configure
     */
    public void applyTo(SessionScheduler scheduler) {
        // Register all schedules
        for (SessionSchedule schedule : schedules) {
            scheduler.registerSchedule(schedule);
        }

        // Apply settings
        scheduler.setCheckIntervalSeconds(checkIntervalSeconds);
        scheduler.setResetToleranceMinutes(resetToleranceMinutes);
        scheduler.setWarningMinutesBeforeEnd(warningMinutesBeforeEnd);
        scheduler.setWarningMinutesBeforeReset(warningMinutesBeforeReset);
    }

    // Getters

    public List<SessionSchedule> getSchedules() {
        return schedules;
    }

    public long getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public int getResetToleranceMinutes() {
        return resetToleranceMinutes;
    }

    public int getWarningMinutesBeforeEnd() {
        return warningMinutesBeforeEnd;
    }

    public int getWarningMinutesBeforeReset() {
        return warningMinutesBeforeReset;
    }

    /**
     * Get session-to-schedule associations from config.
     *
     * <p>Expected format in HOCON:</p>
     * <pre>{@code
     * fix-engine.sessions {
     *   my-session {
     *     scheduler = "us-equity"
     *     ...
     *   }
     * }
     * }</pre>
     *
     * @param config the root config
     * @return map of session name to schedule name
     */
    public static Map<String, String> getSessionScheduleAssociations(Config config) {
        Map<String, String> associations = new java.util.HashMap<>();

        if (config.hasPath("fix-engine.sessions")) {
            Config sessionsConfig = config.getConfig("fix-engine.sessions");
            for (String sessionName : sessionsConfig.root().keySet()) {
                Config sessionConfig = sessionsConfig.getConfig(sessionName);
                if (sessionConfig.hasPath("scheduler")) {
                    String schedulerName = sessionConfig.getString("scheduler");
                    associations.put(sessionName, schedulerName);
                }
            }
        }

        return associations;
    }
}
