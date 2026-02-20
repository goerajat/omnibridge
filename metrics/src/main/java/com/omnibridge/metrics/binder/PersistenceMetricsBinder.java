package com.omnibridge.metrics.binder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * Registers persistence write/replay counters.
 */
public class PersistenceMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(PersistenceMetricsBinder.class);

    private MeterRegistry registry;
    private Timer writeTimer;
    private final Map<String, Counter> streamCounters = new ConcurrentHashMap<>();

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;

        writeTimer = Timer.builder("omnibridge.persistence.write.time")
                .description("Write latency")
                .register(registry);

        log.info("Registered persistence metrics");
    }

    public void recordWrite(String streamName, long nanos) {
        if (writeTimer != null) {
            writeTimer.record(nanos, TimeUnit.NANOSECONDS);
        }
        if (registry != null) {
            streamCounters.computeIfAbsent(streamName, name ->
                    Counter.builder("omnibridge.persistence.entries.written.total")
                            .tag("stream_name", name)
                            .description("Entries written")
                            .register(registry)
            ).increment();
        }
    }
}
