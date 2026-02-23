package com.omnibridge.persistence.aeron;

import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.aeron.codec.*;
import com.omnibridge.persistence.chronicle.ChronicleLogStore;
import io.aeron.Publication;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side handler for replay requests. Reads from a local ChronicleLogStore
 * and publishes replay entries back to the requesting engine.
 */
public class ReplayHandler {

    private static final Logger log = LoggerFactory.getLogger(ReplayHandler.class);
    private static final int MAX_OFFER_RETRIES = 100;

    private final ChronicleLogStore store;
    private final Publication replayPublication;
    private final MutableDirectBuffer encodeBuffer = new ExpandableDirectByteBuffer(8192);
    private final IdleStrategy offerIdleStrategy = new SleepingIdleStrategy(100_000); // 100us

    public ReplayHandler(ChronicleLogStore store, Publication replayPublication) {
        this.store = store;
        this.replayPublication = replayPublication;
    }

    public void handleReplayRequest(DirectBuffer buffer, int offset) {
        long correlationId = ReplayRequestCodec.decodeCorrelationId(buffer, offset);
        byte direction = ReplayRequestCodec.decodeDirection(buffer, offset);
        int fromSeqNum = ReplayRequestCodec.decodeFromSeqNum(buffer, offset);
        int toSeqNum = ReplayRequestCodec.decodeToSeqNum(buffer, offset);
        long fromTimestamp = ReplayRequestCodec.decodeFromTimestamp(buffer, offset);
        long toTimestamp = ReplayRequestCodec.decodeToTimestamp(buffer, offset);
        long maxEntries = ReplayRequestCodec.decodeMaxEntries(buffer, offset);
        String streamName = ReplayRequestCodec.decodeStreamName(buffer, offset);

        log.info("Handling replay request: correlationId={}, stream={}, direction={}, " +
                        "fromSeq={}, toSeq={}, fromTs={}, toTs={}, maxEntries={}",
                correlationId, streamName.isEmpty() ? "ALL" : streamName,
                direction, fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries);

        LogEntry.Direction dirFilter = mapDirection(direction);
        String stream = streamName.isEmpty() ? null : streamName;
        AtomicLong entryCount = new AtomicLong(0);

        try {
            if (fromTimestamp > 0 || toTimestamp > 0) {
                store.replayByTime(stream, dirFilter, fromTimestamp, toTimestamp, entry -> {
                    if (maxEntries > 0 && entryCount.get() >= maxEntries) {
                        return false;
                    }
                    publishReplayEntry(correlationId, entry, entryCount.incrementAndGet());
                    return true;
                });
            } else {
                store.replay(stream, dirFilter, fromSeqNum, toSeqNum, entry -> {
                    if (maxEntries > 0 && entryCount.get() >= maxEntries) {
                        return false;
                    }
                    publishReplayEntry(correlationId, entry, entryCount.incrementAndGet());
                    return true;
                });
            }

            publishReplayComplete(correlationId, entryCount.get(), MessageTypes.STATUS_SUCCESS, null);
            log.info("Replay complete: correlationId={}, entries={}", correlationId, entryCount.get());

        } catch (Exception e) {
            log.error("Replay failed: correlationId={}", correlationId, e);
            publishReplayComplete(correlationId, entryCount.get(),
                    MessageTypes.STATUS_ERROR, e.getMessage());
        }
    }

    public void handleStreamInfoRequest(DirectBuffer buffer, int offset) {
        long correlationId = StreamInfoRequestCodec.decodeCorrelationId(buffer, offset);
        String streamName = StreamInfoRequestCodec.decodeStreamName(buffer, offset);

        String stream = streamName.isEmpty() ? null : streamName;
        long entryCount = store.getEntryCount(stream);

        LogEntry lastInbound = stream != null
                ? store.getLatest(stream, LogEntry.Direction.INBOUND) : null;
        LogEntry lastOutbound = stream != null
                ? store.getLatest(stream, LogEntry.Direction.OUTBOUND) : null;

        int lastInSeq = lastInbound != null ? lastInbound.getSequenceNumber() : 0;
        int lastOutSeq = lastOutbound != null ? lastOutbound.getSequenceNumber() : 0;
        long lastTimestamp = Math.max(
                lastInbound != null ? lastInbound.getTimestamp() : 0,
                lastOutbound != null ? lastOutbound.getTimestamp() : 0
        );

        int length = StreamInfoResponseCodec.encode(encodeBuffer, 0, correlationId,
                entryCount, lastInSeq, lastOutSeq, lastTimestamp,
                stream != null ? stream : "");
        offerWithRetry(encodeBuffer, 0, length);
    }

    private void publishReplayEntry(long correlationId, LogEntry entry, long entryIndex) {
        int length = ReplayEntryCodec.encode(encodeBuffer, 0, correlationId, entry, entryIndex);
        offerWithRetry(encodeBuffer, 0, length);
    }

    private void publishReplayComplete(long correlationId, long entryCount,
                                       byte status, String errorMessage) {
        int length = ReplayCompleteCodec.encode(encodeBuffer, 0, correlationId,
                entryCount, status, errorMessage);
        offerWithRetry(encodeBuffer, 0, length);
    }

    private void offerWithRetry(DirectBuffer buffer, int offset, int length) {
        for (int i = 0; i < MAX_OFFER_RETRIES; i++) {
            long result = replayPublication.offer(buffer, offset, length);
            if (result > 0) {
                return;
            }
            if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                log.error("Replay publication not available: result={}", result);
                return;
            }
            offerIdleStrategy.idle();
        }
        log.warn("Failed to publish replay message after {} retries", MAX_OFFER_RETRIES);
    }

    private static LogEntry.Direction mapDirection(byte direction) {
        return switch (direction) {
            case MessageTypes.DIRECTION_INBOUND -> LogEntry.Direction.INBOUND;
            case MessageTypes.DIRECTION_OUTBOUND -> LogEntry.Direction.OUTBOUND;
            default -> null; // BOTH
        };
    }
}
