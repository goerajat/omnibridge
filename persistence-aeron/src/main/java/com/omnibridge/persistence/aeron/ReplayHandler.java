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
 *
 * <p>Supports publisher-scoped replay: when a publisherId is specified in the request,
 * only streams prefixed with {@code "pub-{publisherId}/"} are replayed. The prefix
 * is stripped from stream names in replayed entries so the requesting engine receives
 * its original stream names.
 */
public class ReplayHandler {

    private static final Logger log = LoggerFactory.getLogger(ReplayHandler.class);
    private static final int MAX_OFFER_RETRIES = 100;

    private final ChronicleLogStore store;
    private final Publication replayPublication;
    private final long publisherId;
    private final MutableDirectBuffer encodeBuffer = new ExpandableDirectByteBuffer(8192);
    private final IdleStrategy offerIdleStrategy = new SleepingIdleStrategy(100_000); // 100us

    public ReplayHandler(ChronicleLogStore store, Publication replayPublication) {
        this(store, replayPublication, 0);
    }

    public ReplayHandler(ChronicleLogStore store, Publication replayPublication, long publisherId) {
        this.store = store;
        this.replayPublication = replayPublication;
        this.publisherId = publisherId;
    }

    public long getPublisherId() {
        return publisherId;
    }

    /**
     * Handle a replay request with publisher-scoped filtering.
     */
    public void handleReplayRequest(DirectBuffer buffer, int offset, long requestPublisherId) {
        long correlationId = ReplayRequestCodec.decodeCorrelationId(buffer, offset);
        byte direction = ReplayRequestCodec.decodeDirection(buffer, offset);
        int fromSeqNum = ReplayRequestCodec.decodeFromSeqNum(buffer, offset);
        int toSeqNum = ReplayRequestCodec.decodeToSeqNum(buffer, offset);
        long fromTimestamp = ReplayRequestCodec.decodeFromTimestamp(buffer, offset);
        long toTimestamp = ReplayRequestCodec.decodeToTimestamp(buffer, offset);
        long maxEntries = ReplayRequestCodec.decodeMaxEntries(buffer, offset);
        String streamName = ReplayRequestCodec.decodeStreamName(buffer, offset);

        log.info("Handling replay request: correlationId={}, stream={}, direction={}, " +
                        "fromSeq={}, toSeq={}, fromTs={}, toTs={}, maxEntries={}, publisherId={}",
                correlationId, streamName.isEmpty() ? "ALL" : streamName,
                direction, fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries,
                requestPublisherId);

        LogEntry.Direction dirFilter = mapDirection(direction);
        AtomicLong entryCount = new AtomicLong(0);

        try {
            if (requestPublisherId > 0) {
                // Publisher-scoped replay: replay only streams for this publisher
                replayForPublisher(correlationId, requestPublisherId, streamName, dirFilter,
                        fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries, entryCount);
            } else {
                // Legacy: replay all streams (no publisher scoping)
                replayAllPublishers(correlationId, streamName, dirFilter,
                        fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries, entryCount);
            }

            publishReplayComplete(correlationId, entryCount.get(), MessageTypes.STATUS_SUCCESS, null);
            log.info("Replay complete: correlationId={}, entries={}", correlationId, entryCount.get());

        } catch (Exception e) {
            log.error("Replay failed: correlationId={}", correlationId, e);
            publishReplayComplete(correlationId, entryCount.get(),
                    MessageTypes.STATUS_ERROR, e.getMessage());
        }
    }

    /**
     * Handle a replay request (backward compatible — no publisher ID).
     */
    public void handleReplayRequest(DirectBuffer buffer, int offset) {
        handleReplayRequest(buffer, offset, 0);
    }

    private void replayForPublisher(long correlationId, long publisherId, String streamName,
                                     LogEntry.Direction dirFilter, int fromSeqNum, int toSeqNum,
                                     long fromTimestamp, long toTimestamp, long maxEntries,
                                     AtomicLong entryCount) {
        String prefix = "pub-" + publisherId + "/";

        if (streamName != null && !streamName.isEmpty()) {
            // Specific stream — add the publisher prefix
            String prefixedStream = prefix + streamName;
            replayStream(correlationId, prefixedStream, prefix, dirFilter,
                    fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries, entryCount);
        } else {
            // All streams for this publisher — find streams with matching prefix
            for (String storeStream : store.getStreamNames()) {
                if (storeStream.startsWith(prefix)) {
                    if (maxEntries > 0 && entryCount.get() >= maxEntries) {
                        break;
                    }
                    replayStream(correlationId, storeStream, prefix, dirFilter,
                            fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries, entryCount);
                }
            }
        }
    }

