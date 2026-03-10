package com.omnibridge.persistence.aeron;

import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.persistence.*;
import com.omnibridge.persistence.aeron.codec.AeronMessageHeader;
import com.omnibridge.persistence.aeron.codec.CatchUpRequestCodec;
import com.omnibridge.persistence.aeron.codec.LogEntryCodec;
import com.omnibridge.persistence.aeron.codec.MessageTypes;
import io.aeron.FragmentAssembler;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import com.omnibridge.persistence.aeron.config.AeronLogStoreConfig;
import com.omnibridge.persistence.chronicle.ChronicleLogStore;
import com.omnibridge.persistence.config.PersistenceConfig;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * LogStore implementation that writes to a local ChronicleLogStore (write-through cache)
 * and replicates entries to remote subscribers via Aeron with SBE-encoded messages.
 *
 * <h3>Write Path (Hot)</h3>
 * <ol>
 *   <li>Entry written to local ChronicleLogStore (synchronous, fast)</li>
 *   <li>Entry SBE-encoded and published via Aeron (fire-and-forget)</li>
 *   <li>Returns immediately after local write</li>
 * </ol>
 *
 * <h3>Read Path (Local)</h3>
 * All reads delegate to the local ChronicleLogStore for zero network latency.
 *
 * <h3>Catch-Up Sync</h3>
 * A background thread monitors subscriber connections. When a subscriber
 * transitions from disconnected to connected, all local Chronicle entries
 * are replayed to it. This handles the case where the engine starts before
 * the remote store, or the remote store restarts.
 *
 * <h3>Recovery Path (Remote)</h3>
 * When engine restarts with empty/stale local cache, sends replay request to
 * remote subscriber to populate local store.
 */
public class AeronLogStore implements LogStore, Component {

    private static final Logger log = LoggerFactory.getLogger(AeronLogStore.class);

    private final ChronicleLogStore localStore;
    private final AeronTransport transport;
    private final AeronLogStoreConfig aeronConfig;
    private final ReplayClient replayClient;
    private final long publisherId;

    // Encode buffer for write path (not thread-safe - sized for typical max message)
    private final MutableDirectBuffer encodeBuffer = new ExpandableDirectByteBuffer(8192);

    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;
    private Thread catchUpThread;
    private volatile boolean catchUpRunning;

    // Timestamp of the last catch-up (either connection-based or CATCH_UP_REQUEST-based).
    // Used to suppress duplicate catch-ups when both mechanisms fire within a short window.
    private volatile long lastCatchUpTimeMs;

    // Pending CATCH_UP_REQUEST from the remote store, deferred for processing
    // after the subscriber connection has time to stabilize.
    private volatile CatchUpRequestCodec.DecodedRequest pendingCatchUpRequest;
    private volatile long pendingCatchUpRequestTimeMs;

    public AeronLogStore(PersistenceConfig persistenceConfig, AeronLogStoreConfig aeronConfig) {
        this.localStore = new ChronicleLogStore(persistenceConfig);
        this.aeronConfig = aeronConfig;
        this.publisherId = aeronConfig.getPublisherId();
        this.transport = new AeronTransport(aeronConfig);
        this.replayClient = new ReplayClient(transport, aeronConfig.getReplayTimeoutMs());
    }

    // ==================== Write Path ====================

    @Override
    public long write(LogEntry entry) {
        // 1. Local write (fast, synchronous)
        long position = localStore.write(entry);

        // 2. Publish to remote subscribers (fire-and-forget)
        if (transport.isRunning()) {
            int length = LogEntryCodec.encode(encodeBuffer, 0, entry, publisherId);
            transport.publishEntry(encodeBuffer, 0, length);
        }

        return position;
    }

    // ==================== Read Path (all delegate to local) ====================

    @Override
    public long replay(String streamName, LogEntry.Direction direction,
                       int fromSeqNum, int toSeqNum, LogCallback callback) {
        return localStore.replay(streamName, direction, fromSeqNum, toSeqNum, callback);
    }

    @Override
    public long replayByTime(String streamName, LogEntry.Direction direction,
                             long fromTimestamp, long toTimestamp, LogCallback callback) {
        return localStore.replayByTime(streamName, direction, fromTimestamp, toTimestamp, callback);
    }

