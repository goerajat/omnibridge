package com.omnibridge.metrics.binder;

import com.omnibridge.config.session.SessionManagementService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers aggregate session gauges from {@link SessionManagementService}.
 */
public class SessionMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(SessionMetricsBinder.class);

    private final SessionManagementService sessionService;

    public SessionMetricsBinder(SessionManagementService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("omnibridge.session.total", sessionService,
                        s -> s.getAllSessions().size())
                .description("Total registered sessions")
                .register(registry);

        Gauge.builder("omnibridge.session.logged_on.count", sessionService,
                        s -> s.getAllSessions().stream()
                                .filter(sess -> sess.isLoggedOn())
                                .count())
                .description("Number of logged-on sessions")
                .register(registry);

        Gauge.builder("omnibridge.session.connected.count", sessionService,
                        s -> s.getAllSessions().stream()
                                .filter(sess -> sess.isConnected())
                                .count())
                .description("Number of connected sessions")
                .register(registry);

        log.info("Registered aggregate session metrics");
    }
}
