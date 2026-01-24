package com.omnibridge.apps.fix.initiator;

import com.omnibridge.apps.common.ApplicationBase;
import com.omnibridge.fix.engine.FixEngine;
import com.omnibridge.fix.engine.session.FixSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sample FIX initiator (trading client).
 * Connects to a FIX acceptor and allows sending orders.
 * Supports interactive mode, auto mode, and latency testing mode.
 */
@Command(name = "sample-initiator", description = "Sample FIX initiator (trading client)")
public class SampleInitiator extends ApplicationBase {

    private static final Logger log = LoggerFactory.getLogger(SampleInitiator.class);

    @Option(names = {"-c", "--config"}, description = "Configuration file(s)", split = ",")
    private List<String> configFiles = new ArrayList<>();

    @Option(names = {"--auto"}, description = "Auto-send sample orders", defaultValue = "false")
    private boolean autoMode;

    @Option(names = {"--count"}, description = "Number of orders to send in auto mode", defaultValue = "10")
    private int orderCount;

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

    private CountDownLatch logonLatch;
    private InitiatorMessageListener messageListener;

    @Override
    protected List<String> getConfigFiles() {
        if (configFiles.isEmpty()) {
            configFiles.add("initiator.conf");
        }
        return configFiles;
    }

    @Override
    protected EngineType getEngineType() {
        return EngineType.FIX;
    }

    @Override
    protected void configureFix(FixEngine engine, List<FixSession> sessions) {
        if (latencyMode) {
            setLogLevel("ERROR");
            System.out.println("Latency tracking mode enabled - log level set to ERROR");
            System.out.println("Warmup orders: " + warmupOrders);
            System.out.println("Test orders: " + testOrders);
            System.out.println("Rate: " + (ordersPerSecond > 0 ? ordersPerSecond + " orders/sec" : "unlimited"));
            System.out.println("Max pending orders: " + maxPendingOrders);
            System.out.println("Backpressure timeout: " + backpressureTimeoutSeconds + " seconds");
        }

        logonLatch = new CountDownLatch(1);
        InitiatorStateListener stateListener = new InitiatorStateListener(logonLatch, reason -> running = false);
        messageListener = new InitiatorMessageListener(latencyMode);

        engine.addStateListener(stateListener);
        engine.addMessageListener(messageListener);
    }

    @Override
    protected int runFix(FixEngine engine, List<FixSession> sessions) throws Exception {
        FixSession session = sessions.get(0);

        // Connect to acceptor
        engine.connect(session.getConfig().getSessionId());

        // Wait for logon
        if (latencyMode) {
            System.out.println("Waiting for logon...");
        } else {
            log.info("Waiting for logon...");
        }

        if (!logonLatch.await(30, TimeUnit.SECONDS)) {
            System.err.println("Timeout waiting for logon");
            return 1;
        }

        if (latencyMode) {
            System.out.println("Logged on successfully!");
        } else {
            log.info("Logged on successfully!");
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

        // Logout gracefully
        session.logout("User requested logout");
        Thread.sleep(1000);

        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleInitiator()).execute(args);
        System.exit(exitCode);
    }
}