    @Override
    public LogEntry getLatest(String streamName, LogEntry.Direction direction) {
        return localStore.getLatest(streamName, direction);
    }

    @Override
    public long getEntryCount(String streamName) {
        return localStore.getEntryCount(streamName);
    }

    @Override
    public Collection<String> getStreamNames() {
        return localStore.getStreamNames();
    }

    @Override
    public void sync() {
        localStore.sync();
    }

    @Override
    public String getStorePath() {
        return localStore.getStorePath();
    }

    @Override
    public Decoder getDecoder(String streamName) {
        return localStore.getDecoder(streamName);
    }

    @Override
    public void setDecoder(String streamName, Decoder decoder) {
        localStore.setDecoder(streamName, decoder);
    }

    @Override
    public LogReader createReader(String streamName, long startPosition) {
        LogReader localReader = localStore.createReader(streamName, startPosition);
        if (streamName != null) {
            return new AeronLogReader(localReader);
        } else {
            return new AeronAllStreamsLogReader(localReader);
        }
    }

    // ==================== Recovery ====================

    /**
     * Recover entries from a remote subscriber for the given stream.
     * Scoped to this engine's publisher ID so only this publisher's entries are recovered.
     *
     * @param streamName the stream to recover (null for all)
     * @return the number of entries recovered
     */
    public long recoverFromRemote(String streamName) {
        log.info("Starting recovery from remote for stream: {}, publisherId: {}",
                streamName != null ? streamName : "ALL", publisherId);
        return replayClient.requestReplay(localStore, streamName,
                MessageTypes.DIRECTION_BOTH, 0, 0, 0, 0, 0, publisherId);
    }

    /**
     * Recover entries from a remote subscriber with filtering.
     * Scoped to this engine's publisher ID.
     */
    public long recoverFromRemote(String streamName, byte direction,
                                  int fromSeqNum, int toSeqNum,
                                  long fromTimestamp, long toTimestamp) {
        return replayClient.requestReplay(localStore, streamName, direction,
                fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, 0, publisherId);
    }

    // ==================== Catch-Up Sync ====================

    private static final long CATCH_UP_SUPPRESS_MS = 3_000;
    private static final long CATCH_UP_REQUEST_DEFER_MS = 2_000;

    /**
     * Background loop that monitors subscriber connections and polls the replay
     * subscription for {@code CATCH_UP_REQUEST} messages from the remote store.
     *
     * <p>Two catch-up mechanisms cooperate:
     * <ol>
     *   <li><b>Connection-based</b>: when the engine detects a subscriber
     *       transitioning from disconnected to connected, it replays all local
     *       entries (full catch-up). If a pending {@code CATCH_UP_REQUEST}
     *       exists, only missing entries are replayed (incremental catch-up).</li>
     *   <li><b>Store-initiated</b>: the remote store sends a {@code CATCH_UP_REQUEST}
     *       on startup listing the last sequence number it has for each stream.
     *       The request is deferred for {@link #CATCH_UP_REQUEST_DEFER_MS} to
     *       allow the subscriber connection to stabilize before replaying.</li>
     * </ol>
     *
     * <p>A timestamp-based guard ({@link #lastCatchUpTimeMs}) prevents the
     * deferred handler from duplicating a connection-based catch-up.
     */
    private void catchUpLoop() {
        log.info("Catch-up sync thread started");
        FragmentHandler replayHandler = new FragmentAssembler(this::onCatchUpFragment);

        while (catchUpRunning) {
            try {
                int work = 0;

                // 1. Poll replay subscription for CATCH_UP_REQUEST (store → engine)
                work += transport.getReplaySubscription().poll(replayHandler, 10);

                // 2. Check subscriber connection transitions (engine → store)
                List<SubscriberConnection> subscribers = transport.getSubscribers();
                for (int i = 0; i < subscribers.size(); i++) {
                    SubscriberConnection sub = subscribers.get(i);
                    if (sub.checkAndClearCatchUpNeeded()) {
                        CatchUpRequestCodec.DecodedRequest pending = pendingCatchUpRequest;
                        pendingCatchUpRequest = null;
                        if (pending != null && !pending.streams().isEmpty()) {
                            syncIncrementalToSubscriber(sub, pending);
                        } else {
                            syncLocalToSubscriber(sub);
                        }
                        lastCatchUpTimeMs = System.currentTimeMillis();
                    }
                }

                // 3. Deferred CATCH_UP_REQUEST processing (when connection transition didn't fire)
                CatchUpRequestCodec.DecodedRequest pending = pendingCatchUpRequest;
                if (pending != null &&
                        System.currentTimeMillis() - pendingCatchUpRequestTimeMs > CATCH_UP_REQUEST_DEFER_MS) {
                    pendingCatchUpRequest = null;
                    if (System.currentTimeMillis() - lastCatchUpTimeMs > CATCH_UP_SUPPRESS_MS) {
                        processStoreCatchUpRequest(pending, subscribers);
                        lastCatchUpTimeMs = System.currentTimeMillis();
                    } else {
                        log.info("Skipping deferred CATCH_UP_REQUEST — recent catch-up handled {}ms ago",
                                System.currentTimeMillis() - lastCatchUpTimeMs);
                    }
                }

                if (work == 0) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in catch-up sync loop", e);
            }
        }
        log.info("Catch-up sync thread stopped");
    }

