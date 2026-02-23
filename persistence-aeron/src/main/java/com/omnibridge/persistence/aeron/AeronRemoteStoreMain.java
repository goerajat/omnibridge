package com.omnibridge.persistence.aeron;

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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            if (remoteStore != null) {
                log.info("Total entries received: {}", remoteStore.getEntriesReceived());
                remoteStore.stop();
            }
        }, "remote-store-shutdown"));

        remoteStore.initialize();
        remoteStore.startActive();

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
