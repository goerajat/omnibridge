package com.fixengine.samples.initiator;

import com.fixengine.engine.FixEngine;
import com.fixengine.engine.config.EngineConfig;
import com.fixengine.engine.config.SessionConfig;
import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.MessageListener;
import com.fixengine.engine.session.SessionState;
import com.fixengine.engine.session.SessionStateListener;
import com.fixengine.message.FixTags;
import com.fixengine.message.IncomingFixMessage;
import com.fixengine.message.OutgoingFixMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import ch.qos.logback.classic.Level;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sample FIX initiator (trading client).
 * Connects to a FIX acceptor and allows sending orders interactively.
 * Supports latency tracking mode for performance testing.
 *
 * <h2>Latency Mode</h2>
 * Use {@code --latency} flag to enable latency tracking mode, which runs:
 * <ol>
 *   <li>Warmup phase - JIT compilation warmup with configurable order count</li>
 *   <li>Latency test - Measures round-trip latency with percentile statistics</li>
 * </ol>
 *
 * <h2>Flow Control</h2>
 * In latency mode, flow control prevents overwhelming the acceptor:
 * <ul>
 *   <li>{@code --max-pending} (default: 100) - Maximum pending orders before backpressure</li>
 *   <li>{@code --backpressure-timeout} (default: 5s) - Seconds to wait in backpressure before aborting</li>
 * </ul>
 *
 * Flow control behavior:
 * <ul>
 *   <li>Tracks pending orders (sent but not yet acknowledged with ExecType=NEW)</li>
 *   <li>Pauses sending when pending orders reach threshold</li>
 *   <li>Resumes when pending count drops below threshold</li>
 *   <li>Aborts test if backpressure persists longer than timeout</li>
 * </ul>
 *
 * <h2>Rate Statistics</h2>
 * Results include throughput metrics:
 * <ul>
 *   <li>Overall rate - orders/sec including backpressure time</li>
 *   <li>Backpressure time - total time spent waiting (ms and percentage)</li>
 *   <li>Effective rate - orders/sec excluding backpressure time</li>
 * </ul>
 *
 * Example output:
 * <pre>
 *   Warmup: 10000 orders in 5234 ms
 *   Overall rate: 1911 orders/sec
 *   Backpressure time: 1200 ms (22.9%)
 *   Effective rate (excluding backpressure): 2480 orders/sec
 *
 *   Latency Statistics (microseconds):
 *   ┌─────────────┬──────────────┐
 *   │ Metric      │ Value        │
 *   ├─────────────┼──────────────┤
 *   │ Count       │        1,000 │
 *   │ Min         │        45.23 │
 *   │ Max         │     1,234.56 │
 *   │ Avg         │       123.45 │
 *   │ p50         │        98.76 │
 *   │ p90         │       234.56 │
 *   │ p95         │       345.67 │
 *   │ p99         │       567.89 │
 *   │ p99.9       │       890.12 │
 *   └─────────────┴──────────────┘
 * </pre>
 */
