package com.omnibridge.config.schedule;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Event emitted by the SessionScheduler when schedule state changes.
 *
 * <p>Events include:</p>
 * <ul>
 *   <li>{@link Type#SESSION_START} - Session window has started, session should connect</li>
 *   <li>{@link Type#SESSION_END} - Session window has ended, session should disconnect</li>
 *   <li>{@link Type#RESET_DUE} - Reset time reached, session should perform EOD/reset</li>
 *   <li>{@link Type#WARNING_SESSION_END} - Warning before session end (configurable)</li>
 *   <li>{@link Type#WARNING_RESET} - Warning before reset (configurable)</li>
 * </ul>
 */
public class ScheduleEvent {

    /**
     * Type of schedule event.
     */
    public enum Type {
        /** Session window has started - connect */
        SESSION_START,
        /** Session window has ended - disconnect */
        SESSION_END,
        /** Reset time reached - perform EOD/reset */
        RESET_DUE,
        /** Warning: session will end soon */
        WARNING_SESSION_END,
        /** Warning: reset will occur soon */
        WARNING_RESET
    }

    private final Type type;
    private final String sessionId;
    private final String scheduleName;
    private final ZonedDateTime eventTime;
    private final ZonedDateTime scheduledTime;
    private final String message;

    private ScheduleEvent(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type is required");
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId is required");
        this.scheduleName = builder.scheduleName;
        this.eventTime = Objects.requireNonNull(builder.eventTime, "eventTime is required");
        this.scheduledTime = builder.scheduledTime;
        this.message = builder.message;
    }

    /**
     * Create a SESSION_START event.
     */
    public static ScheduleEvent sessionStart(String sessionId, String scheduleName,
                                              ZonedDateTime eventTime, ZonedDateTime scheduledTime) {
        return builder()
                .type(Type.SESSION_START)
                .sessionId(sessionId)
                .scheduleName(scheduleName)
                .eventTime(eventTime)
                .scheduledTime(scheduledTime)
                .message("Session schedule window started")
                .build();
    }

    /**
     * Create a SESSION_END event.
     */
    public static ScheduleEvent sessionEnd(String sessionId, String scheduleName,
                                            ZonedDateTime eventTime, ZonedDateTime scheduledTime) {
        return builder()
                .type(Type.SESSION_END)
                .sessionId(sessionId)
                .scheduleName(scheduleName)
                .eventTime(eventTime)
                .scheduledTime(scheduledTime)
                .message("Session schedule window ended")
                .build();
    }

    /**
     * Create a RESET_DUE event.
     */
    public static ScheduleEvent resetDue(String sessionId, String scheduleName,
                                          ZonedDateTime eventTime, ZonedDateTime scheduledTime) {
        return builder()
                .type(Type.RESET_DUE)
                .sessionId(sessionId)
                .scheduleName(scheduleName)
                .eventTime(eventTime)
                .scheduledTime(scheduledTime)
                .message("Session reset/EOD due")
                .build();
    }

    /**
     * Create a WARNING_SESSION_END event.
     */
    public static ScheduleEvent warningSessionEnd(String sessionId, String scheduleName,
                                                   ZonedDateTime eventTime, ZonedDateTime endTime,
                                                   long minutesUntilEnd) {
        return builder()
                .type(Type.WARNING_SESSION_END)
                .sessionId(sessionId)
                .scheduleName(scheduleName)
                .eventTime(eventTime)
                .scheduledTime(endTime)
                .message("Session will end in " + minutesUntilEnd + " minutes")
                .build();
    }

    /**
     * Create a WARNING_RESET event.
     */
    public static ScheduleEvent warningReset(String sessionId, String scheduleName,
                                              ZonedDateTime eventTime, ZonedDateTime resetTime,
                                              long minutesUntilReset) {
        return builder()
                .type(Type.WARNING_RESET)
                .sessionId(sessionId)
                .scheduleName(scheduleName)
                .eventTime(eventTime)
                .scheduledTime(resetTime)
                .message("Session reset in " + minutesUntilReset + " minutes")
                .build();
    }

    // Getters

    public Type getType() {
        return type;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getScheduleName() {
        return scheduleName;
    }

    public ZonedDateTime getEventTime() {
        return eventTime;
    }

    public ZonedDateTime getScheduledTime() {
        return scheduledTime;
    }

    public String getMessage() {
        return message;
    }

    public boolean isStartEvent() {
        return type == Type.SESSION_START;
    }

    public boolean isEndEvent() {
        return type == Type.SESSION_END;
    }

    public boolean isResetEvent() {
        return type == Type.RESET_DUE;
    }

    public boolean isWarning() {
        return type == Type.WARNING_SESSION_END || type == Type.WARNING_RESET;
    }

    @Override
    public String toString() {
        return "ScheduleEvent{" +
                "type=" + type +
                ", sessionId='" + sessionId + '\'' +
                ", scheduleName='" + scheduleName + '\'' +
                ", eventTime=" + eventTime +
                ", scheduledTime=" + scheduledTime +
                ", message='" + message + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScheduleEvent that = (ScheduleEvent) o;
        return type == that.type &&
                Objects.equals(sessionId, that.sessionId) &&
                Objects.equals(scheduleName, that.scheduleName) &&
                Objects.equals(eventTime, that.eventTime) &&
                Objects.equals(scheduledTime, that.scheduledTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, sessionId, scheduleName, eventTime, scheduledTime);
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Type type;
        private String sessionId;
        private String scheduleName;
        private ZonedDateTime eventTime;
        private ZonedDateTime scheduledTime;
        private String message;

        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder scheduleName(String scheduleName) {
            this.scheduleName = scheduleName;
            return this;
        }

        public Builder eventTime(ZonedDateTime eventTime) {
            this.eventTime = eventTime;
            return this;
        }

        public Builder scheduledTime(ZonedDateTime scheduledTime) {
            this.scheduledTime = scheduledTime;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public ScheduleEvent build() {
            return new ScheduleEvent(this);
        }
    }
}
