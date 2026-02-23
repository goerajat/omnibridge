package com.omnibridge.persistence.aeron;

import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.aeron.codec.*;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side replay request handler. Sends replay requests to a remote subscriber
 * and writes received entries to the local store.
 */
public class ReplayClient {

    private static final Logger log = LoggerFactory.getLogger(ReplayClient.class);

    private final AeronTransport transport;
    private final long timeoutMs;
    private final MutableDirectBuffer requestBuffer = new ExpandableDirectByteBuffer(256);
    private long nextCorrelationId = 1;

    public ReplayClient(AeronTransport transport, long timeoutMs) {
        this.transport = transport;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Request replay from the primary subscriber and write entries to the local store.
     *
     * @param localStore the local store to populate
     * @param streamName the stream to replay (null for all)
     * @param direction direction filter
     * @param fromSeqNum starting sequence number (0 for none)
     * @param toSeqNum ending sequence number (0 for none)
     * @return the number of entries replayed
     */
    public long requestReplay(LogStore localStore, String streamName,
                              byte direction, int fromSeqNum, int toSeqNum) {
        return requestReplay(localStore, streamName, direction, fromSeqNum, toSeqNum, 0, 0, 0, 0);
    }

    /**
     * Request replay with full filtering options.
     */
    public long requestReplay(LogStore localStore, String streamName,
                              byte direction, int fromSeqNum, int toSeqNum,
                              long fromTimestamp, long toTimestamp, long maxEntries) {
        return requestReplay(localStore, streamName, direction, fromSeqNum, toSeqNum,
                fromTimestamp, toTimestamp, maxEntries, 0);
    }

    /**
     * Request replay with full filtering options and publisher-scoped replay.
     *
     * @param publisherId the publisher ID to scope replay to (0 = all publishers)
     */
    public long requestReplay(LogStore localStore, String streamName,
                              byte direction, int fromSeqNum, int toSeqNum,
                              long fromTimestamp, long toTimestamp, long maxEntries,
                              long publisherId) {
        long correlationId = nextCorrelationId++;

        int length = ReplayRequestCodec.encode(requestBuffer, 0, correlationId, direction,
                fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, maxEntries, streamName,
                publisherId);

        // Send to primary subscriber (index 0)
        transport.publishControlTo(0, requestBuffer, 0, length);
        log.info("Sent replay request: correlationId={}, stream={}, fromSeq={}, toSeq={}, publisherId={}",
                correlationId, streamName, fromSeqNum, toSeqNum, publisherId);

        // Poll for responses
        return pollReplayResponses(localStore, correlationId);
    }

    private long pollReplayResponses(LogStore localStore, long correlationId) {
        Subscription replaySub = transport.getReplaySubscription();
        IdleStrategy idleStrategy = transport.createIdleStrategy();

        AtomicLong entryCount = new AtomicLong(0);
        AtomicBoolean complete = new AtomicBoolean(false);

        FragmentHandler handler = new FragmentAssembler((buffer, offset, length, header) ->
                handleReplayFragment(buffer, offset, localStore, correlationId, entryCount, complete));

        long deadline = System.currentTimeMillis() + timeoutMs;

        while (!complete.get() && System.currentTimeMillis() < deadline) {
            int fragments = replaySub.poll(handler, 256);
            idleStrategy.idle(fragments);
        }

        if (!complete.get()) {
            log.warn("Replay request {} timed out after {}ms with {} entries received",
                    correlationId, timeoutMs, entryCount.get());
        }

        return entryCount.get();
    }

    private void handleReplayFragment(DirectBuffer buffer, int offset,
                                      LogStore localStore, long expectedCorrelationId,
                                      AtomicLong entryCount, AtomicBoolean complete) {
        int templateId = AeronMessageHeader.readTemplateId(buffer, offset);

        switch (templateId) {
            case MessageTypes.REPLAY_ENTRY -> {
                long corrId = ReplayEntryCodec.decodeCorrelationId(buffer, offset);
                if (corrId == expectedCorrelationId) {
                    LogEntry entry = ReplayEntryCodec.decode(buffer, offset);
                    localStore.write(entry);
                    entryCount.incrementAndGet();
                }
            }
            case MessageTypes.REPLAY_COMPLETE -> {
                long corrId = ReplayCompleteCodec.decodeCorrelationId(buffer, offset);
                if (corrId == expectedCorrelationId) {
                    byte status = ReplayCompleteCodec.decodeStatus(buffer, offset);
                    long count = ReplayCompleteCodec.decodeEntryCount(buffer, offset);
                    if (status == MessageTypes.STATUS_SUCCESS) {
                        log.info("Replay complete: correlationId={}, entries={}", corrId, count);
                    } else {
                        String error = ReplayCompleteCodec.decodeErrorMessage(buffer, offset);
                        log.warn("Replay ended with status {}: correlationId={}, entries={}, error={}",
                                status, corrId, count, error);
                    }
                    complete.set(true);
                }
            }
        }
    }
}
