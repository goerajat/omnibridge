package com.fixengine.samples.acceptor;

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

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sample FIX acceptor (exchange simulator).
 * Accepts incoming FIX connections and simulates order handling.
 */
@Command(name = "sample-acceptor", description = "Sample FIX acceptor (exchange simulator)")
public class SampleAcceptor implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SampleAcceptor.class);

    @Option(names = {"-p", "--port"}, description = "Listen port", defaultValue = "9876")
    private int port;

    @Option(names = {"-s", "--sender"}, description = "SenderCompID", defaultValue = "EXCHANGE")
    private String senderCompId;

    @Option(names = {"-t", "--target"}, description = "TargetCompID", defaultValue = "CLIENT")
    private String targetCompId;

    @Option(names = {"--heartbeat"}, description = "Heartbeat interval (seconds)", defaultValue = "30")
    private int heartbeatInterval;

    @Option(names = {"--persistence"}, description = "Persistence directory")
    private String persistencePath;

    @Option(names = {"--fill-rate"}, description = "Probability of immediate fill (0.0-1.0)", defaultValue = "0.8")
    private double fillRate;

    @Option(names = {"--latency"}, description = "Enable latency mode (ERROR log level, no message logging)", defaultValue = "false")
    private boolean latencyMode;

    private FixEngine engine;
    private final AtomicLong execIdCounter = new AtomicLong(1);
    private final AtomicLong orderIdCounter = new AtomicLong(1);

    @Override
    public Integer call() throws Exception {
        // Configure log level for latency mode
        if (latencyMode) {
            setLogLevel("ERROR");
            System.out.println("Latency mode enabled - log level set to ERROR");
        }

        log.info("Starting Sample FIX Acceptor on port {}", port);
        log.info("SenderCompID: {}, TargetCompID: {}", senderCompId, targetCompId);

        try {
            // Build configuration
            SessionConfig sessionConfig = SessionConfig.builder()
                    .sessionName("ACCEPTOR")
                    .senderCompId(senderCompId)
                    .targetCompId(targetCompId)
                    .acceptor()
                    .port(port)
                    .heartbeatInterval(heartbeatInterval)
                    .resetOnLogon(true)
                    .logMessages(!latencyMode) // Disable message logging in latency mode
                    .build();

            EngineConfig.Builder configBuilder = EngineConfig.builder()
                    .addSession(sessionConfig);

            if (persistencePath != null) {
                configBuilder.persistencePath(persistencePath);
            }

            EngineConfig config = configBuilder.build();

            // Create and start engine
            engine = new FixEngine(config);

            // Add listeners
            engine.addStateListener(new AcceptorStateListener());
            engine.addMessageListener(new AcceptorMessageListener());

            engine.start();

            log.info("Acceptor started. Press Ctrl+C to stop.");

            // Wait for shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown signal received");
                if (engine != null) {
                    engine.stop();
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

    /**
     * Set log level for all FIX engine loggers.
     */
    private void setLogLevel(String level) {
        ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.fixengine");
        rootLogger.setLevel(Level.toLevel(level));
    }

    /**
     * Session state listener.
     */
    private class AcceptorStateListener implements SessionStateListener {
        @Override
        public void onSessionStateChange(FixSession session, SessionState oldState, SessionState newState) {
            log.info("Session {} state changed: {} -> {}", session.getConfig().getSessionId(), oldState, newState);
        }

        @Override
        public void onSessionLogon(FixSession session) {
            log.info("Session {} logged on", session.getConfig().getSessionId());
        }

        @Override
        public void onSessionLogout(FixSession session, String reason) {
            log.info("Session {} logged out: {}", session.getConfig().getSessionId(), reason);
        }

        @Override
        public void onSessionError(FixSession session, Throwable error) {
            log.error("Session {} error", session.getConfig().getSessionId(), error);
        }
    }

    /**
     * Message listener for order handling.
     */
    private class AcceptorMessageListener implements MessageListener {
        @Override
        public void onMessage(FixSession session, FixMessage message) {
            String msgType = message.getMsgType();
            log.info("Received message: {} from {}", msgType, session.getConfig().getSessionId());

            switch (msgType) {
                case FixTags.MsgTypes.NewOrderSingle:
                    handleNewOrder(session, message);
                    break;
                case FixTags.MsgTypes.OrderCancelRequest:
                    handleOrderCancel(session, message);
                    break;
                case FixTags.MsgTypes.OrderCancelReplaceRequest:
                    handleOrderReplace(session, message);
                    break;
                default:
                    log.warn("Unknown message type: {}", msgType);
            }
        }

        @Override
        public void onReject(FixSession session, int refSeqNum, String refMsgType, int rejectReason, String text) {
            log.warn("Message {} (type {}) rejected: {} - {}", refSeqNum, refMsgType, rejectReason, text);
        }
    }

    /**
     * Handle a new order.
     */
    private void handleNewOrder(FixSession session, FixMessage order) {
        String clOrdId = order.getString(FixTags.ClOrdID);
        String symbol = order.getString(FixTags.Symbol);
        char side = order.getChar(FixTags.Side);
        double orderQty = order.getDouble(FixTags.OrderQty);
        char ordType = order.getChar(FixTags.OrdType);
        double price = order.getDouble(FixTags.Price);

        log.info("New Order: ClOrdID={}, Symbol={}, Side={}, Qty={}, Type={}, Price={}",
                clOrdId, symbol, side, orderQty, ordType, price);

        String orderId = "ORD" + orderIdCounter.getAndIncrement();

        // Send acknowledgment (New)
        sendExecutionReport(session, clOrdId, orderId, symbol, side,
                FixTags.EXEC_TYPE_NEW, FixTags.ORD_STATUS_NEW,
                orderQty, 0, 0, price, 0, "Order accepted");

        // Simulate fill based on fill rate
        if (Math.random() < fillRate) {
            // Full fill
            String execId = "EXEC" + execIdCounter.getAndIncrement();
            double fillPrice = price > 0 ? price : 100.0; // Use limit price or market price

            sendExecutionReport(session, clOrdId, orderId, symbol, side,
                    FixTags.EXEC_TYPE_FILL, FixTags.ORD_STATUS_FILLED,
                    orderQty, orderQty, 0, fillPrice, fillPrice, "Order filled");
        } else {
            // Partial fill
            double fillQty = Math.floor(orderQty * Math.random() * 0.5);
            if (fillQty > 0) {
                double fillPrice = price > 0 ? price : 100.0;
                sendExecutionReport(session, clOrdId, orderId, symbol, side,
                        FixTags.EXEC_TYPE_PARTIAL_FILL, FixTags.ORD_STATUS_PARTIALLY_FILLED,
                        orderQty, fillQty, orderQty - fillQty, fillPrice, fillPrice, "Partial fill");
            }
        }
    }

    /**
     * Handle an order cancel request.
     */
    private void handleOrderCancel(FixSession session, FixMessage cancel) {
        String clOrdId = cancel.getString(FixTags.ClOrdID);
        String origClOrdId = cancel.getString(41); // OrigClOrdID
        String symbol = cancel.getString(FixTags.Symbol);
        char side = cancel.getChar(FixTags.Side);
        double orderQty = cancel.getDouble(FixTags.OrderQty);

        log.info("Cancel Request: ClOrdID={}, OrigClOrdID={}", clOrdId, origClOrdId);

        String orderId = "ORD" + orderIdCounter.getAndIncrement();

        // For simplicity, always accept the cancel
        sendExecutionReport(session, clOrdId, orderId, symbol, side,
                FixTags.EXEC_TYPE_CANCELED, FixTags.ORD_STATUS_CANCELED,
                orderQty, 0, 0, 0, 0, "Order canceled");
    }

    /**
     * Handle an order cancel/replace request.
     */
    private void handleOrderReplace(FixSession session, FixMessage replace) {
        String clOrdId = replace.getString(FixTags.ClOrdID);
        String origClOrdId = replace.getString(41); // OrigClOrdID
        String symbol = replace.getString(FixTags.Symbol);
        char side = replace.getChar(FixTags.Side);
        double orderQty = replace.getDouble(FixTags.OrderQty);
        double price = replace.getDouble(FixTags.Price);

        log.info("Replace Request: ClOrdID={}, OrigClOrdID={}, NewQty={}, NewPrice={}",
                clOrdId, origClOrdId, orderQty, price);

        String orderId = "ORD" + orderIdCounter.getAndIncrement();

        // For simplicity, always accept the replace
        sendExecutionReport(session, clOrdId, orderId, symbol, side,
                FixTags.EXEC_TYPE_REPLACED, FixTags.ORD_STATUS_REPLACED,
                orderQty, 0, orderQty, price, 0, "Order replaced");
    }

    /**
     * Send an execution report.
     */
    private void sendExecutionReport(FixSession session, String clOrdId, String orderId,
                                     String symbol, char side, char execType, char ordStatus,
                                     double orderQty, double cumQty, double leavesQty,
                                     double price, double avgPx, String text) {
        FixMessage execReport = new FixMessage();
        execReport.setMsgType(FixTags.MsgTypes.ExecutionReport);
        execReport.setField(FixTags.OrderID, orderId);
        execReport.setField(FixTags.ClOrdID, clOrdId);
        execReport.setField(FixTags.ExecID, "EXEC" + execIdCounter.getAndIncrement());
        execReport.setField(FixTags.ExecType, execType);
        execReport.setField(FixTags.OrdStatus, ordStatus);
        execReport.setField(FixTags.Symbol, symbol);
        execReport.setField(FixTags.Side, side);
        execReport.setField(FixTags.OrderQty, (int) orderQty);
        execReport.setField(FixTags.CumQty, (int) cumQty);
        execReport.setField(FixTags.LeavesQty, (int) leavesQty);
        execReport.setField(FixTags.AvgPx, avgPx);

        if (price > 0) {
            execReport.setField(FixTags.Price, price);
        }

        if (cumQty > 0) {
            execReport.setField(FixTags.LastQty, (int) cumQty);
            execReport.setField(FixTags.LastPx, avgPx);
        }

        if (text != null) {
            execReport.setField(FixTags.Text, text);
        }

        try {
            session.send(execReport);
            log.info("Sent ExecutionReport: OrdID={}, ExecType={}, OrdStatus={}",
                    orderId, execType, ordStatus);
        } catch (Exception e) {
            log.error("Error sending execution report", e);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleAcceptor()).execute(args);
        System.exit(exitCode);
    }
}