@Command(name = "sample-initiator", description = "Sample FIX initiator (trading client)")
public class SampleInitiator implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SampleInitiator.class);

    @Option(names = {"-h", "--host"}, description = "Target host", defaultValue = "localhost")
    private String host;

    @Option(names = {"-p", "--port"}, description = "Target port", defaultValue = "9876")
    private int port;

    @Option(names = {"-s", "--sender"}, description = "SenderCompID", defaultValue = "CLIENT")
    private String senderCompId;

    @Option(names = {"-t", "--target"}, description = "TargetCompID", defaultValue = "EXCHANGE")
    private String targetCompId;

    @Option(names = {"--heartbeat"}, description = "Heartbeat interval (seconds)", defaultValue = "30")
    private int heartbeatInterval;

    @Option(names = {"--persistence"}, description = "Persistence directory")
    private String persistencePath;

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

    private FixEngine engine;
    private FixSession session;
    private final AtomicLong clOrdIdCounter = new AtomicLong(1);
    private final CountDownLatch logonLatch = new CountDownLatch(1);
    private volatile boolean running = true;

    // Latency tracking data structures
    private final ConcurrentHashMap<String, Long> sendTimestamps = new ConcurrentHashMap<>();
    private long[] latencies;
    private final AtomicInteger latencyIndex = new AtomicInteger(0);
    private volatile boolean trackingLatency = false;
    private CountDownLatch ackLatch;

    // Flow control for pending orders
    private final AtomicInteger pendingOrders = new AtomicInteger(0);

    @Override
    public Integer call() throws Exception {
        // Configure log level for latency mode
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

        log.info("Starting Sample FIX Initiator");
        log.info("Connecting to {}:{}", host, port);
        log.info("SenderCompID: {}, TargetCompID: {}", senderCompId, targetCompId);

        try {
            // Build configuration
            SessionConfig.Builder configBuilder = SessionConfig.builder()
                    .sessionName("INITIATOR")
                    .senderCompId(senderCompId)
                    .targetCompId(targetCompId)
                    .initiator()
                    .host(host)
                    .port(port)
                    .heartbeatInterval(heartbeatInterval)
                    .resetOnLogon(true)
                    .logMessages(!latencyMode) // Disable message logging in latency mode
                    .reconnectInterval(5)
                    .maxReconnectAttempts(3);

            // Enable message pooling in latency mode for zero-allocation sending
            if (latencyMode) {
                configBuilder.usePooledMessages(true)
                        .messagePoolSize(maxPendingOrders * 2) // 2x max pending for headroom
                        .maxMessageLength(512)  // Orders are small
                        .maxTagNumber(200);     // Standard order tags
            }

            SessionConfig sessionConfig = configBuilder.build();

            EngineConfig.Builder engineConfigBuilder = EngineConfig.builder()
                    .addSession(sessionConfig);

            if (persistencePath != null) {
                engineConfigBuilder.persistencePath(persistencePath);
            }

            EngineConfig config = engineConfigBuilder.build();

            // Create engine
            engine = new FixEngine(config);

            // Add listeners
            engine.addStateListener(new InitiatorStateListener());
            engine.addMessageListener(new InitiatorMessageListener());

            // Create session and start engine
            session = engine.createSession(sessionConfig);
            engine.start();

            // Connect
            String sessionId = sessionConfig.getSessionId();
            engine.connect(sessionId);

            // Wait for logon
            if (!latencyMode) {
                log.info("Waiting for logon...");
            } else {
                System.out.println("Waiting for logon...");
            }
            if (!logonLatch.await(30, TimeUnit.SECONDS)) {
                log.error("Timeout waiting for logon");
                System.err.println("Timeout waiting for logon");
                engine.stop();
                return 1;
            }

            if (!latencyMode) {
                log.info("Logged on successfully!");
            } else {
                System.out.println("Logged on successfully!");
            }

            if (latencyMode) {
                // Latency tracking mode
                runLatencyMode();
            } else if (autoMode) {
                // Auto mode: send sample orders
                runAutoMode();
            } else {
                // Interactive mode
                runInteractiveMode();
            }

            // Logout and stop
            session.logout("User requested logout");
            Thread.sleep(1000);
            engine.stop();

            return 0;
        } catch (Exception e) {
            log.error("Error in initiator", e);
            if (engine != null) {
                engine.stop();
            }
            return 1;
        }
    }

    /**
     * Set log level for all FIX engine loggers.
     */
    private void setLogLevel(String level) {
        ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.fixengine");
        rootLogger.setLevel(Level.toLevel(level));
    }

    /**
     * Run latency tracking mode.
     */
    private void runLatencyMode() throws InterruptedException {
        System.out.println("\n========== LATENCY TRACKING MODE ==========\n");

        // Phase 1: Warmup (JIT compilation)
        System.out.println("Phase 1: Warmup (" + warmupOrders + " orders for JIT compilation)...");
        boolean warmupSuccess = runWarmup();

        if (!warmupSuccess) {
            System.out.println("\n========== TEST ABORTED (Warmup Failed) ==========\n");
            return;
        }

        System.out.println("Warmup complete.\n");

        // Small pause between phases
        Thread.sleep(10000);

        // Phase 2: Latency test
        System.out.println("Phase 2: Latency test (" + testOrders + " orders at " +
                          (ordersPerSecond > 0 ? ordersPerSecond + " orders/sec" : "max rate") + ")...");
        boolean testSuccess = runLatencyTest(testOrders, ordersPerSecond);

        if (testSuccess) {
            System.out.println("\n========== TEST COMPLETE ==========\n");
        } else {
            System.out.println("\n========== TEST ABORTED (Error During Test) ==========");
            System.out.println("Partial results shown above.\n");
        }
    }

    /**
     * Run warmup phase to trigger JIT compilation.
     * @return true if warmup completed successfully, false if an error occurred
     */
    private boolean runWarmup() throws InterruptedException {
        trackingLatency = false;
        pendingOrders.set(0);
        ackLatch = new CountDownLatch(warmupOrders);

        long startTime = System.currentTimeMillis();
        int ordersSent = 0;
        long backpressureStartTime = 0;
        long totalBackpressureTime = 0;

        // Send warmup orders as fast as possible with flow control
        for (int i = 0; i < warmupOrders && running; i++) {
            // Flow control: wait if too many pending orders
            while (pendingOrders.get() >= maxPendingOrders && running) {
                if (backpressureStartTime == 0) {
                    backpressureStartTime = System.currentTimeMillis();
                }

                long backpressureDuration = System.currentTimeMillis() - backpressureStartTime;
                if (backpressureDuration > backpressureTimeoutSeconds * 1000L) {
                    System.err.println("\nERROR: Backpressure timeout exceeded (" + backpressureTimeoutSeconds +
                                     "s) - pending orders: " + pendingOrders.get());
                    System.err.println("Warmup phase aborted after " + ordersSent + " orders.");
                    long elapsed = System.currentTimeMillis() - startTime;
                    printWarmupResults(ordersSent, elapsed, totalBackpressureTime);
                    return false;
                }
                Thread.sleep(1);
            }

            // Track backpressure time
            if (backpressureStartTime > 0) {
                totalBackpressureTime += System.currentTimeMillis() - backpressureStartTime;
                backpressureStartTime = 0;
            }

            try {
                pendingOrders.incrementAndGet();
                sendLatencyOrder("WARMUP" + i);
                ordersSent++;
            } catch (Exception e) {
                pendingOrders.decrementAndGet();
                log.error("Error sending warmup order {} - stopping warmup", i, e);
                System.err.println("\nERROR: Failed to send warmup order " + i + ": " + e.getMessage());
                System.err.println("Warmup phase aborted after " + ordersSent + " orders.");
                long elapsed = System.currentTimeMillis() - startTime;
                printWarmupResults(ordersSent, elapsed, totalBackpressureTime);
                return false;
            }

            // Brief yield every 1000 orders to prevent overwhelming
            if (i % 1000 == 0 && i > 0) {
                Thread.yield();
            }
        }

        // Wait for all acks
        boolean completed = ackLatch.await(60, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;

        if (!completed) {
            System.out.println("WARNING: Not all warmup acks received within timeout");
        }

        printWarmupResults(ordersSent, elapsed, totalBackpressureTime);
        return true;
    }

    /**
     * Print warmup phase results.
     */
    private void printWarmupResults(int ordersSent, long elapsedMs, long backpressureMs) {
        double effectiveTime = elapsedMs - backpressureMs;
        double overallRate = ordersSent * 1000.0 / elapsedMs;
        double effectiveRate = effectiveTime > 0 ? ordersSent * 1000.0 / effectiveTime : 0;

        System.out.printf("  Warmup: %d orders in %d ms%n", ordersSent, elapsedMs);
        System.out.printf("  Overall rate: %.0f orders/sec%n", overallRate);
        if (backpressureMs > 0) {
            System.out.printf("  Backpressure time: %d ms (%.1f%%)%n", backpressureMs, backpressureMs * 100.0 / elapsedMs);
            System.out.printf("  Effective rate (excluding backpressure): %.0f orders/sec%n", effectiveRate);
        }
    }

    /**
     * Run latency test with specified parameters.
     * @return true if test completed successfully, false if an error occurred
     */
    private boolean runLatencyTest(int numOrders, int rate) throws InterruptedException {
        // Initialize latency tracking
        latencies = new long[numOrders];
        latencyIndex.set(0);
        sendTimestamps.clear();
        trackingLatency = true;
        pendingOrders.set(0);
        ackLatch = new CountDownLatch(numOrders);

        long intervalNanos = rate > 0 ? 1_000_000_000L / rate : 0;
        long startTime = System.nanoTime();
        long nextSendTime = startTime;
        int ordersSent = 0;
        boolean errorOccurred = false;
        long backpressureStartTime = 0;
        long totalBackpressureTimeNanos = 0;

        // Send orders at specified rate with flow control
        for (int i = 0; i < numOrders && running; i++) {
            String clOrdId = "TEST" + i;

            // Flow control: wait if too many pending orders
            while (pendingOrders.get() >= maxPendingOrders && running) {
                if (backpressureStartTime == 0) {
                    backpressureStartTime = System.nanoTime();
                }

                long backpressureDurationMs = (System.nanoTime() - backpressureStartTime) / 1_000_000;
                if (backpressureDurationMs > backpressureTimeoutSeconds * 1000L) {
                    System.err.println("\nERROR: Backpressure timeout exceeded (" + backpressureTimeoutSeconds +
                                     "s) - pending orders: " + pendingOrders.get());
                    System.err.println("Latency test aborted after " + ordersSent + " orders.");
                    errorOccurred = true;
                    break;
                }
                Thread.sleep(1);
            }

            if (errorOccurred) {
                break;
            }

            // Track backpressure time
            if (backpressureStartTime > 0) {
                totalBackpressureTimeNanos += System.nanoTime() - backpressureStartTime;
                backpressureStartTime = 0;
            }

            // Rate limiting (adjusted for backpressure)
            if (intervalNanos > 0) {
                long now = System.nanoTime();
                long sleepNanos = nextSendTime - now;
                if (sleepNanos > 0) {
                    long sleepMillis = sleepNanos / 1_000_000;
                    int sleepNanosRemainder = (int) (sleepNanos % 1_000_000);
                    Thread.sleep(sleepMillis, sleepNanosRemainder);
                }
                nextSendTime += intervalNanos;
            }

            // Record send time and send order
            sendTimestamps.put(clOrdId, System.nanoTime());
            try {
                pendingOrders.incrementAndGet();
                sendLatencyOrder(clOrdId);
                ordersSent++;
            } catch (Exception e) {
                pendingOrders.decrementAndGet();
                log.error("Error sending latency test order {} - stopping test", i, e);
                System.err.println("\nERROR: Failed to send test order " + i + ": " + e.getMessage());
                System.err.println("Latency test aborted after " + ordersSent + " orders.");
                errorOccurred = true;
                break;
            }
        }

        long totalTimeNanos = System.nanoTime() - startTime;

        // Wait for acks (with shorter timeout if error occurred)
        int waitTimeSeconds = errorOccurred ? 10 : 60;

        // Wait for responses with timeout
        boolean completed = false;
        if (ordersSent > 0) {
            long waitStart = System.currentTimeMillis();
            while (latencyIndex.get() < ordersSent &&
                   (System.currentTimeMillis() - waitStart) < waitTimeSeconds * 1000) {
                Thread.sleep(100);
            }
            completed = latencyIndex.get() >= ordersSent;
        }

        trackingLatency = false;

        if (!completed && !errorOccurred) {
            System.out.println("WARNING: Not all acks received within timeout");
        }

        // Calculate and print results (even for partial results)
        int receivedCount = latencyIndex.get();
        if (ordersSent > 0) {
            printLatencyTestResults(ordersSent, receivedCount, totalTimeNanos, totalBackpressureTimeNanos);
            printLatencyStats(receivedCount);
        } else {
            System.out.println("  No orders were sent successfully.");
        }

        return !errorOccurred;
    }

    /**
     * Print latency test results including rate statistics.
     */
    private void printLatencyTestResults(int ordersSent, int acksReceived, long totalTimeNanos, long backpressureTimeNanos) {
        double totalTimeMs = totalTimeNanos / 1_000_000.0;
        double backpressureTimeMs = backpressureTimeNanos / 1_000_000.0;
        double effectiveTimeMs = totalTimeMs - backpressureTimeMs;
        double overallRate = ordersSent * 1000.0 / totalTimeMs;
        double effectiveRate = effectiveTimeMs > 0 ? ordersSent * 1000.0 / effectiveTimeMs : 0;

        System.out.printf("  Sent: %d orders, Received: %d acks in %.2f ms%n", ordersSent, acksReceived, totalTimeMs);
        System.out.printf("  Overall rate: %.0f orders/sec%n", overallRate);

        if (backpressureTimeMs > 0) {
            System.out.printf("  Backpressure time: %.0f ms (%.1f%%)%n", backpressureTimeMs, backpressureTimeMs * 100.0 / totalTimeMs);
            System.out.printf("  Effective rate (excluding backpressure): %.0f orders/sec%n", effectiveRate);
        }
    }

    /**
     * Calculate and print latency statistics.
     */
    private void printLatencyStats(int count) {
        if (count == 0) {
            System.out.println("  No latency data collected");
            return;
        }

        // Sort latencies for percentile calculation
        long[] sorted = Arrays.copyOf(latencies, count);
        Arrays.sort(sorted);

        // Calculate statistics
        long min = sorted[0];
        long max = sorted[count - 1];
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += sorted[i];
        }
        double avg = (double) sum / count;

        // Percentiles
        long p50 = sorted[(int) (count * 0.50)];
        long p90 = sorted[(int) (count * 0.90)];
        long p95 = sorted[(int) (count * 0.95)];
        long p99 = sorted[(int) (count * 0.99)];
        long p999 = sorted[(int) Math.min(count * 0.999, count - 1)];

        System.out.println("\n  Latency Statistics (microseconds):");
        System.out.println("  ┌─────────────┬──────────────┐");
        System.out.println("  │ Metric      │ Value        │");
        System.out.println("  ├─────────────┼──────────────┤");
        System.out.printf("  │ Count       │ %,12d │%n", count);
        System.out.printf("  │ Min         │ %,12.2f │%n", min / 1000.0);
        System.out.printf("  │ Max         │ %,12.2f │%n", max / 1000.0);
        System.out.printf("  │ Avg         │ %,12.2f │%n", avg / 1000.0);
        System.out.printf("  │ p50         │ %,12.2f │%n", p50 / 1000.0);
        System.out.printf("  │ p90         │ %,12.2f │%n", p90 / 1000.0);
        System.out.printf("  │ p95         │ %,12.2f │%n", p95 / 1000.0);
        System.out.printf("  │ p99         │ %,12.2f │%n", p99 / 1000.0);
        System.out.printf("  │ p99.9       │ %,12.2f │%n", p999 / 1000.0);
        System.out.println("  └─────────────┴──────────────┘");
    }

    /**
     * Send an order for latency testing (minimal overhead).
     * Uses OutgoingFixMessage for sending.
     * Throws exception on failure to allow caller to handle it.
     */
    private void sendLatencyOrder(String clOrdId) throws Exception {
        OutgoingFixMessage order = session.acquireMessage(FixTags.MsgTypes.NewOrderSingle);
        order.setField(FixTags.ClOrdID, clOrdId);
        order.setField(FixTags.Symbol, "TEST");
        order.setField(FixTags.Side, FixTags.SIDE_BUY);
        order.setField(FixTags.OrderQty, 100);
        order.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
        order.setField(FixTags.Price, 100.00, 2);
        order.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
        // Skip TransactTime to reduce allocations - it's optional for testing
        session.send(order);  // Auto-releases back to pool
    }

    /**
     * Run in auto mode, sending sample orders.
     */
    private void runAutoMode() throws InterruptedException {
        log.info("Auto mode: sending {} sample orders", orderCount);

        String[] symbols = {"AAPL", "GOOGL", "MSFT", "AMZN", "META"};

        for (int i = 0; i < orderCount && running; i++) {
            String symbol = symbols[i % symbols.length];
            char side = (i % 2 == 0) ? FixTags.SIDE_BUY : FixTags.SIDE_SELL;
            int qty = (i + 1) * 100;
            double price = 100.0 + (i * 0.5);

            sendNewOrder(symbol, side, qty, FixTags.ORD_TYPE_LIMIT, price);

            Thread.sleep(500); // Wait between orders
        }

        log.info("Finished sending {} orders", orderCount);
        Thread.sleep(2000); // Wait for responses
    }

    /**
     * Run in interactive mode, accepting user commands.
     */
    private void runInteractiveMode() {
        log.info("Interactive mode. Commands:");
        log.info("  buy <symbol> <qty> [price]   - Send buy order");
        log.info("  sell <symbol> <qty> [price]  - Send sell order");
        log.info("  cancel <clOrdId>             - Cancel order");
        log.info("  quit                         - Exit");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (running) {
            try {
                System.out.print("> ");
                String line = reader.readLine();
                if (line == null) break;

                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                String cmd = parts[0].toLowerCase();

                switch (cmd) {
                    case "buy":
                        if (parts.length >= 3) {
                            String symbol = parts[1];
                            int qty = Integer.parseInt(parts[2]);
                            double price = parts.length > 3 ? Double.parseDouble(parts[3]) : 0;
                            char ordType = price > 0 ? FixTags.ORD_TYPE_LIMIT : FixTags.ORD_TYPE_MARKET;
                            sendNewOrder(symbol, FixTags.SIDE_BUY, qty, ordType, price);
                        } else {
                            log.info("Usage: buy <symbol> <qty> [price]");
                        }
                        break;

                    case "sell":
                        if (parts.length >= 3) {
                            String symbol = parts[1];
                            int qty = Integer.parseInt(parts[2]);
                            double price = parts.length > 3 ? Double.parseDouble(parts[3]) : 0;
                            char ordType = price > 0 ? FixTags.ORD_TYPE_LIMIT : FixTags.ORD_TYPE_MARKET;
                            sendNewOrder(symbol, FixTags.SIDE_SELL, qty, ordType, price);
                        } else {
                            log.info("Usage: sell <symbol> <qty> [price]");
                        }
                        break;

                    case "cancel":
                        if (parts.length >= 2) {
                            sendCancelRequest(parts[1]);
                        } else {
                            log.info("Usage: cancel <clOrdId>");
                        }
                        break;

                    case "quit":
                    case "exit":
                        running = false;
                        break;

                    default:
                        log.info("Unknown command: {}", cmd);
                }
            } catch (Exception e) {
                log.error("Error processing command", e);
            }
        }
    }

    /**
     * Send a new order.
     */
    private void sendNewOrder(String symbol, char side, int qty, char ordType, double price) {
        String clOrdId = "ORDER" + clOrdIdCounter.getAndIncrement();

        try {
            OutgoingFixMessage order = session.acquireMessage(FixTags.MsgTypes.NewOrderSingle);
            order.setField(FixTags.ClOrdID, clOrdId);
            order.setField(FixTags.Symbol, symbol);
            order.setField(FixTags.Side, side);
            order.setField(FixTags.OrderQty, qty);
            order.setField(FixTags.OrdType, ordType);
            order.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
            order.setField(FixTags.TransactTime, java.time.Instant.now().toString());

            if (ordType == FixTags.ORD_TYPE_LIMIT && price > 0) {
                order.setField(FixTags.Price, price, 2);
            }

            int seqNum = session.send(order);
            log.info("Sent NewOrderSingle: ClOrdID={}, Symbol={}, Side={}, Qty={}, Price={}, SeqNum={}",
                    clOrdId, symbol, side == FixTags.SIDE_BUY ? "BUY" : "SELL", qty, price, seqNum);
        } catch (Exception e) {
            log.error("Error sending order", e);
        }
    }

    /**
     * Send a cancel request.
     */
    private void sendCancelRequest(String origClOrdId) {
        String clOrdId = "CANCEL" + clOrdIdCounter.getAndIncrement();

        try {
            OutgoingFixMessage cancel = session.acquireMessage(FixTags.MsgTypes.OrderCancelRequest);
            cancel.setField(FixTags.ClOrdID, clOrdId);
            cancel.setField(41, origClOrdId); // OrigClOrdID
            cancel.setField(FixTags.Symbol, "UNKNOWN"); // In real app, would lookup
            cancel.setField(FixTags.Side, FixTags.SIDE_BUY); // In real app, would lookup
            cancel.setField(FixTags.TransactTime, java.time.Instant.now().toString());

            int seqNum = session.send(cancel);
            log.info("Sent OrderCancelRequest: ClOrdID={}, OrigClOrdID={}, SeqNum={}",
                    clOrdId, origClOrdId, seqNum);
        } catch (Exception e) {
            log.error("Error sending cancel request", e);
        }
    }

    /**
     * Session state listener.
     */
    private class InitiatorStateListener implements SessionStateListener {
        @Override
        public void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState) {
            log.info("Session state changed: {} -> {}", oldState, newState);
        }

        @Override
        public void onSessionLogon(FixSession session) {
            log.info("Logged on!");
            logonLatch.countDown();
        }

        @Override
        public void onSessionLogout(FixSession session, String reason) {
            log.info("Logged out: {}", reason);
            running = false;
        }

        @Override
        public void onSessionDisconnected(FixSession session, Throwable reason) {
            log.info("Disconnected: {}", reason != null ? reason.getMessage() : "clean disconnect");
        }

        @Override
        public void onSessionError(FixSession session, Throwable error) {
            log.error("Session error", error);
        }
    }

    /**
     * Message listener for execution reports.
     */
    private class InitiatorMessageListener implements MessageListener {
        @Override
        public void onMessage(FixSession session, IncomingFixMessage message) {
            long receiveTime = System.nanoTime();
            String msgType = message.getMsgType();

            if (FixTags.MsgTypes.ExecutionReport.equals(msgType)) {
                handleExecutionReport(message, receiveTime);
            } else if (FixTags.MsgTypes.OrderCancelReject.equals(msgType)) {
                handleCancelReject(message);
            } else {
                log.info("Received message: {}", msgType);
            }
        }

        @Override
        public void onReject(FixSession session, int refSeqNum, String refMsgType, int rejectReason, String text) {
            log.warn("Message {} (type {}) rejected: {} - {}", refSeqNum, refMsgType, rejectReason, text);
        }
    }

    /**
     * Handle an execution report.
     */
    private void handleExecutionReport(IncomingFixMessage execReport, long receiveTime) {
        String clOrdId = asString(execReport.getCharSequence(FixTags.ClOrdID));
        char execType = execReport.getChar(FixTags.ExecType);

        // Handle NEW acks (first response to our order)
        if (execType == FixTags.EXEC_TYPE_NEW) {
            // Track latency during latency test phase
            if (trackingLatency && clOrdId != null) {
                Long sendTime = sendTimestamps.remove(clOrdId);
                if (sendTime != null) {
                    long latencyNanos = receiveTime - sendTime;
                    int idx = latencyIndex.getAndIncrement();
                    if (idx < latencies.length) {
                        latencies[idx] = latencyNanos;
                    }
                }
            }
            // Decrement pending orders count in latency mode (warmup or test)
            if (latencyMode) {
                pendingOrders.decrementAndGet();
            }
        }

        // Count down ack latch for any execution report
        if (ackLatch != null) {
            ackLatch.countDown();
        }

        // Only log in non-latency mode
        if (!latencyMode) {
            String orderId = asString(execReport.getCharSequence(FixTags.OrderID));
            char ordStatus = execReport.getChar(FixTags.OrdStatus);
            String symbol = asString(execReport.getCharSequence(FixTags.Symbol));
            double cumQty = execReport.getDouble(FixTags.CumQty);
            double leavesQty = execReport.getDouble(FixTags.LeavesQty);
            double avgPx = execReport.getDouble(FixTags.AvgPx);
            String text = asString(execReport.getCharSequence(FixTags.Text));

            String execTypeStr = switch (execType) {
                case FixTags.EXEC_TYPE_NEW -> "NEW";
                case FixTags.EXEC_TYPE_PARTIAL_FILL -> "PARTIAL_FILL";
                case FixTags.EXEC_TYPE_FILL -> "FILL";
                case FixTags.EXEC_TYPE_CANCELED -> "CANCELED";
                case FixTags.EXEC_TYPE_REPLACED -> "REPLACED";
                case FixTags.EXEC_TYPE_REJECTED -> "REJECTED";
                default -> String.valueOf(execType);
            };

            log.info("ExecutionReport: ClOrdID={}, OrdID={}, ExecType={}, Status={}, " +
                            "Symbol={}, CumQty={}, LeavesQty={}, AvgPx={}, Text={}",
                    clOrdId, orderId, execTypeStr, ordStatus, symbol, cumQty, leavesQty, avgPx, text);
        }
    }

    /**
     * Handle a cancel reject.
     */
    private void handleCancelReject(IncomingFixMessage reject) {
        String clOrdId = asString(reject.getCharSequence(FixTags.ClOrdID));
        String orderId = asString(reject.getCharSequence(FixTags.OrderID));
        int rejectReason = reject.getInt(102); // CxlRejReason
        String text = asString(reject.getCharSequence(FixTags.Text));

        log.warn("OrderCancelReject: ClOrdID={}, OrdID={}, Reason={}, Text={}",
                clOrdId, orderId, rejectReason, text);
    }

    private static String asString(CharSequence cs) {
        return cs != null ? cs.toString() : null;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleInitiator()).execute(args);
        System.exit(exitCode);
    }
}