    private void replayAllPublishers(long correlationId, String streamName,
                                      LogEntry.Direction dirFilter, int fromSeqNum, int toSeqNum,
                                      long fromTimestamp, long toTimestamp, long maxEntries,
                                      AtomicLong entryCount) {
        if (streamName != null && !streamName.isEmpty()) {
            // Try exact match first (for backward compat with non-prefixed stores)
            replayStream(correlationId, streamName, null, dirFilter,
                    fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries, entryCount);

            // Also replay any publisher-prefixed versions of this stream
            for (String storeStream : store.getStreamNames()) {
                if (storeStream.endsWith("/" + streamName) && storeStream.startsWith("pub-")) {
                    if (maxEntries > 0 && entryCount.get() >= maxEntries) {
                        break;
                    }
                    String prefix = storeStream.substring(0, storeStream.indexOf('/') + 1);
                    replayStream(correlationId, storeStream, prefix, dirFilter,
                            fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries, entryCount);
                }
            }
        } else {
            // All streams
            for (String storeStream : store.getStreamNames()) {
                if (maxEntries > 0 && entryCount.get() >= maxEntries) {
                    break;
                }
                String prefix = null;
                if (storeStream.startsWith("pub-") && storeStream.contains("/")) {
                    prefix = storeStream.substring(0, storeStream.indexOf('/') + 1);
                }
                replayStream(correlationId, storeStream, prefix, dirFilter,
                        fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries, entryCount);
            }
        }
    }

    private void replayStream(long correlationId, String storeStream, String prefixToStrip,
                               LogEntry.Direction dirFilter, int fromSeqNum, int toSeqNum,
                               long fromTimestamp, long toTimestamp, long maxEntries,
                               AtomicLong entryCount) {
        if (fromTimestamp > 0 || toTimestamp > 0) {
            store.replayByTime(storeStream, dirFilter, fromTimestamp, toTimestamp, entry -> {
                if (maxEntries > 0 && entryCount.get() >= maxEntries) {
                    return false;
                }
                LogEntry unprefixed = stripPrefix(entry, prefixToStrip);
                publishReplayEntry(correlationId, unprefixed, entryCount.incrementAndGet());
                return true;
            });
        } else {
            store.replay(storeStream, dirFilter, fromSeqNum, toSeqNum, entry -> {
                if (maxEntries > 0 && entryCount.get() >= maxEntries) {
                    return false;
                }
                LogEntry unprefixed = stripPrefix(entry, prefixToStrip);
                publishReplayEntry(correlationId, unprefixed, entryCount.incrementAndGet());
                return true;
            });
        }
    }

    /**
     * Strip the publisher prefix from an entry's stream name for replay.
     */
    private static LogEntry stripPrefix(LogEntry entry, String prefix) {
        if (prefix == null || entry.getStreamName() == null
                || !entry.getStreamName().startsWith(prefix)) {
            return entry;
        }
        String originalStreamName = entry.getStreamName().substring(prefix.length());
        return LogEntry.builder()
                .timestamp(entry.getTimestamp())
                .direction(entry.getDirection())
                .sequenceNumber(entry.getSequenceNumber())
                .streamName(originalStreamName)
                .metadata(entry.getMetadata())
                .rawMessage(entry.getRawMessage())
                .build();
    }

    public void handleStreamInfoRequest(DirectBuffer buffer, int offset, long requestPublisherId) {
        long correlationId = StreamInfoRequestCodec.decodeCorrelationId(buffer, offset);
        String streamName = StreamInfoRequestCodec.decodeStreamName(buffer, offset);

        String stream;
        if (requestPublisherId > 0 && streamName != null && !streamName.isEmpty()) {
            stream = "pub-" + requestPublisherId + "/" + streamName;
        } else if (streamName != null && !streamName.isEmpty()) {
            stream = streamName;
        } else {
            stream = null;
        }

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

        // Return the original (unprefixed) stream name in the response
        String responseStream = (streamName != null && !streamName.isEmpty()) ? streamName : "";
        int length = StreamInfoResponseCodec.encode(encodeBuffer, 0, correlationId,
                entryCount, lastInSeq, lastOutSeq, lastTimestamp, responseStream);
        offerWithRetry(encodeBuffer, 0, length);
    }

    public void handleStreamInfoRequest(DirectBuffer buffer, int offset) {
        handleStreamInfoRequest(buffer, offset, 0);
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
