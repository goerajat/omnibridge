package com.omnibridge.metrics.factory;

import com.omnibridge.admin.AdminServer;
import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.metrics.MetricsComponent;
import com.omnibridge.metrics.MetricsConfig;
import com.omnibridge.metrics.MetricsRouteProvider;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating {@link MetricsComponent} instances.
 * Registers the {@link MetricsRouteProvider} with the {@link AdminServer}
 * to expose {@code GET /api/metrics}.
 */
public class MetricsFactory implements ComponentFactory<MetricsComponent> {

    private static final Logger log = LoggerFactory.getLogger(MetricsFactory.class);

    @Override
    public MetricsComponent create(String name, Config config, ComponentProvider provider) throws Exception {
        String componentName = name != null ? name : "metrics";
        MetricsConfig metricsConfig = MetricsConfig.fromConfig(config);

        MetricsComponent component = new MetricsComponent(componentName, metricsConfig);

        // Register metrics endpoint with admin server if available
        try {
            AdminServer adminServer = provider.getComponent(AdminServer.class);
            if (adminServer != null) {
                adminServer.addRouteProvider(new MetricsRouteProvider(component));
                log.info("Registered MetricsRouteProvider with AdminServer");
            }
        } catch (IllegalArgumentException e) {
            log.debug("No AdminServer registered, metrics endpoint not available via HTTP");
        }

        return component;
    }
}
