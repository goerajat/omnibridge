package com.fixengine.samples.acceptor;

import com.fixengine.config.FixEngineConfig;
import com.fixengine.config.NetworkConfig;
import com.fixengine.config.PersistenceConfig;
import com.fixengine.config.provider.DefaultComponentProvider;
import com.fixengine.engine.FixEngine;
import com.fixengine.network.NetworkEventLoop;
import com.fixengine.persistence.FixLogStore;
import com.fixengine.persistence.memory.MemoryMappedFixLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import ch.qos.logback.classic.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Sample FIX acceptor (exchange simulator).
 * Accepts incoming FIX connections and simulates order handling.
 */
@Command(name = "sample-acceptor", description = "Sample FIX acceptor (exchange simulator)")
public class SampleAcceptor implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SampleAcceptor.class);

    @Option(names = {"-c", "--config"}, description = "Configuration file(s)", split = ",")
    private List<String> configFiles = new ArrayList<>();

    @Option(names = {"--fill-rate"}, description = "Probability of immediate fill (0.0-1.0)", defaultValue = "0.8")
    private double fillRate;

    @Option(names = {"--latency"}, description = "Enable latency mode (ERROR log level, no message logging)", defaultValue = "false")
    private boolean latencyMode;

    private DefaultComponentProvider provider;

    @Override
    public Integer call() throws Exception {
        if (latencyMode) {
            //setLogLevel("ERROR");
            System.out.println("Latency mode enabled - log level set to ERROR");
            System.out.println("Message pooling: ENABLED (zero-allocation mode)");
        }

        try {
            // Load configuration
            if (configFiles.isEmpty()) {
                configFiles.add("acceptor.conf");
            }

            log.info("Loading configuration from: {}", configFiles);

            // Create and initialize provider
            provider = DefaultComponentProvider.create(configFiles);
/*
            // Apply latency mode settings to sessions
            if (latencyMode) {
                NetworkConfig networkConfig = provider.getNetworkConfig();
                PersistenceConfig persistenceConfig = provider.getPersistenceConfig();
                FixEngineConfig engineConfig = applyLatencyModeSettings(provider.getEngineConfig());
                provider = DefaultComponentProvider.create(networkConfig, persistenceConfig, engineConfig);
            }
*/
            log.info("Starting Sample FIX Acceptor");

            // Register component factories
            provider.withNetworkFactory(config -> new NetworkEventLoop(config, provider))
                    .withPersistenceFactory(config -> new MemoryMappedFixLogStore(config, provider))
                    .withEngineFactory((config, networkLoop, persistence) ->
                        new FixEngine(config, (NetworkEventLoop) networkLoop, (FixLogStore) persistence));

            // Initialize components
            provider.initialize();

            // Get the engine and create sessions from config
            FixEngine engine = (FixEngine) provider.getEngineProvider().get();
            AcceptorMessageListener messageListener = new AcceptorMessageListener(fillRate, latencyMode);
            engine.addStateListener(new AcceptorStateListener(messageListener));
            engine.addMessageListener(messageListener);
            engine.createSessionsFromConfig();

            provider.start();
            //engine.start();

            log.info("Acceptor started. Press Ctrl+C to stop.");

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown signal received");
                if (provider != null) {
                    provider.stop();
                }
            }));

            // Keep running
            Thread.currentThread().join();
            return 0;

        } catch (Exception e) {
            log.error("Error starting acceptor", e);
            return 1;
        }
    }
/*
    private FixEngineConfig applyLatencyModeSettings(FixEngineConfig config) {
        // Rebuild config with latency mode settings for sessions
        return FixEngineConfig.builder()
                .sessions(config.getSessions().stream()
                    .map(s -> com.fixengine.config.EngineSessionConfig.builder()
                        .sessionName(s.getSessionName())
                        .beginString(s.getBeginString())
                        .senderCompId(s.getSenderCompId())
                        .targetCompId(s.getTargetCompId())
                        .role(s.getRole())
                        .host(s.getHost())
                        .port(s.getPort())
                        .heartbeatInterval(s.getHeartbeatInterval())
                        .resetOnLogon(s.isResetOnLogon())
                        .resetOnLogout(s.isResetOnLogout())
                        .resetOnDisconnect(s.isResetOnDisconnect())
                        .reconnectInterval(s.getReconnectInterval())
                        .maxReconnectAttempts(s.getMaxReconnectAttempts())
                        .timeZone(s.getTimeZone())
                        .resetOnEod(s.isResetOnEod())
                        .logMessages(false)  // Disable logging in latency mode
                        .usePooledMessages(true)  // Enable pooling in latency mode
                        .messagePoolSize(Math.max(s.getMessagePoolSize(), 128))
                        .maxMessageLength(s.getMaxMessageLength())
                        .maxTagNumber(s.getMaxTagNumber())
                        .build())
                    .toList())
                .build();
    }

    private void setLogLevel(String level) {
        ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.fixengine");
        rootLogger.setLevel(Level.toLevel(level));
    }
*/
    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleAcceptor()).execute(args);
        System.exit(exitCode);
    }
}
