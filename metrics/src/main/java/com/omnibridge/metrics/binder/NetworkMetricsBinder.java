package com.omnibridge.metrics.binder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers network I/O metrics.
 * Provides pre-resolved counters for use on the hot path.
 */
public class NetworkMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(NetworkMetricsBinder.class);

    private Counter bytesReadCounter;
    private Counter bytesWrittenCounter;
    private Counter connectionsAcceptedCounter;
    private final AtomicLong activeConnections = new AtomicLong(0);

    @Override
    public void bindTo(MeterRegistry registry) {
        bytesReadCounter = Counter.builder("omnibridge.network.bytes.read.total")
                .description("Bytes read from sockets")
                .register(registry);

        bytesWrittenCounter = Counter.builder("omnibridge.network.bytes.written.total")
                .description("Bytes written to sockets")
                .register(registry);

        connectionsAcceptedCounter = Counter.builder("omnibridge.network.connections.accepted.total")
                .description("Accepted connections")
                .register(registry);

        Gauge.builder("omnibridge.network.connections.active", activeConnections, AtomicLong::doubleValue)
                .description("Active connections")
                .register(registry);

        log.info("Registered network metrics");
    }

    public void recordBytesRead(long bytes) {
        if (bytesReadCounter != null) {
            bytesReadCounter.increment(bytes);
        }
    }

    public void recordBytesWritten(long bytes) {
        if (bytesWrittenCounter != null) {
            bytesWrittenCounter.increment(bytes);
        }
    }

    public void recordConnectionAccepted() {
        if (connectionsAcceptedCounter != null) {
            connectionsAcceptedCounter.increment();
        }
        activeConnections.incrementAndGet();
    }

    public void recordConnectionClosed() {
        activeConnections.decrementAndGet();
    }
}
