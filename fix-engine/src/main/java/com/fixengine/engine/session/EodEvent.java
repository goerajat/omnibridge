package com.fixengine.engine.session;

import java.time.Instant;

/**
 * Represents an End of Day (EOD) event for a FIX session.
 * Contains information about the EOD trigger including previous sequence numbers.
 */
public class EodEvent {

    /**
     * Type of EOD event.
     */
    public enum Type {
        SCHEDULED,  // Triggered by scheduled EOD time
        MANUAL      // Triggered manually via API
    }

    private final String sessionId;
    private final Instant eventTime;
    private final Type type;
    private final int previousOutgoingSeqNum;
    private final int previousIncomingSeqNum;

    public EodEvent(String sessionId, Instant eventTime, Type type,
                    int previousOutgoingSeqNum, int previousIncomingSeqNum) {
        this.sessionId = sessionId;
        this.eventTime = eventTime;
        this.type = type;
        this.previousOutgoingSeqNum = previousOutgoingSeqNum;
        this.previousIncomingSeqNum = previousIncomingSeqNum;
    }

    /**
     * Get the session ID.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Get the event timestamp.
     */
    public Instant getEventTime() {
        return eventTime;
    }

    /**
     * Get the type of EOD event.
     */
    public Type getType() {
        return type;
    }

    /**
     * Get the outgoing sequence number before the reset.
     */
    public int getPreviousOutgoingSeqNum() {
        return previousOutgoingSeqNum;
    }

    /**
     * Get the incoming sequence number before the reset.
     */
    public int getPreviousIncomingSeqNum() {
        return previousIncomingSeqNum;
    }

    /**
     * Convert to JSON metadata string for persistence.
     */
    public String toMetadataJson() {
        return String.format(
            "{\"eventType\":\"EOD\",\"type\":\"%s\",\"previousOutSeq\":%d,\"previousInSeq\":%d}",
            type.name(), previousOutgoingSeqNum, previousIncomingSeqNum
        );
    }

    @Override
    public String toString() {
        return "EodEvent{" +
                "sessionId='" + sessionId + '\'' +
                ", eventTime=" + eventTime +
                ", type=" + type +
                ", previousOutgoingSeqNum=" + previousOutgoingSeqNum +
                ", previousIncomingSeqNum=" + previousIncomingSeqNum +
                '}';
    }
}
