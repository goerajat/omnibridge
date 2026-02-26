package com.omnibridge.persistence.aeron;

import com.omnibridge.admin.AdminServer;
import com.omnibridge.admin.config.AdminServerConfig;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.metrics.MetricsComponent;
import com.omnibridge.metrics.MetricsConfig;
import com.omnibridge.metrics.MetricsRouteProvider;
import com.omnibridge.persistence.aeron.admin.AeronStoreMetricsBinder;
import com.omnibridge.persistence.aeron.admin.AeronStoreRouteProvider;
import com.omnibridge.persistence.aeron.config.AeronRemoteStoreConfig;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * Standalone process for running an {@link AeronRemoteStore}.
 *
 * <p>Loads HOCON configuration, starts the remote store, and blocks until
 * interrupted. On shutdown, logs the total number of entries received.</p>
 *
 * <p>Usage:
 * <pre>
 * java -cp ... com.omnibridge.persistence.aeron.AeronRemoteStoreMain -c remote-store.conf
 * </pre>
 */
@CommandLine.Command(
        name = "aeron-remote-store",
        description = "Standalone Aeron Remote Store for receiving replicated log entries",
        mixinStandardHelpOptions = true
)
public class AeronRemoteStoreMain implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(AeronRemoteStoreMain.class);

    @CommandLine.Option(names = {"-c", "--config"}, description = "Path to HOCON config file", required = true)
    private File configFile;

    private volatile AeronRemoteStore remoteStore;
    private volatile AdminServer adminServer;
    private volatile MetricsComponent metricsComponent;
    private volatile AeronStoreMetricsBinder metricsBinder;

    @Override
    public Integer call() throws Exception {
        log.info("Starting AeronRemoteStore with config: {}", configFile.getAbsolutePath());

        Config rootConfig = ConfigFactory.parseFile(configFile)
                .withFallback(ConfigFactory.load())
                .resolve();

        Config remoteStoreConfig = rootConfig.getConfig("aeron-remote-store");
        AeronRemoteStoreConfig config = AeronRemoteStoreConfig.fromConfig(remoteStoreConfig);

        log.info("Configuration: {}", config);

        remoteStore = new AeronRemoteStore(config);

        // Admin server + metrics setup
        AdminServerConfig adminConfig = AdminServerConfig.fromConfig(rootConfig);
        MetricsConfig metricsConfig = MetricsConfig.fromConfig(rootConfig);

        // No-op ComponentProvider — the store doesn't use the component framework
        ComponentProvider noOpProvider = new ComponentProvider() {
            @Override
            public <T extends com.omnibridge.config.Component> T getComponent(Class<T> type) {
                return null;
            }

            @Override
            public <T extends com.omnibridge.config.Component> T getComponent(String name, Class<T> type) {
                return null;
            }

            @Override
            public Config getConfig() {
                return rootConfig;
            }
        };

        metricsComponent = new MetricsComponent("aeron-store-metrics", metricsConfig, noOpProvider);
        adminServer = new AdminServer("aeron-store-admin", adminConfig);

        // Register route providers
        adminServer.addRouteProvider(new AeronStoreRouteProvider(remoteStore));
        adminServer.addRouteProvider(new MetricsRouteProvider(metricsComponent));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            if (adminServer != null) {
                adminServer.stop();
            }
            if (metricsBinder != null) {
                metricsBinder.close();
            }
            if (metricsComponent != null) {
                metricsComponent.stop();
            }
            if (remoteStore != null) {
                log.info("Total entries received: {}", remoteStore.getEntriesReceived());
                remoteStore.stop();
            }
        }, "remote-store-shutdown"));

        remoteStore.initialize();
        remoteStore.startActive();

        // Initialize and start admin + metrics after store is running
        metricsComponent.initialize();
        metricsComponent.startActive();

        // Bind Aeron store metrics to registry
        if (metricsComponent.getRegistry() != null) {
            metricsBinder = new AeronStoreMetricsBinder(remoteStore);
            metricsBinder.bindTo(metricsComponent.getRegistry());
        }

        adminServer.initialize();
        adminServer.startActive();

        log.info("AeronRemoteStore is running. Press Ctrl+C to stop.");

        // Block until interrupted
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AeronRemoteStoreMain()).execute(args);
        System.exit(exitCode);
    }
}
