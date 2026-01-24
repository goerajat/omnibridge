package com.omnibridge.apps.ouch.initiator;

import com.omnibridge.apps.common.ApplicationBase;
import com.omnibridge.apps.common.LatencyTracker;
import com.omnibridge.ouch.engine.OuchEngine;
import com.omnibridge.ouch.engine.session.OuchSession;
import com.omnibridge.ouch.engine.session.SessionState;
import com.omnibridge.ouch.message.Side;
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
 * Sample OUCH initiator (trading client).
 *
 * <p>Connects to an OUCH acceptor and sends orders using the high-level OuchSession API.
 * Supports both OUCH 4.2 and 5.0 protocols - the version is configured in the session
 * configuration file.</p>
 *
 * <p>The same code works for both protocol versions because OuchSession abstracts
 * away the differences between Order Token (4.2) and UserRefNum (5.0).</p>
 */
@Command(name = "ouch-initiator", description = "Sample OUCH initiator (trading client)")
public class SampleOuchInitiator extends ApplicationBase {

    private static final Logger log = LoggerFactory.getLogger(SampleOuchInitiator.class);

    @Option(names = {"-c", "--config"}, description = "Configuration file(s)", split = ",")
    private List<String> configFiles = new ArrayList<>();

    @Option(names = {"--latency"}, description = "Enable latency test mode", defaultValue = "false")
    private boolean latencyMode;

    @Option(names = {"--warmup-orders"}, description = "Warmup order count", defaultValue = "1000")
    private int warmupOrders;

    @Option(names = {"--test-orders"}, description = "Test order count", defaultValue = "1000")
    private int testOrders;

    @Option(names = {"--rate"}, description = "Orders per second", defaultValue = "100")
    private int rate;

    @Option(names = {"--auto"}, description = "Auto-send sample orders", defaultValue = "false")
    private boolean autoMode;

    @Option(names = {"--count"}, description = "Number of orders in auto mode", defaultValue = "10")
    private int orderCount;

    private InitiatorMessageHandler messageHandler;
    private CountDownLatch loginLatch;

    @Override
    protected List<String> getConfigFiles() {
        if (configFiles.isEmpty()) {
            configFiles.add("ouch-initiator.conf");
        }
        return configFiles;
    }

    @Override
    protected EngineType getEngineType() {
        return EngineType.OUCH;
    }

    @Override
    protected void configureOuch(OuchEngine engine, List<OuchSession> sessions) {
        if (latencyMode) {
            setLogLevel("ERROR");
            System.out.println("Latency mode enabled - log level set to ERROR");
            System.out.println("Warmup: " + warmupOrders + " orders");
            System.out.println("Test: " + testOrders + " orders");
            System.out.println("Rate: " + rate + " orders/sec");
        }

        loginLatch = new CountDownLatch(sessions.size());
        messageHandler = new InitiatorMessageHandler(latencyMode);

        // Add listeners to all sessions
        for (OuchSession session : sessions) {
            log.info("Configuring session: {} (protocol: {})",
                    session.getSessionId(), session.getProtocolVersion());

            // Add state listener
            session.addStateListener((sess, oldState, newState) -> {
                log.info("Session {} state: {} -> {}", sess.getSessionId(), oldState, newState);
                if (newState == SessionState.LOGGED_IN) {
                    loginLatch.countDown();
                }
            });

            // Add typed message listener
            session.addMessageListener(messageHandler);
        }
    }

    @Override
    protected int runOuch(OuchEngine engine, List<OuchSession> sessions) throws Exception {
        if (sessions.isEmpty()) {
            log.error("No sessions configured");
            return 1;
        }

        OuchSession session = sessions.get(0);

        // Connect to acceptor
        log.info("Connecting to {}:{}...", session.getHost(), session.getPort());
        engine.connect(session.getSessionId());

        // Wait for login
        if (latencyMode) {
            System.out.println("Waiting for login...");
        } else {
            log.info("Waiting for login...");
        }

        if (!loginLatch.await(30, TimeUnit.SECONDS)) {
            System.err.println("Timeout waiting for login");
            return 1;
        }

        if (session.getState() != SessionState.LOGGED_IN) {
            log.error("Failed to log in - session state: {}", session.getState());
            return 1;
        }

        if (latencyMode) {
            System.out.println("Logged in successfully!");
            System.out.println("Protocol version: " + session.getProtocolVersion());
        } else {
            log.info("Logged in successfully! Protocol version: {}", session.getProtocolVersion());
        }

        // Run the selected mode
        if (latencyMode) {
            runLatencyTest(session);
        } else if (autoMode) {
            runAutoMode(session);
        } else {
            runInteractiveMode(session);
        }

        // Disconnect gracefully
        session.disconnect();
        Thread.sleep(1000);

        return 0;
    }

