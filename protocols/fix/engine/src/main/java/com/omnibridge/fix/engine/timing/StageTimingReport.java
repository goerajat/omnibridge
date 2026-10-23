package com.omnibridge.fix.engine.timing;

import org.HdrHistogram.Histogram;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.omnibridge.fix.engine.timing.StageTimingRecorder.*;

/**
 * Turns a {@link StageTimingRecorder} into per-stage HdrHistograms and formats them.
 *
 * <p>Each line is {@code stage <name> mean=… p50=… p90=… p99=… p99.9=… max=…} in
 * microseconds, so scripts can pick out a stage by name. Means add up along a chain
 * (percentiles do not), which is what shows how much of the total each stage owns.</p>
 */
public final class StageTimingReport {

    private static final long HIGHEST_TRACKABLE_NANOS = TimeUnit.MINUTES.toNanos(1);

    /** Stage names and the stamps they span, send side. */
    private static final String[] SEND_NAMES = {
            "send.encode", "send.prepare", "send.queue", "send.persist", "send.write"};
    private static final String[] SEND_DESCRIPTIONS = {
            "claim -> commit (application encodes fields)",
            "commit -> prepared (SendingTime, BodyLength, CheckSum)",
            "prepared -> drained (ring commit, wait for the event loop)",
            "drained -> before write (MsgSeqNum stamp, outbound log/persistence)",
            "before write -> written (socket write)"};
    private static final String[] RECV_NAMES = {
            "recv.frame", "recv.parse", "recv.session", "recv.callback"};
    private static final String[] RECV_DESCRIPTIONS = {
            "read -> framed (framing and checksum, after earlier messages of the same read)",
            "framed -> parsed (field index)",
            "parsed -> dispatch (inbound persistence, CompID and sequence checks)",
            "dispatch -> done (message listeners)"};

    private StageTimingReport() {
    }

    /**
     * Histograms of each send stage, then the whole chain ({@code send.total}), in nanoseconds.
     */
    public static Histogram[] sendHistograms(StageTimingRecorder recorder) {
        int rows = (int) Math.min(recorder.getSendCount(), recorder.getCapacity());
        Histogram[] h = newHistograms(SEND_STAGES);
        for (int r = 0; r < rows; r++) {
            for (int s = 0; s < SEND_STAGES - 1; s++) {
                record(h[s], recorder.sendStamp(r, s + 1) - recorder.sendStamp(r, s));
            }
            record(h[SEND_STAGES - 1], recorder.sendStamp(r, SEND_WRITTEN) - recorder.sendStamp(r, SEND_CLAIM));
        }
        return h;
    }

    /**
     * Histograms of each receive stage, then the whole chain ({@code recv.total}), in nanoseconds.
     */
    public static Histogram[] receiveHistograms(StageTimingRecorder recorder) {
        int rows = (int) Math.min(recorder.getReceiveCount(), recorder.getCapacity());
        Histogram[] h = newHistograms(RECV_STAGES);
        for (int r = 0; r < rows; r++) {
            for (int s = 0; s < RECV_STAGES - 1; s++) {
                record(h[s], recorder.receiveStamp(r, s + 1) - recorder.receiveStamp(r, s));
            }
            record(h[RECV_STAGES - 1], recorder.receiveStamp(r, RECV_DONE) - recorder.receiveStamp(r, RECV_READ));
        }
        return h;
    }

    public static Histogram commitCallHistogram(StageTimingRecorder recorder) {
        int rows = (int) Math.min(recorder.getCommitCallCount(), recorder.getCapacity());
        Histogram h = new Histogram(HIGHEST_TRACKABLE_NANOS, 3);
        for (int r = 0; r < rows; r++) {
            record(h, recorder.commitCall(r));
        }
        return h;
    }

    /**
     * Format the report. Every line ends with a line separator.
     *
     * @param label shown in the heading, e.g. the session ID
     */
    public static String format(StageTimingRecorder recorder, String label) {
        Histogram[] send = sendHistograms(recorder);
        Histogram[] recv = receiveHistograms(recorder);
        Histogram commitCall = commitCallHistogram(recorder);

        StringBuilder sb = new StringBuilder(2048);
        String nl = System.lineSeparator();
        sb.append(String.format(Locale.ROOT,
                "  Stage timing [%s], us (send rows=%d, receive rows=%d, kept up to %d each):",
                label, send[0].getTotalCount(), recv[0].getTotalCount(), recorder.getCapacity())).append(nl);
        for (int i = 0; i < SEND_NAMES.length; i++) {
            line(sb, SEND_NAMES[i], send[i], SEND_DESCRIPTIONS[i]);
        }
        line(sb, "send.total", send[SEND_STAGES - 1], "claim -> written");
        line(sb, "send.commit_call", commitCall, "time the sender spends in ring commit + selector wakeup");
        for (int i = 0; i < RECV_NAMES.length; i++) {
            line(sb, RECV_NAMES[i], recv[i], RECV_DESCRIPTIONS[i]);
        }
        line(sb, "recv.total", recv[RECV_STAGES - 1], "read -> done");
        return sb.toString();
    }

    private static void line(StringBuilder sb, String name, Histogram h, String description) {
        if (h.getTotalCount() == 0) {
            sb.append(String.format(Locale.ROOT, "  stage %-16s count=0  # %s", name, description));
        } else {
            sb.append(String.format(Locale.ROOT,
                    "  stage %-16s mean=%.2f p50=%.2f p90=%.2f p99=%.2f p99.9=%.2f max=%.2f  # %s",
                    name, h.getMean() / 1000.0,
                    h.getValueAtPercentile(50) / 1000.0, h.getValueAtPercentile(90) / 1000.0,
                    h.getValueAtPercentile(99) / 1000.0, h.getValueAtPercentile(99.9) / 1000.0,
                    h.getMaxValue() / 1000.0, description));
        }
        sb.append(System.lineSeparator());
    }

    private static Histogram[] newHistograms(int n) {
        Histogram[] h = new Histogram[n];
        for (int i = 0; i < n; i++) {
            h[i] = new Histogram(HIGHEST_TRACKABLE_NANOS, 3);
        }
        return h;
    }

    private static void record(Histogram h, long nanos) {
        h.recordValue(Math.max(0, Math.min(nanos, HIGHEST_TRACKABLE_NANOS)));
    }
}
