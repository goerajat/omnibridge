package com.omnibridge.apps.fix.initiator;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ArrayLatencyTrackerTest {

    @Test
    void parsesTheNumericSuffix() {
        assertEquals(123, ArrayLatencyTracker.parseIndex("TEST123"));
        assertEquals(0, ArrayLatencyTracker.parseIndex("WARMUP0"));
        assertEquals(7, ArrayLatencyTracker.parseIndex(new StringBuilder("X7")));
        assertEquals(-1, ArrayLatencyTracker.parseIndex("TEST"));
        assertEquals(-1, ArrayLatencyTracker.parseIndex(null));
        assertEquals(-1, ArrayLatencyTracker.parseIndex("T12345678901"), "too long for an int");
    }

    @Test
    void measuresFromIntendedAndActualSendTimes() throws Exception {
        ArrayLatencyTracker tracker = new ArrayLatencyTracker(10);
        tracker.reset(2, "TEST");

        // Order 0 left on time; order 1 left 30 us late
        tracker.recordSend(0, 1_000_000, 1_000_000);
        tracker.recordSend(1, 2_000_000, 2_030_000);
        tracker.recordResponse("TEST0", 1_050_000);
        tracker.recordResponse("TEST1", 2_080_000);

        assertTrue(tracker.await(1, TimeUnit.SECONDS));
        assertEquals(2, tracker.getRecordedCount());
        assertEquals(0, tracker.getPendingOrderCount());
        assertEquals(50_000, tracker.getLatencies()[0]);
        assertEquals(50_000, tracker.getLatencies()[1]);
        assertEquals(50_000, tracker.getActualHistogram().getMaxValue(), 50);
        assertEquals(80_000, tracker.getIntendedHistogram().getMaxValue(), 80);
        assertEquals(30_000, tracker.getSendSlipHistogram().getMaxValue(), 30);
    }

    @Test
    void ignoresDuplicateUnknownAndForeignResponses() {
        ArrayLatencyTracker tracker = new ArrayLatencyTracker(10);
        tracker.reset(1, "TEST");
        tracker.recordSend(0, 100, 100);

        tracker.recordResponse("TEST5", 200);      // never sent
        tracker.recordResponse("WARMUP0", 200);    // late response from the warmup phase
        tracker.recordResponse("TEST", 200);       // no index
        tracker.recordResponse("TEST0", 300);
        tracker.recordResponse("TEST0", 400);      // duplicate

        assertEquals(1, tracker.getRecordedCount());
        assertEquals(200, tracker.getLatencies()[0]);
        assertEquals(0, tracker.getPendingOrderCount());
    }

    @Test
    void resetClearsHistogramsAndState() {
        ArrayLatencyTracker tracker = new ArrayLatencyTracker(4);
        tracker.reset(1, "WARMUP");
        tracker.recordSend(0, 100, 100);
        tracker.recordResponse("WARMUP0", 300);

        tracker.reset(1, "TEST");
        assertEquals(0, tracker.getRecordedCount());
        assertEquals(0, tracker.getActualHistogram().getTotalCount());
        tracker.recordResponse("TEST0", 500);
        assertEquals(0, tracker.getRecordedCount(), "index 0 was not sent in this run");
    }
}