    /**
     * Handle fragments on the replay subscription during the catch-up loop.
     * Only processes CATCH_UP_REQUEST messages; other template IDs (REPLAY_ENTRY,
     * REPLAY_COMPLETE) are handled by ReplayClient during active recovery.
     */
    private void onCatchUpFragment(DirectBuffer buffer, int offset, int length,
                                   io.aeron.logbuffer.Header header) {
        int templateId = AeronMessageHeader.readTemplateId(buffer, offset);
        if (templateId == MessageTypes.CATCH_UP_REQUEST) {
            CatchUpRequestCodec.DecodedRequest request = CatchUpRequestCodec.decode(buffer, offset);
            long requestPublisherId = request.publisherId();

            if (requestPublisherId != 0 && requestPublisherId != publisherId) {
                log.debug("Ignoring CATCH_UP_REQUEST for publisherId={} (this={})",
                        requestPublisherId, publisherId);
                return;
            }

            log.info("Received CATCH_UP_REQUEST: publisherId={}, streams={}, lastTimestamp={}",
                    requestPublisherId, request.streams().size(), request.lastTimestamp());

            pendingCatchUpRequest = request;
            pendingCatchUpRequestTimeMs = System.currentTimeMillis();
        }
        // Ignore other template IDs — they're for ReplayClient
    }

    /**
     * Process a deferred CATCH_UP_REQUEST by replaying missing entries to the
     * first available subscriber. Used when the connection transition didn't fire
     * (e.g., quick store restart within Aeron's connection timeout).
     */
    private void processStoreCatchUpRequest(CatchUpRequestCodec.DecodedRequest request,
                                             List<SubscriberConnection> subscribers) {
        if (subscribers.isEmpty()) {
            log.warn("No subscribers configured, cannot process CATCH_UP_REQUEST");
            return;
        }
        SubscriberConnection subscriber = subscribers.get(0);

        if (!request.streams().isEmpty()) {
            syncIncrementalToSubscriber(subscriber, request);
        } else {
            syncLocalToSubscriber(subscriber);
        }
    }

