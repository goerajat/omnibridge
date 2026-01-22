package com.fixengine.config.schedule;

/**
 * Listener interface for schedule events.
 *
 * <p>Implementations receive notifications when:</p>
 * <ul>
 *   <li>A session should start (connect)</li>
 *   <li>A session should end (disconnect)</li>
 *   <li>A session reset is due (EOD)</li>
 *   <li>Warnings before end/reset</li>
 * </ul>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * scheduler.addListener(new ScheduleListener() {
 *     @Override
 *     public void onScheduleEvent(ScheduleEvent event) {
 *         switch (event.getType()) {
 *             case SESSION_START -> connectSession(event.getSessionId());
 *             case SESSION_END -> disconnectSession(event.getSessionId());
 *             case RESET_DUE -> performReset(event.getSessionId());
 *             case WARNING_SESSION_END -> log.warn("Session {} ending soon", event.getSessionId());
 *             case WARNING_RESET -> log.warn("Reset due soon for {}", event.getSessionId());
 *         }
 *     }
 * });
 * }</pre>
 *
 * <p>For protocol-specific handling (e.g., FIX EOD), the listener can delegate to
 * the appropriate session/engine methods.</p>
 */
@FunctionalInterface
public interface ScheduleListener {

    /**
     * Called when a schedule event occurs.
     *
     * <p>This method is called from the scheduler's timer thread.
     * Implementations should be quick to avoid blocking the scheduler.
     * Long-running operations should be delegated to another thread.</p>
     *
     * @param event the schedule event
     */
    void onScheduleEvent(ScheduleEvent event);

    /**
     * Combine this listener with another to create a composite listener.
     *
     * @param other the other listener
     * @return a combined listener that notifies both
     */
    default ScheduleListener andThen(ScheduleListener other) {
        return event -> {
            this.onScheduleEvent(event);
            other.onScheduleEvent(event);
        };
    }

    /**
     * Create a listener that only handles specific event types.
     *
     * @param listener the listener to wrap
     * @param types the event types to handle
     * @return a filtered listener
     */
    static ScheduleListener forTypes(ScheduleListener listener, ScheduleEvent.Type... types) {
        java.util.Set<ScheduleEvent.Type> typeSet = java.util.EnumSet.noneOf(ScheduleEvent.Type.class);
        java.util.Collections.addAll(typeSet, types);
        return event -> {
            if (typeSet.contains(event.getType())) {
                listener.onScheduleEvent(event);
            }
        };
    }

    /**
     * Create a listener that only handles events for a specific session.
     *
     * @param listener the listener to wrap
     * @param sessionId the session ID to filter for
     * @return a filtered listener
     */
    static ScheduleListener forSession(ScheduleListener listener, String sessionId) {
        return event -> {
            if (sessionId.equals(event.getSessionId())) {
                listener.onScheduleEvent(event);
            }
        };
    }
}