    private void runLatencyTest(OuchSession session) throws InterruptedException {
        if (latencyMode) {
            System.out.println("\n========== LATENCY TEST MODE ==========");
        }
        log.info("Running latency test");
        log.info("Warmup: {} orders, Test: {} orders, Rate: {} orders/sec",
                warmupOrders, testOrders, rate);

        long intervalNanos = 1_000_000_000L / rate;

        // Warmup phase
        if (latencyMode) {
            System.out.println("Starting warmup phase (" + warmupOrders + " orders)...");
        }
        log.info("Starting warmup phase ({} orders)...", warmupOrders);

        for (int i = 0; i < warmupOrders; i++) {
            // Use high-level sendEnterOrder - works for both V4.2 and V5.0
            session.sendEnterOrder(Side.BUY, "AAPL", 100, 150.00);
            Thread.sleep(intervalNanos / 1_000_000);
        }
        Thread.sleep(1000); // Let warmup settle

        // Test phase with latency tracking
        LatencyTracker tracker = new LatencyTracker(testOrders);
        messageHandler.setLatencyTracker(tracker);
        tracker.reset();

        if (latencyMode) {
            System.out.println("Starting test phase (" + testOrders + " orders)...");
        }
        log.info("Starting test phase ({} orders)...", testOrders);
        long testStart = System.nanoTime();

        for (int i = 0; i < testOrders; i++) {
            int idx = tracker.recordSend();

            // Send order using high-level API
            String token = session.sendEnterOrder(Side.BUY, "AAPL", 100, 150.00);

            if (token == null) {
                log.warn("Failed to send order {}", i);
            }

            // Rate limiting
            long elapsed = System.nanoTime() - testStart;
            long expected = (long) i * intervalNanos;
            if (expected > elapsed) {
                long sleepNanos = expected - elapsed;
                Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
            }
        }

        // Wait for responses
        Thread.sleep(5000);
        tracker.complete();
        messageHandler.setLatencyTracker(null);

        // Print results
        if (latencyMode) {
            tracker.printStatisticsToStdout();
        } else {
            tracker.printStatistics();
        }
    }

    private void runAutoMode(OuchSession session) throws InterruptedException {
        log.info("Auto mode: sending {} sample orders", orderCount);

        String[] symbols = {"AAPL", "GOOGL", "MSFT", "AMZN", "META"};

        for (int i = 0; i < orderCount && isRunning(); i++) {
            String symbol = symbols[i % symbols.length];
            Side side = (i % 2 == 0) ? Side.BUY : Side.SELL;
            int qty = (i + 1) * 100;
            double price = 100.0 + (i * 0.5);

            // Use high-level API - works for both V4.2 and V5.0
            String token = session.sendEnterOrder(side, symbol, qty, price);
            log.info("Sent order: token={}, symbol={}, side={}, qty={}, price={}",
                    token, symbol, side, qty, price);

            Thread.sleep(500);
        }

        log.info("Finished sending {} orders", orderCount);
        Thread.sleep(2000);
    }

    private void runInteractiveMode(OuchSession session) throws InterruptedException {
        log.info("Interactive mode - sending a few test orders");

        // Send a few test orders
        for (int i = 0; i < 5; i++) {
            String token = session.sendEnterOrder(Side.BUY, "AAPL", 100, 150.00 + i);
            log.info("Sent order: {}", token);
            Thread.sleep(500);
        }

        // Wait for responses
        Thread.sleep(2000);
        log.info("Test complete");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleOuchInitiator()).execute(args);
        System.exit(exitCode);
    }
}
