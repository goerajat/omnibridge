package com.omnibridge.metrics.binder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Registers event loop iteration and task queue gauges.
 */
public class EventLoopMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(EventLoopMetricsBinder.class);

    private Counter selectCounter;
    private Timer iterationTimer;
    private final AtomicLong taskQueueSize = new AtomicLong(0);
    private final AtomicLong busySpin = new AtomicLong(0);

    @Override
    public void bindTo(MeterRegistry registry) {
        selectCounter = Counter.builder("omnibridge.eventloop.select.count")
                .description("Selector iterations")
                .register(registry);

        iterationTimer = Timer.builder("omnibridge.eventloop.iteration.time")
                .description("Per-iteration time")
                .register(registry);

        Gauge.builder("omnibridge.eventloop.task_queue.size", taskQueueSize, AtomicLong::doubleValue)
                .description("Pending tasks")
                .register(registry);

        Gauge.builder("omnibridge.eventloop.busy_spin", busySpin, AtomicLong::doubleValue)
                .description("Busy-spin mode active (0/1)")
                .register(registry);

        log.info("Registered event loop metrics");
    }

    public void recordSelect() {
        if (selectCounter != null) {
            selectCounter.increment();
        }
    }

    public void recordIterationTime(long nanos) {
        if (iterationTimer != null) {
            iterationTimer.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    public void setTaskQueueSize(long size) {
        taskQueueSize.set(size);
    }

    public void setBusySpin(boolean active) {
        busySpin.set(active ? 1 : 0);
    }
}
