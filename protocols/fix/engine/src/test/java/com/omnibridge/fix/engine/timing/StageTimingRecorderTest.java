package com.omnibridge.fix.engine.timing;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import static com.omnibridge.fix.engine.timing.StageTimingRecorder.*;
import static org.junit.jupiter.api.Assertions.*;

class StageTimingRecorderTest {

    @Test
    void capacityRoundsUpToPowerOfTwo() {
        assertEquals(128, new StageTimingRecorder(100).getCapacity());
        assertEquals(64, new StageTimingRecorder(64).getCapacity());
        assertThrows(IllegalArgumentException.class, () -> new StageTimingRecorder(0));
    }

    @Test
    void keepsTheMostRecentRowsWhenTheRingWraps() {
        StageTimingRecorder r = new StageTimingRecorder(4);
        for (int i = 0; i < 10; i++) {
            r.recordSend(i, i + 1, i + 2, i + 3, i + 4, i + 5);
            r.recordReceive(i, i + 1, i + 2, i + 3, i + 4);
        }
        assertEquals(10, r.getSendCount());
        assertEquals(10, r.getReceiveCount());
        // Oldest kept row is row 6 of 10
        assertEquals(6, r.sendStamp(0, SEND_CLAIM));
        assertEquals(9 + 5, r.sendStamp(3, SEND_WRITTEN));
        assertEquals(6, r.receiveStamp(0, RECV_READ));
        assertEquals(9 + 4, r.receiveStamp(3, RECV_DONE));
    }

    @Test
    void resetIsAppliedBeforeTheNextRow() {
        StageTimingRecorder r = new StageTimingRecorder(16);
        r.recordSend(1, 2, 3, 4, 5, 6);
        r.recordReceive(1, 2, 3, 4, 5);
        r.recordCommitCall(7);
        r.markReported();

        r.requestReset();
        assertFalse(r.isReported());
        assertEquals(0, r.getSendCount(), "a pending reset hides old rows");
        assertEquals(0, r.getReceiveCount());

        r.recordReceive(100, 101, 102, 103, 104);
        assertEquals(0, r.getSendCount());
        assertEquals(1, r.getReceiveCount());
        assertEquals(0, r.getCommitCallCount());
        assertEquals(100, r.receiveStamp(0, RECV_READ));
    }

    @Test
    void reportHistogramsHoldStageDeltas() {
        StageTimingRecorder r = new StageTimingRecorder(16);
        // claim 0, commit +1000, prepared +500, drain +2000, before write +300, written +4000
        r.recordSend(0, 1000, 1500, 3500, 3800, 7800);
        Histogram[] send = StageTimingReport.sendHistograms(r);
        assertEquals(SEND_STAGES, send.length);
        assertEquals(1000, send[0].getMaxValue(), 1000 * 0.001);
        assertEquals(500, send[1].getMaxValue(), 500 * 0.001);
        assertEquals(2000, send[2].getMaxValue(), 2000 * 0.001);
        assertEquals(300, send[3].getMaxValue(), 300 * 0.001);
        assertEquals(4000, send[4].getMaxValue(), 4000 * 0.001);
        assertEquals(7800, send[5].getMaxValue(), 7800 * 0.001);

        String text = StageTimingReport.format(r, "S1");
        assertTrue(text.contains("Stage timing [S1]"), text);
        assertTrue(text.contains("stage send.queue"), text);
        assertTrue(text.matches("(?s).*stage recv\\.total\\s+count=0.*"), text);
        assertFalse(text.contains("| p50"), "must not look like the runner's summary table rows");
    }
}
