package com.omnibridge.metrics;

import com.omnibridge.config.Component;
import com.omnibridge.config.ComponentState;
import com.omnibridge.config.Singleton;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics component wrapping a {@link PrometheusMeterRegistry}.
 * Registers JVM binders (memory, GC, threads, direct buffers, CPU) on initialization.
 */
@Singleton
public class MetricsComponent implements Component {

    private static final Logger log = LoggerFactory.getLogger(MetricsComponent.class);

    private final String name;
    private final MetricsConfig config;
    private final AtomicReference<ComponentState> state = new AtomicReference<>(ComponentState.UNINITIALIZED);
    private PrometheusMeterRegistry registry;

    public MetricsComponent(String name, MetricsConfig config) {
        this.name = name;
        this.config = config;
    }

    /**
     * Get the Prometheus meter registry.
     * Available after initialization.
     */
    public PrometheusMeterRegistry getRegistry() {
        return registry;
    }

    /**
     * Get the meter registry as the generic interface.
     */
    public MeterRegistry getMeterRegistry() {
        return registry;
    }

    /**
     * Get the metrics configuration.
     */
    public MetricsConfig getMetricsConfig() {
        return config;
    }

    /**
     * Scrape all metrics in Prometheus text format.
     */
    public String scrape() {
        if (registry == null) {
            return "";
        }
        return registry.scrape();
    }

    @Override
    public void initialize() throws Exception {
        if (!state.compareAndSet(ComponentState.UNINITIALIZED, ComponentState.INITIALIZED)) {
            throw new IllegalStateException("Cannot initialize from state: " + state.get());
        }

        if (!config.isEnabled()) {
            log.info("[{}] Metrics disabled", name);
            return;
        }

        log.info("[{}] Initializing Prometheus metrics registry", name);

        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        if (config.isIncludeJvm()) {
            new JvmMemoryMetrics().bindTo(registry);
            new JvmGcMetrics().bindTo(registry);
            new JvmThreadMetrics().bindTo(registry);
            new JvmInfoMetrics().bindTo(registry);
            new ClassLoaderMetrics().bindTo(registry);
            new ProcessorMetrics().bindTo(registry);
            new UptimeMetrics().bindTo(registry);

            log.info("[{}] JVM metrics binders registered", name);
        }

        log.info("[{}] Metrics component initialized", name);
    }

    @Override
    public void startActive() throws Exception {
        if (state.get() != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start active from state: " + state.get());
        }
        state.set(ComponentState.ACTIVE);
        log.info("[{}] Metrics component active", name);
    }

    @Override
    public void startStandby() throws Exception {
        if (state.get() != ComponentState.INITIALIZED) {
            throw new IllegalStateException("Cannot start standby from state: " + state.get());
        }
        state.set(ComponentState.STANDBY);
    }

    @Override
    public void becomeActive() throws Exception {
        if (state.get() != ComponentState.STANDBY) {
            throw new IllegalStateException("Cannot become active from state: " + state.get());
        }
        state.set(ComponentState.ACTIVE);
    }

    @Override
    public void becomeStandby() throws Exception {
        if (state.get() != ComponentState.ACTIVE) {
            throw new IllegalStateException("Cannot become standby from state: " + state.get());
        }
        state.set(ComponentState.STANDBY);
    }

    @Override
    public void stop() {
        if (state.get() == ComponentState.STOPPED) {
            return;
        }

        log.info("[{}] Stopping metrics component", name);

        if (registry != null) {
            registry.close();
        }

        state.set(ComponentState.STOPPED);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ComponentState getState() {
        return state.get();
    }
}
