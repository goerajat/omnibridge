package com.omnibridge.persistence.aeron;

import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.persistence.LogEntry;
import com.omnibridge.persistence.aeron.codec.*;
import com.omnibridge.persistence.aeron.config.AeronRemoteStoreConfig;
import com.omnibridge.persistence.chronicle.ChronicleLogStore;
import com.omnibridge.persistence.config.PersistenceConfig;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.FragmentAssembler;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side component that receives replicated log entries via Aeron
 * and stores them in a local ChronicleLogStore for durable remote persistence.
 *
 * <p>Runs a single-threaded polling loop that:
 * <ul>
 *   <li>Polls data subscription for incoming log entries</li>
 *   <li>Polls control subscription for replay/info requests</li>
 * </ul>
 */
public class AeronRemoteStore implements Component, Runnable {

    private static final Logger log = LoggerFactory.getLogger(AeronRemoteStore.class);

    private final AeronRemoteStoreConfig config;
    private final ChronicleLogStore store;
    private final List<ReplayHandler> replayHandlers = new ArrayList<>();
    private final Map<Long, PublisherState> publishers = new ConcurrentHashMap<>();

    private MediaDriver mediaDriver;
    private Aeron aeron;
    private Subscription dataSubscription;
    private Subscription controlSubscription;
    private final List<Publication> replayPublications = new ArrayList<>();

    private IdleStrategy idleStrategy;
    private Thread pollingThread;
    private volatile boolean running;
    private volatile ComponentState componentState = ComponentState.UNINITIALIZED;
    private long entriesReceived;

    public AeronRemoteStore(AeronRemoteStoreConfig config) {
        this.config = config;
        PersistenceConfig persistenceConfig = PersistenceConfig.builder()
                .basePath(config.getBasePath())
                .storeType(PersistenceConfig.StoreType.CHRONICLE)
                .build();
        this.store = new ChronicleLogStore(persistenceConfig);
    }

    // ==================== Polling Loop ====================

    @Override
    public void run() {
        FragmentHandler dataHandler = new FragmentAssembler(this::onDataFragment);
        FragmentHandler controlHandler = new FragmentAssembler(this::onControlFragment);
        int fragmentLimit = config.getFragmentLimit();

        log.info("AeronRemoteStore polling loop started");

        while (running) {
            int totalWork = 0;
            totalWork += dataSubscription.poll(dataHandler, fragmentLimit);
            totalWork += controlSubscription.poll(controlHandler, fragmentLimit);
            idleStrategy.idle(totalWork);
        }

        log.info("AeronRemoteStore polling loop stopped (entries received: {})", entriesReceived);
    }

    private void onDataFragment(DirectBuffer buffer, int offset, int length,
                                io.aeron.logbuffer.Header header) {
        int templateId = AeronMessageHeader.readTemplateId(buffer, offset);

        if (templateId == MessageTypes.LOG_ENTRY) {
            LogEntryCodec.DecodedEntry decoded = LogEntryCodec.decodeWithPublisherId(buffer, offset);
            LogEntry entry = decoded.entry();
            long publisherId = decoded.publisherId();

            // Prefix stream name with publisher ID for isolation
            String prefixedStream = "pub~" + publisherId + "~" + entry.getStreamName();
            LogEntry prefixedEntry = LogEntry.builder()
                    .timestamp(entry.getTimestamp())
                    .direction(entry.getDirection())
                    .sequenceNumber(entry.getSequenceNumber())
                    .streamName(prefixedStream)
                    .metadata(entry.getMetadata())
                    .rawMessage(entry.getRawMessage())
                    .build();

            store.write(prefixedEntry);
            entriesReceived++;

            // Update per-publisher tracking
            publishers.computeIfAbsent(publisherId, PublisherState::new)
                    .update(entry.getTimestamp(), entry.getSequenceNumber());
        } else {
            log.warn("Unknown template ID on data channel: {}", templateId);
        }
    }

