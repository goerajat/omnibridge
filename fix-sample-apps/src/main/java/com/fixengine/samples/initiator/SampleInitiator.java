package com.fixengine.samples.initiator;

import com.fixengine.engine.FixEngine;
import com.fixengine.engine.config.EngineConfig;
import com.fixengine.engine.config.SessionConfig;
import com.fixengine.engine.session.FixSession;
import com.fixengine.engine.session.MessageListener;
import com.fixengine.engine.session.SessionState;
import com.fixengine.engine.session.SessionStateListener;
import com.fixengine.message.FixMessage;
import com.fixengine.message.FixTags;
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

    @Override
    public Integer call() throws Exception {
        // Configure log level for latency mode
        if (latencyMode) {
            setLogLevel("ERROR");
            System.out.println("Latency tracking mode enabled - log level set to ERROR");
            System.out.println("Warmup orders: " + warmupOrders);
            System.out.println("Test orders: " + testOrders);
            System.out.println("Rate: " + (ordersPerSecond > 0 ? ordersPerSecond + " orders/sec" : "unlimited"));
        }

        log.info("Starting Sample FIX Initiator");
        log.info("Connecting to {}:{}", host, port);
        log.info("SenderCompID: {}, TargetCompID: {}", senderCompId, targetCompId);

        try {
            // Build configuration
            SessionConfig sessionConfig = SessionConfig.builder()
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
                    .maxReconnectAttempts(3)
                    .build();

            EngineConfig.Builder configBuilder = EngineConfig.builder()
                    .addSession(sessionConfig);

            if (persistencePath != null) {
                configBuilder.persistencePath(persistencePath);
            }

            EngineConfig config = configBuilder.build();

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
        runWarmup();
        System.out.println("Warmup complete.\n");

        // Small pause between phases
        Thread.sleep(1000);

        // Phase 2: Latency test
        System.out.println("Phase 2: Latency test (" + testOrders + " orders at " +
                          (ordersPerSecond > 0 ? ordersPerSecond + " orders/sec" : "max rate") + ")...");
        runLatencyTest(testOrders, ordersPerSecond);

        System.out.println("\n========== TEST COMPLETE ==========\n");
    }

    /**
     * Run warmup phase to trigger JIT compilation.
     */
    private void runWarmup() throws InterruptedException {
        trackingLatency = false;
        ackLatch = new CountDownLatch(warmupOrders);

        long startTime = System.currentTimeMillis();

        // Send warmup orders as fast as possible
        for (int i = 0; i < warmupOrders && running; i++) {
            sendLatencyOrder("WARMUP" + i);

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

        System.out.printf("  Warmup: %d orders in %d ms (%.0f orders/sec)%n",
                         warmupOrders, elapsed, warmupOrders * 1000.0 / elapsed);
    }

    /**
     * Run latency test with specified parameters.
     */
    private void runLatencyTest(int numOrders, int rate) throws InterruptedException {
        // Initialize latency tracking
        latencies = new long[numOrders];
        latencyIndex.set(0);
        sendTimestamps.clear();
        trackingLatency = true;
        ackLatch = new CountDownLatch(numOrders);

        long intervalNanos = rate > 0 ? 1_000_000_000L / rate : 0;
        long startTime = System.nanoTime();
        long nextSendTime = startTime;

        // Send orders at specified rate
        for (int i = 0; i < numOrders && running; i++) {
            String clOrdId = "TEST" + i;

            // Rate limiting
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
            sendLatencyOrder(clOrdId);
        }

        // Wait for all acks
        boolean completed = ackLatch.await(60, TimeUnit.SECONDS);
        long totalTimeNanos = System.nanoTime() - startTime;

        trackingLatency = false;

        if (!completed) {
            System.out.println("WARNING: Not all acks received within timeout");
        }

        // Calculate and print results
        int receivedCount = latencyIndex.get();
        System.out.printf("  Sent: %d orders, Received: %d acks in %.2f ms%n",
                         numOrders, receivedCount, totalTimeNanos / 1_000_000.0);
        System.out.printf("  Throughput: %.0f orders/sec%n",
                         receivedCount * 1_000_000_000.0 / totalTimeNanos);

        printLatencyStats(receivedCount);
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
     */
    private void sendLatencyOrder(String clOrdId) {
        FixMessage order = new FixMessage();
        order.setMsgType(FixTags.MsgTypes.NewOrderSingle);
        order.setField(FixTags.ClOrdID, clOrdId);
        order.setField(FixTags.Symbol, "TEST");
        order.setField(FixTags.Side, FixTags.SIDE_BUY);
        order.setField(FixTags.OrderQty, 100);
        order.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
        order.setField(FixTags.Price, 100.0);
        order.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
        order.setField(FixTags.TransactTime, java.time.Instant.now().toString());

        try {
            session.send(order);
        } catch (Exception e) {
            log.error("Error sending latency order", e);
        }
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

        FixMessage order = new FixMessage();
        order.setMsgType(FixTags.MsgTypes.NewOrderSingle);
        order.setField(FixTags.ClOrdID, clOrdId);
        order.setField(FixTags.Symbol, symbol);
        order.setField(FixTags.Side, side);
        order.setField(FixTags.OrderQty, qty);
        order.setField(FixTags.OrdType, ordType);
        order.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
        order.setField(FixTags.TransactTime, java.time.Instant.now().toString());

        if (ordType == FixTags.ORD_TYPE_LIMIT && price > 0) {
            order.setField(FixTags.Price, price);
        }

        try {
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

        FixMessage cancel = new FixMessage();
        cancel.setMsgType(FixTags.MsgTypes.OrderCancelRequest);
        cancel.setField(FixTags.ClOrdID, clOrdId);
        cancel.setField(41, origClOrdId); // OrigClOrdID
        cancel.setField(FixTags.Symbol, "UNKNOWN"); // In real app, would lookup
        cancel.setField(FixTags.Side, FixTags.SIDE_BUY); // In real app, would lookup
        cancel.setField(FixTags.TransactTime, java.time.Instant.now().toString());

        try {
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
        public void onMessage(FixSession session, FixMessage message) {
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
    private void handleExecutionReport(FixMessage execReport, long receiveTime) {
        String clOrdId = execReport.getString(FixTags.ClOrdID);
        char execType = execReport.getChar(FixTags.ExecType);

        // Track latency for NEW acks only (first response to our order)
        if (trackingLatency && execType == FixTags.EXEC_TYPE_NEW) {
            Long sendTime = sendTimestamps.remove(clOrdId);
            if (sendTime != null) {
                long latencyNanos = receiveTime - sendTime;
                int idx = latencyIndex.getAndIncrement();
                if (idx < latencies.length) {
                    latencies[idx] = latencyNanos;
                }
            }
        }

        // Count down ack latch for any execution report
        if (ackLatch != null) {
            ackLatch.countDown();
        }

        // Only log in non-latency mode
        if (!latencyMode) {
            String orderId = execReport.getString(FixTags.OrderID);
            char ordStatus = execReport.getChar(FixTags.OrdStatus);
            String symbol = execReport.getString(FixTags.Symbol);
            double cumQty = execReport.getDouble(FixTags.CumQty);
            double leavesQty = execReport.getDouble(FixTags.LeavesQty);
            double avgPx = execReport.getDouble(FixTags.AvgPx);
            String text = execReport.getString(FixTags.Text);

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
    private void handleCancelReject(FixMessage reject) {
        String clOrdId = reject.getString(FixTags.ClOrdID);
        String orderId = reject.getString(FixTags.OrderID);
        int rejectReason = reject.getInt(102); // CxlRejReason
        String text = reject.getString(FixTags.Text);

        log.warn("OrderCancelReject: ClOrdID={}, OrdID={}, Reason={}, Text={}",
                clOrdId, orderId, rejectReason, text);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleInitiator()).execute(args);
        System.exit(exitCode);
    }
}
