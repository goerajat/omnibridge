package com.omnibridge.mcp.tools;

import com.omnibridge.persistence.LogCallback;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogReader;
import com.omnibridge.persistence.LogStore;

import com.omnibridge.config.ComponentState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple in-memory LogStore for unit tests.
 */
class InMemoryLogStore implements LogStore {

    @Override public void initialize() {}
    @Override public void startActive() {}
    @Override public void startStandby() {}
    @Override public void becomeActive() {}
    @Override public void becomeStandby() {}
    @Override public void stop() {}
    @Override public String getName() { return "in-memory-test"; }
    @Override public ComponentState getState() { return ComponentState.ACTIVE; }


    private final Map<String, List<LogEntry>> streams = new ConcurrentHashMap<>();

    void addEntry(LogEntry entry) {
        streams.computeIfAbsent(entry.getStreamName(), k -> new CopyOnWriteArrayList<>()).add(entry);
    }

    @Override
    public long write(LogEntry entry) {
        addEntry(entry);
        return streams.get(entry.getStreamName()).size() - 1;
    }

    @Override
    public long replay(String streamName, LogEntry.Direction direction,
                        int fromSeqNum, int toSeqNum, LogCallback callback) {
        long count = 0;
        Collection<String> names = streamName != null ? List.of(streamName) : streams.keySet();
        for (String name : names) {
            List<LogEntry> entries = streams.getOrDefault(name, List.of());
            for (LogEntry entry : entries) {
                if (direction != null && entry.getDirection() != direction) continue;
                if (fromSeqNum > 0 && entry.getSequenceNumber() < fromSeqNum) continue;
                if (toSeqNum > 0 && entry.getSequenceNumber() > toSeqNum) continue;
                count++;
                if (!callback.onEntry(entry)) return count;
            }
        }
        return count;
    }

    @Override
    public long replayByTime(String streamName, LogEntry.Direction direction,
                              long fromTimestamp, long toTimestamp, LogCallback callback) {
        long count = 0;
        Collection<String> names = streamName != null ? List.of(streamName) : streams.keySet();
        for (String name : names) {
            List<LogEntry> entries = streams.getOrDefault(name, List.of());
            for (LogEntry entry : entries) {
                if (direction != null && entry.getDirection() != direction) continue;
                if (fromTimestamp > 0 && entry.getTimestamp() < fromTimestamp) continue;
                if (toTimestamp > 0 && entry.getTimestamp() > toTimestamp) continue;
                count++;
                if (!callback.onEntry(entry)) return count;
            }
        }
        return count;
    }

    @Override
    public LogEntry getLatest(String streamName, LogEntry.Direction direction) {
        List<LogEntry> entries = streams.getOrDefault(streamName, List.of());
        LogEntry latest = null;
        for (LogEntry entry : entries) {
            if (direction != null && entry.getDirection() != direction) continue;
            latest = entry;
        }
        return latest;
    }

    @Override
    public long getEntryCount(String streamName) {
        if (streamName == null) {
            return streams.values().stream().mapToLong(List::size).sum();
        }
        return streams.getOrDefault(streamName, List.of()).size();
    }

    @Override
    public Collection<String> getStreamNames() {
        return new TreeSet<>(streams.keySet());
    }

    @Override
    public void sync() {}

    @Override
    public String getStorePath() {
        return "in-memory";
    }

    @Override
    public LogReader createReader(String streamName, long startPosition) {
        throw new UnsupportedOperationException("Not needed for tool tests");
    }

    @Override
    public void close() {}
}
