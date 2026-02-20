package com.omnibridge.sbe.engine.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.sbe.engine.SbeEngine;
import com.omnibridge.sbe.engine.config.SbeEngineConfig;
import com.omnibridge.sbe.engine.config.SbeSessionConfig;
import com.typesafe.config.Config;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract factory for creating {@link SbeEngine} instances.
 * <p>
 * Protocol-specific implementations (iLink 3, Optiq) must extend this class
 * and implement the engine creation logic.
 * <p>
 * Usage:
 * <pre>
 * public class ILink3EngineFactory extends SbeEngineFactory&lt;ILink3Engine&gt; {
 *     &#64;Override
 *     protected String getConfigPath() {
 *         return "ilink3-engine";
 *     }
 *
 *     &#64;Override
 *     protected ILink3Engine createEngine(Config config, ComponentProvider provider) {
 *         ILink3EngineConfig engineConfig = ILink3EngineConfig.fromConfig(config);
 *         return new ILink3Engine(engineConfig, provider);
 *     }
 * }
 * </pre>
 *
 * @param <E> the engine type
 */
public abstract class SbeEngineFactory<E extends SbeEngine<?, ?, ?>> implements ComponentFactory<E> {

    private static final Logger log = LoggerFactory.getLogger(SbeEngineFactory.class);

    /**
     * Gets the configuration path for this engine type.
     * For example, "ilink3-engine" or "optiq-engine".
     *
     * @return the configuration path
     */
    protected abstract String getConfigPath();

    /**
     * Creates the protocol-specific engine.
     *
     * @param config the HOCON configuration
     * @param provider the component provider
     * @return the created engine
     */
    protected abstract E createEngine(Config config, ComponentProvider provider);

    @Override
    public E create(String name, Config config, ComponentProvider provider) {
        String configPath = getConfigPath();
        Config engineConfig = config.hasPath(configPath) ? config.getConfig(configPath) : config;
        E engine = createEngine(engineConfig, provider);

        // Inject meter registry if metrics component is available
        try {
            Object metricsComponent = null;
            try {
                Class<?> metricsClass = Class.forName("com.omnibridge.metrics.MetricsComponent");
                metricsComponent = provider.getComponent(metricsClass.asSubclass(com.omnibridge.config.Component.class));
            } catch (ClassNotFoundException | IllegalArgumentException e) {
                log.debug("No MetricsComponent available, metrics disabled for {} engine", getConfigPath());
            }

            if (metricsComponent != null) {
                java.lang.reflect.Method getRegistry = metricsComponent.getClass().getMethod("getMeterRegistry");
                MeterRegistry registry = (MeterRegistry) getRegistry.invoke(metricsComponent);
                if (registry != null) {
                    engine.setMeterRegistry(registry);
                    log.info("Injected MeterRegistry into {} engine", getConfigPath());
                }
            }
        } catch (Exception e) {
            log.debug("Could not inject MeterRegistry into {} engine: {}", getConfigPath(), e.getMessage());
        }

        return engine;
    }
}
