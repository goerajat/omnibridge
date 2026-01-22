package com.fixengine.persistence;

/**
 * Callback interface for receiving log entries during replay.
 */
@FunctionalInterface
public interface LogCallback {

    /**
     * Called for each log entry during replay.
     *
     * @param entry the log entry
     * @return true to continue replay, false to stop
     */
    boolean onEntry(LogEntry entry);
}
