package com.omnibridge.persistence.aeron.admin;

import com.omnibridge.persistence.aeron.AeronRemoteStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer {@link MeterBinder} that registers gauges from {@link AeronRemoteStore} state.
 * Periodically checks for newly-appeared publishers and registers per-publisher gauges.
 */
public class AeronStoreMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(AeronStoreMetricsBinder.class);

    private final AeronRemoteStore store;
    private final Set<Long> registeredPublishers = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;

    public AeronStoreMetricsBinder(AeronRemoteStore store) {
        this.store = store;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // Global gauges
        Gauge.builder("aeron_store_entries_received", store, AeronRemoteStore::getEntriesReceived)
                .description("Total log entries received by the Aeron remote store")
                .register(registry);

        Gauge.builder("aeron_store_stream_count", store,
                        s -> s.getStore().getStreamNames().size())
                .description("Number of distinct streams in the store")
                .register(registry);

        // Per-stream entry count gauges
        Gauge.builder("aeron_store_stream_entries_total", store,
                        s -> {
                            long total = 0;
                            for (String name : s.getStore().getStreamNames()) {
                                total += s.getStore().getEntryCount(name);
                            }
                            return total;
                        })
                .description("Total entries across all streams")
                .register(registry);

        // Register gauges for any publishers already present
        registerNewPublishers(registry);

        // Schedule periodic check for new publishers
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aeron-store-metrics-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                registerNewPublishers(registry);
                registerStreamGauges(registry);
            } catch (Exception e) {
                log.warn("Error refreshing Aeron store metrics", e);
            }
        }, 15, 15, TimeUnit.SECONDS);

        log.info("Aeron store metrics binder registered");
    }

    private final Set<String> registeredStreams = ConcurrentHashMap.newKeySet();

    private void registerNewPublishers(MeterRegistry registry) {
        store.getPublisherStates().forEach((publisherId, state) -> {
            if (registeredPublishers.add(publisherId)) {
                Tags tags = Tags.of("publisher_id", String.valueOf(publisherId));

                Gauge.builder("aeron_store_publisher_entries", state,
                                AeronRemoteStore.PublisherState::getEntriesReceived)
                        .tags(tags)
                        .description("Entries received from publisher")
                        .register(registry);

                Gauge.builder("aeron_store_publisher_last_timestamp", state,
                                AeronRemoteStore.PublisherState::getLastTimestamp)
                        .tags(tags)
                        .description("Last entry timestamp from publisher")
                        .register(registry);

                Gauge.builder("aeron_store_publisher_last_seq_num", state,
                                s -> s.getLastSeqNum())
                        .tags(tags)
                        .description("Last sequence number from publisher")
                        .register(registry);

                log.info("Registered metrics for publisher {}", publisherId);
            }
        });
    }

    private void registerStreamGauges(MeterRegistry registry) {
        for (String streamName : store.getStore().getStreamNames()) {
            if (registeredStreams.add(streamName)) {
                Gauge.builder("aeron_store_stream_entries", store,
                                s -> s.getStore().getEntryCount(streamName))
                        .tag("stream", streamName)
                        .description("Entry count for stream")
                        .register(registry);
            }
        }
    }

    /**
     * Shut down the periodic refresh scheduler.
     */
    public void close() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
}