    private void onControlFragment(DirectBuffer buffer, int offset, int length,
                                   io.aeron.logbuffer.Header header) {
        int templateId = AeronMessageHeader.readTemplateId(buffer, offset);

        switch (templateId) {
            case MessageTypes.REPLAY_REQUEST -> {
                long publisherId = ReplayRequestCodec.decodePublisherId(buffer, offset);
                // Route to the replay handler for the requesting engine's publisherId
                boolean handled = false;
                for (ReplayHandler rh : replayHandlers) {
                    if (rh.getPublisherId() == publisherId || rh.getPublisherId() == 0) {
                        rh.handleReplayRequest(buffer, offset, publisherId);
                        handled = true;
                        break;
                    }
                }
                if (!handled && !replayHandlers.isEmpty()) {
                    // Fallback: use first handler
                    replayHandlers.get(0).handleReplayRequest(buffer, offset, publisherId);
                }
            }
            case MessageTypes.STREAM_INFO_REQUEST -> {
                long publisherId = StreamInfoRequestCodec.decodePublisherId(buffer, offset);
                for (ReplayHandler rh : replayHandlers) {
                    rh.handleStreamInfoRequest(buffer, offset, publisherId);
                    break; // Only need one handler to respond
                }
            }
            case MessageTypes.HEARTBEAT -> {
                long ts = HeartbeatCodec.decodeTimestamp(buffer, offset);
                long pubId = HeartbeatCodec.decodePublisherId(buffer, offset);
                log.trace("Heartbeat from publisherId={} at {}", pubId, ts);
            }
            default -> log.warn("Unknown template ID on control channel: {}", templateId);
        }
    }

    // ==================== Component Lifecycle ====================

    @Override
    public void initialize() throws Exception {
        store.initialize();
        componentState = ComponentState.INITIALIZED;
        log.debug("AeronRemoteStore initialized");
    }

    @Override
    public void startActive() throws Exception {
        store.startActive();
        startAeron();
        startPolling();
        sendCatchUpRequests();
        componentState = ComponentState.ACTIVE;
        log.info("AeronRemoteStore started in ACTIVE mode (data={}, control={})",
                config.getDataChannel(), config.getControlChannel());
    }

    @Override
    public void startStandby() throws Exception {
        store.startStandby();
        componentState = ComponentState.STANDBY;
        log.info("AeronRemoteStore started in STANDBY mode");
    }

    @Override
    public void becomeActive() throws Exception {
        store.becomeActive();
        startAeron();
        startPolling();
        sendCatchUpRequests();
        componentState = ComponentState.ACTIVE;
        log.info("AeronRemoteStore transitioned to ACTIVE mode");
    }

    @Override
    public void becomeStandby() throws Exception {
        stopPolling();
        stopAeron();
        store.becomeStandby();
        componentState = ComponentState.STANDBY;
        log.info("AeronRemoteStore transitioned to STANDBY mode");
    }

    @Override
    public void stop() {
        stopPolling();
        stopAeron();
        store.stop();
        componentState = ComponentState.STOPPED;
        log.info("AeronRemoteStore stopped");
    }

    @Override
    public String getName() {
        return "aeron-remote-store";
    }

    @Override
    public ComponentState getState() {
        return componentState;
    }

    // ==================== Internal ====================

    private void startAeron() {
        if (config.isEmbeddedMediaDriver()) {
            MediaDriver.Context driverCtx = new MediaDriver.Context()
                    .threadingMode(ThreadingMode.SHARED)
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true);

            String aeronDir = config.getAeronDir();
            if (aeronDir != null && !aeronDir.isEmpty()) {
                driverCtx.aeronDirectoryName(aeronDir);
            }

            mediaDriver = MediaDriver.launchEmbedded(driverCtx);
            log.info("Embedded MediaDriver started: {}", mediaDriver.aeronDirectoryName());
        }

        Aeron.Context aeronCtx = new Aeron.Context();
        if (mediaDriver != null) {
            aeronCtx.aeronDirectoryName(mediaDriver.aeronDirectoryName());
        }
        aeron = Aeron.connect(aeronCtx);

        // Data subscription: receives log entries
        dataSubscription = aeron.addSubscription(
                config.getDataChannel(), MessageTypes.DATA_STREAM_ID);

        // Control subscription: receives replay/info requests
        controlSubscription = aeron.addSubscription(
                config.getControlChannel(), MessageTypes.CONTROL_STREAM_ID);

        // Create replay publications for each known engine
        for (AeronRemoteStoreConfig.EngineConfig engine : config.getEngines()) {
            Publication replayPub = aeron.addPublication(
                    engine.getReplayChannel(), MessageTypes.REPLAY_STREAM_ID);
            replayPublications.add(replayPub);
            replayHandlers.add(new ReplayHandler(store, replayPub, engine.getPublisherId()));
            log.info("Replay publication created for engine {} (publisherId={}): {}",
                    engine.getName(), engine.getPublisherId(), engine.getReplayChannel());
        }