    /**
     * Replay only entries that the store is missing, based on the stream positions
     * reported in a CATCH_UP_REQUEST.
     */
    private void syncIncrementalToSubscriber(SubscriberConnection subscriber,
                                              CatchUpRequestCodec.DecodedRequest request) {
        java.util.Map<String, Integer> storePositions = new java.util.HashMap<>();
        for (CatchUpRequestCodec.StreamPosition sp : request.streams()) {
            storePositions.put(sp.streamName(), sp.lastSeqNum());
        }

        MutableDirectBuffer syncBuffer = new ExpandableDirectByteBuffer(8192);
        long[] count = {0};

        log.info("Starting incremental catch-up to subscriber {} — {} stream positions reported",
                subscriber.getConfig().getName(), storePositions.size());

        for (String streamName : localStore.getStreamNames()) {
            int storeLastSeq = storePositions.getOrDefault(streamName, 0);
            localStore.replay(streamName, null, storeLastSeq + 1, 0, entry -> {
                int length = LogEntryCodec.encode(syncBuffer, 0, entry, publisherId);
                long result = subscriber.offerData(syncBuffer, 0, length);
                if (result < 0) {
                    try { Thread.sleep(1); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    subscriber.offerData(syncBuffer, 0, length);
                }
                count[0]++;
                return true;
            });
        }

        log.info("Incremental catch-up to subscriber {} complete — {} entries replayed",
                subscriber.getConfig().getName(), count[0]);
    }

    /**
     * Replay all local Chronicle entries to a single subscriber that just connected.
     * Uses a dedicated encode buffer to avoid contention with the write path.
     */
    private void syncLocalToSubscriber(SubscriberConnection subscriber) {
        MutableDirectBuffer syncBuffer = new ExpandableDirectByteBuffer(8192);
        long[] count = {0};

        log.info("Starting catch-up sync to subscriber {} — replaying all local entries",
                subscriber.getConfig().getName());

        for (String streamName : localStore.getStreamNames()) {
            localStore.replay(streamName, null, 0, 0, entry -> {
                int length = LogEntryCodec.encode(syncBuffer, 0, entry, publisherId);
                long result = subscriber.offerData(syncBuffer, 0, length);
                if (result < 0) {
                    // Back-pressure or disconnect during sync — pause briefly and retry once
                    try { Thread.sleep(1); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    subscriber.offerData(syncBuffer, 0, length);
                }
                count[0]++;
                return true;
            });
        }

        log.info("Catch-up sync to subscriber {} complete — {} entries replayed",
                subscriber.getConfig().getName(), count[0]);
    }

    private void startCatchUpThread() {
        catchUpRunning = true;
        catchUpThread = new Thread(this::catchUpLoop, "aeron-catchup-sync");
        catchUpThread.setDaemon(true);
        catchUpThread.start();
    }

    private void stopCatchUpThread() {
        catchUpRunning = false;
        if (catchUpThread != null) {
            catchUpThread.interrupt();
            try {
                catchUpThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catchUpThread = null;
        }
    }

    // ==================== Component Lifecycle ====================

    @Override
    public void initialize() throws Exception {
        localStore.initialize();
        componentState = ComponentState.INITIALIZED;
        log.debug("AeronLogStore initialized");
    }

    @Override
    public void startActive() throws Exception {
        localStore.startActive();
        transport.start();
        startCatchUpThread();
        componentState = ComponentState.ACTIVE;
        log.info("AeronLogStore started in ACTIVE mode (publisherId={})", publisherId);
    }

    @Override
    public void startStandby() throws Exception {
        localStore.startStandby();
        componentState = ComponentState.STANDBY;
        log.info("AeronLogStore started in STANDBY mode");
    }

    @Override
    public void becomeActive() throws Exception {
        localStore.becomeActive();
        transport.start();
        startCatchUpThread();
        componentState = ComponentState.ACTIVE;
        log.info("AeronLogStore transitioned to ACTIVE mode");
    }

    @Override
    public void becomeStandby() throws Exception {
        stopCatchUpThread();
        transport.stop();
        localStore.becomeStandby();
        componentState = ComponentState.STANDBY;
        log.info("AeronLogStore transitioned to STANDBY mode");
    }

    @Override
    public void stop() {
        stopCatchUpThread();
        transport.stop();
        localStore.stop();
        componentState = ComponentState.STOPPED;
        log.info("AeronLogStore stopped");
    }

    @Override
    public String getName() {
        return "persistence-store";
    }

    @Override
    public ComponentState getState() {
        return componentState;
    }

    @Override
    public void close() throws IOException {
        stop();
    }

    // ==================== Accessors (for testing) ====================

    public ChronicleLogStore getLocalStore() {
        return localStore;
    }

    public AeronTransport getTransport() {
        return transport;
    }

    public ReplayClient getReplayClient() {
        return replayClient;
    }
}
