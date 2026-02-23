package com.omnibridge.persistence.aeron;

import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.persistence.*;
import com.omnibridge.persistence.aeron.codec.LogEntryCodec;
import com.omnibridge.persistence.aeron.codec.MessageTypes;
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
     *
     * @param streamName the stream to recover (null for all)
     * @return the number of entries recovered
     */
    public long recoverFromRemote(String streamName) {
        log.info("Starting recovery from remote for stream: {}", streamName != null ? streamName : "ALL");
        return replayClient.requestReplay(localStore, streamName,
                MessageTypes.DIRECTION_BOTH, 0, 0);
    }

    /**
     * Recover entries from a remote subscriber with filtering.
     */
    public long recoverFromRemote(String streamName, byte direction,
                                  int fromSeqNum, int toSeqNum,
                                  long fromTimestamp, long toTimestamp) {
        return replayClient.requestReplay(localStore, streamName, direction,
                fromSeqNum, toSeqNum, fromTimestamp, toTimestamp, 0);
    }

    // ==================== Catch-Up Sync ====================

    /**
     * Background loop that monitors subscriber connections and triggers a
     * catch-up replay of all local Chronicle entries when a subscriber
     * transitions from disconnected to connected.
     *
     * <p>This ensures no messages are lost when the remote store starts after
     * the engine, or when the remote store restarts.</p>
     */
    private void catchUpLoop() {
        log.info("Catch-up sync thread started");
        while (catchUpRunning) {
            try {
                List<SubscriberConnection> subscribers = transport.getSubscribers();
                for (int i = 0; i < subscribers.size(); i++) {
                    SubscriberConnection sub = subscribers.get(i);
                    if (sub.checkAndClearCatchUpNeeded()) {
                        syncLocalToSubscriber(sub);
                    }
                }
                Thread.sleep(1000); // Check every second
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