        idleStrategy = createIdleStrategy();
    }

    private void stopAeron() {
        for (ReplayHandler rh : replayHandlers) {
            // ReplayHandler doesn't need closing
        }
        replayHandlers.clear();

        for (Publication pub : replayPublications) {
            pub.close();
        }
        replayPublications.clear();

        if (dataSubscription != null) {
            dataSubscription.close();
        }
        if (controlSubscription != null) {
            controlSubscription.close();
        }

        if (aeron != null) {
            aeron.close();
            aeron = null;
        }

        if (mediaDriver != null) {
            mediaDriver.close();
            mediaDriver = null;
        }
    }

    private void startPolling() {
        running = true;
        pollingThread = new Thread(this, "aeron-remote-store");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    private void stopPolling() {
        running = false;
        if (pollingThread != null) {
            try {
                pollingThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pollingThread = null;
        }
    }

    /**
     * Send a CATCH_UP_REQUEST to each configured engine so they can replay
     * entries that the store missed while it was down.
     *
     * <p>For each engine, the local Chronicle store is scanned for streams
     * belonging to that engine's publisher ID. The last sequence number for
     * each stream is included in the request so the engine knows where to
     * start replaying from.
     */
    private void sendCatchUpRequests() {
        MutableDirectBuffer requestBuffer = new ExpandableDirectByteBuffer(1024);

        for (int i = 0; i < config.getEngines().size(); i++) {
            AeronRemoteStoreConfig.EngineConfig engine = config.getEngines().get(i);
            long publisherId = engine.getPublisherId();
            String prefix = "pub~" + publisherId + "~";

            // Scan local Chronicle for streams belonging to this publisher
            List<CatchUpRequestCodec.StreamPosition> positions = new ArrayList<>();
            long latestTimestamp = 0;

            for (String streamName : store.getStreamNames()) {
                if (streamName.startsWith(prefix)) {
                    String originalName = streamName.substring(prefix.length());
                    // Get the latest entry for this stream to find last seq#
                    LogEntry lastInbound = store.getLatest(streamName, LogEntry.Direction.INBOUND);
                    LogEntry lastOutbound = store.getLatest(streamName, LogEntry.Direction.OUTBOUND);
                    int lastSeq = Math.max(
                            lastInbound != null ? lastInbound.getSequenceNumber() : 0,
                            lastOutbound != null ? lastOutbound.getSequenceNumber() : 0);
                    long lastTs = Math.max(
                            lastInbound != null ? lastInbound.getTimestamp() : 0,
                            lastOutbound != null ? lastOutbound.getTimestamp() : 0);
                    latestTimestamp = Math.max(latestTimestamp, lastTs);
                    positions.add(new CatchUpRequestCodec.StreamPosition(originalName, lastSeq));
                }
            }

            int length = CatchUpRequestCodec.encode(requestBuffer, 0,
                    publisherId, latestTimestamp, positions);

            // Send via the replay publication for this engine
            if (i < replayPublications.size()) {
                Publication replayPub = replayPublications.get(i);
                // Retry a few times since the publication may not be connected yet
                for (int attempt = 0; attempt < 50; attempt++) {
                    long result = replayPub.offer(requestBuffer, 0, length);
                    if (result > 0) {
                        log.info("Sent CATCH_UP_REQUEST to engine {} (publisherId={}, streams={}, lastTs={})",
                                engine.getName(), publisherId, positions.size(), latestTimestamp);
                        break;
                    }
                    if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                        log.warn("Cannot send CATCH_UP_REQUEST to engine {}: publication unavailable (result={})",
                                engine.getName(), result);
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private IdleStrategy createIdleStrategy() {
        return AeronIdleStrategyUtil.create(config.getIdleStrategy());
    }

    public ChronicleLogStore getStore() {
        return store;
    }

    public long getEntriesReceived() {
        return entriesReceived;
    }

    /**
     * Returns the per-publisher tracking state for monitoring and validation.
     */
    public Map<Long, PublisherState> getPublisherStates() {
        return Collections.unmodifiableMap(publishers);
    }

    /**
     * Tracks per-publisher state: entries received, last timestamp, and last sequence number.
     */
    public static class PublisherState {
        private final long publisherId;
        private long entriesReceived;
        private long lastTimestamp;
        private int lastSeqNum;

        public PublisherState(long publisherId) {
            this.publisherId = publisherId;
        }

        void update(long timestamp, int seqNum) {
            entriesReceived++;
            lastTimestamp = timestamp;
            lastSeqNum = seqNum;
        }

        public long getPublisherId() {
            return publisherId;
        }

        public long getEntriesReceived() {
            return entriesReceived;
        }

        public long getLastTimestamp() {
            return lastTimestamp;
        }

        public int getLastSeqNum() {
            return lastSeqNum;
        }

        @Override
        public String toString() {
            return "PublisherState{publisherId=" + publisherId +
                    ", entriesReceived=" + entriesReceived +
                    ", lastSeqNum=" + lastSeqNum + '}';
        }
    }
}
