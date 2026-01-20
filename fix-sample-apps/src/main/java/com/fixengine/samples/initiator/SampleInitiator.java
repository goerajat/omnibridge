package com.fixengine.samples.initiator;

import com.fixengine.config.FixEngineConfig;
import com.fixengine.config.NetworkConfig;
import com.fixengine.config.PersistenceConfig;
import com.fixengine.config.provider.DefaultComponentProvider;
import com.fixengine.engine.FixEngine;
import com.fixengine.engine.session.FixSession;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sample FIX initiator (trading client).
 * Connects to a FIX acceptor and allows sending orders.
 * Supports interactive mode, auto mode, and latency testing mode.
 */
@Command(name = "sample-initiator", description = "Sample FIX initiator (trading client)")
public class SampleInitiator implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SampleInitiator.class);

    @Option(names = {"-c", "--config"}, description = "Configuration file(s)", split = ",")
    private List<String> configFiles = new ArrayList<>();

    @Option(names = {"--auto"}, description = "Auto-send sample orders", defaultValue = "false")
    private boolean autoMode;

    @Option(names = {"--count"}, description = "Number of orders to send in auto mode", defaultValue = "10")
    private int orderCount;

    // Latency tracking options
    @Option(names = {"--latency"}, description = "Enable latency tracking mode", defaultValue = "false")
    private boolean latencyMode;

    @Option(names = {"--warmup-orders"}, description = "Number of warmup orders for JIT compilation", defaultValue = "10000")
    private int warmupOrders;

    @Option(names = {"--test-orders"}, description = "Number of orders per latency test run", defaultValue = "1000")
    private int testOrders;

    @Option(names = {"--rate"}, description = "Orders per second (0 for unlimited)", defaultValue = "100")
    private int ordersPerSecond;

    @Option(names = {"--max-pending"}, description = "Maximum pending orders before backpressure", defaultValue = "100")
    private int maxPendingOrders;

    @Option(names = {"--backpressure-timeout"}, description = "Seconds to wait in backpressure before aborting", defaultValue = "5")
    private int backpressureTimeoutSeconds;

    private DefaultComponentProvider provider;
    private volatile boolean running = true;

    @Override
    public Integer call() throws Exception {
        if (latencyMode) {
            setLogLevel("ERROR");
            System.out.println("Latency tracking mode enabled - log level set to ERROR");
            System.out.println("Warmup orders: " + warmupOrders);
            System.out.println("Test orders: " + testOrders);
            System.out.println("Rate: " + (ordersPerSecond > 0 ? ordersPerSecond + " orders/sec" : "unlimited"));
            System.out.println("Max pending orders: " + maxPendingOrders);
            System.out.println("Backpressure timeout: " + backpressureTimeoutSeconds + " seconds");
            System.out.println("Message pooling: ENABLED (zero-allocation mode)");
        }

        try {
            // Load configuration
            if (configFiles.isEmpty()) {
                configFiles.add("initiator.conf");
            }

            log.info("Loading configuration from: {}", configFiles);

            // Create and initialize provider
            provider = DefaultComponentProvider.create(configFiles);

            // Apply latency mode settings
            if (latencyMode) {
                NetworkConfig networkConfig = provider.getNetworkConfig();
                PersistenceConfig persistenceConfig = provider.getPersistenceConfig();
                FixEngineConfig engineConfig = applyLatencyModeSettings(provider.getEngineConfig());
                provider = DefaultComponentProvider.create(networkConfig, persistenceConfig, engineConfig);
            }

            log.info("Starting Sample FIX Initiator");

            // Register component factories
            provider.withNetworkFactory(config -> new NetworkEventLoop(config, provider))
                    .withPersistenceFactory(config -> new MemoryMappedFixLogStore(config, provider))
                    .withEngineFactory((config, networkLoop, persistence) ->
                        new FixEngine(config, (NetworkEventLoop) networkLoop, (FixLogStore) persistence));

            // Initialize components
            provider.initialize();

            // Get engine and create sessions
            FixEngine engine = (FixEngine) provider.getEngineProvider().get();

            // Setup listeners
            CountDownLatch logonLatch = new CountDownLatch(1);
            InitiatorStateListener stateListener = new InitiatorStateListener(logonLatch, reason -> running = false);
            InitiatorMessageListener messageListener = new InitiatorMessageListener(latencyMode);

            engine.addStateListener(stateListener);
            engine.addMessageListener(messageListener);

            List<FixSession> sessions = engine.createSessionsFromConfig();
            if (sessions.isEmpty()) {
                log.error("No sessions configured");
                return 1;
            }

            FixSession session = sessions.get(0);
            engine.start();

            // Connect
            engine.connect(session.getConfig().getSessionId());

            // Wait for logon
            if (!latencyMode) {
                log.info("Waiting for logon...");
            } else {
                System.out.println("Waiting for logon...");
            }

            if (!logonLatch.await(30, TimeUnit.SECONDS)) {
                log.error("Timeout waiting for logon");
                System.err.println("Timeout waiting for logon");
                provider.stop();
                return 1;
            }

            if (!latencyMode) {
                log.info("Logged on successfully!");
            } else {
                System.out.println("Logged on successfully!");
            }

            // Run the selected mode
            if (latencyMode) {
                LatencyTestRunner runner = new LatencyTestRunner(session, messageListener,
                        warmupOrders, testOrders, ordersPerSecond, maxPendingOrders, backpressureTimeoutSeconds);
                runner.run();
            } else if (autoMode) {
                AutoModeRunner runner = new AutoModeRunner(session, orderCount);
                runner.run();
            } else {
                InteractiveModeRunner runner = new InteractiveModeRunner(session);
                runner.run();
            }

            // Logout and stop
            session.logout("User requested logout");
            Thread.sleep(1000);
            provider.stop();

            return 0;

        } catch (Exception e) {
            log.error("Error in initiator", e);
            if (provider != null) {
                provider.stop();
            }
            return 1;
        }
    }

    private FixEngineConfig applyLatencyModeSettings(FixEngineConfig config) {
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
                        .logMessages(false)
                        .usePooledMessages(true)
                        .messagePoolSize(Math.max(s.getMessagePoolSize(), maxPendingOrders * 2))
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleInitiator()).execute(args);
        System.exit(exitCode);
    }
}
