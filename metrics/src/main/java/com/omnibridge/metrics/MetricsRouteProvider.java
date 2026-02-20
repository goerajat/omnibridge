package com.omnibridge.metrics;

import com.omnibridge.admin.routes.RouteProvider;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route provider that exposes {@code GET /api/metrics} returning Prometheus scrape format.
 */
public class MetricsRouteProvider implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(MetricsRouteProvider.class);
    private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final MetricsComponent metricsComponent;

    public MetricsRouteProvider(MetricsComponent metricsComponent) {
        this.metricsComponent = metricsComponent;
    }

    @Override
    public String getBasePath() {
        return "/metrics";
    }

    @Override
    public void registerRoutes(Javalin app, String contextPath) {
        String path = contextPath + getBasePath();

        app.get(path, ctx -> {
            ctx.contentType(PROMETHEUS_CONTENT_TYPE);
            ctx.result(metricsComponent.scrape());
        });

        log.info("Registered metrics endpoint at {}", path);
    }

    @Override
    public String getDescription() {
        return "Prometheus metrics endpoint";
    }

    @Override
    public int getPriority() {
        return 50; // Register early
    }
}
