package com.omnibridge.fix.reference.acceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.TestRequest;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reference FIX Acceptor using QuickFIX/J.
 *
 * <p>This acceptor simulates an exchange/counterparty and can be used to test
 * the FIX engine implementation. It handles:</p>
 * <ul>
 *   <li>Session management (logon, logout, heartbeat)</li>
 *   <li>Order handling with execution reports</li>
 *   <li>Order cancellation</li>
 *   <li>Order modification</li>
 * </ul>
 */
public class ReferenceAcceptor implements Application {

    private static final Logger log = LoggerFactory.getLogger(ReferenceAcceptor.class);
    private static final DateTimeFormatter EXEC_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final AcceptorConfig config;
    private SocketAcceptor acceptor;
    private final AtomicInteger orderIdCounter = new AtomicInteger(1);
    private final AtomicLong execIdCounter = new AtomicLong(1);
    private final CountDownLatch logonLatch = new CountDownLatch(1);
    private volatile SessionID currentSession;

    // Configurable behavior
    private double fillRate = 1.0;  // 100% fill rate by default
    private int fillDelayMs = 0;    // No delay by default
    private boolean autoAck = true; // Auto-generate execution reports

    public ReferenceAcceptor(AcceptorConfig config) {
        this.config = config;
        this.fillRate = config.getFillRate();
        this.fillDelayMs = config.getFillDelayMs();
        this.autoAck = config.isAutoAck();
    }

    public void start() throws ConfigError {
        SessionSettings settings = createSettings();
        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = new SLF4JLogFactory(settings);
        quickfix.MessageFactory messageFactory = new DefaultMessageFactory();

        acceptor = new SocketAcceptor(this, storeFactory, settings, logFactory, messageFactory);
        acceptor.start();
        log.info("Reference Acceptor started on port {}", config.getPort());
    }

    public void stop() {
        if (acceptor != null) {
            acceptor.stop();
            log.info("Reference Acceptor stopped");
        }
    }

    public void waitForLogon() throws InterruptedException {
        logonLatch.await();
    }

    public boolean waitForLogon(long timeoutMs) throws InterruptedException {
        return logonLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public SessionID getCurrentSession() {
        return currentSession;
    }

    public boolean isLoggedOn() {
        return currentSession != null && Session.lookupSession(currentSession).isLoggedOn();
    }

    private SessionSettings createSettings() throws ConfigError {
        // Try to load from config file first
        InputStream configStream = getClass().getResourceAsStream("/acceptor.cfg");
        if (configStream != null && !config.isCustomConfig()) {
            return new SessionSettings(configStream);
        }

        // Create settings programmatically
        SessionSettings settings = new SessionSettings();

        // Default settings
        settings.setString("ConnectionType", "acceptor");
        settings.setString("StartTime", "00:00:00");
        settings.setString("EndTime", "00:00:00");
        settings.setLong("HeartBtInt", config.getHeartbeatInterval());
        settings.setString("FileStorePath", "./quickfix-store");
        settings.setString("UseDataDictionary", "Y");
        settings.setBool("ResetOnLogon", config.isResetOnLogon());
        settings.setBool("ResetOnLogout", false);
        settings.setBool("ResetOnDisconnect", false);

        // Session settings
        SessionID sessionId = new SessionID(
                config.getBeginString(),
                config.getSenderCompId(),
                config.getTargetCompId()
        );

        settings.setString(sessionId, "SocketAcceptPort", String.valueOf(config.getPort()));

        // Configure dictionaries based on FIX version
        if (config.usesFixt()) {
            // FIX 5.0+ uses separate transport and application dictionaries
            settings.setString(sessionId, "TransportDataDictionary", "FIXT11.xml");
            settings.setString(sessionId, "AppDataDictionary", "FIX50.xml");

            // Set DefaultApplVerID for FIX 5.0+
            if (config.getDefaultApplVerID() != null) {
                settings.setString(sessionId, "DefaultApplVerID", config.getDefaultApplVerID());
            } else {
                settings.setString(sessionId, "DefaultApplVerID", "9"); // Default to FIX 5.0
            }

            log.info("Configured for FIXT.1.1 with DefaultApplVerID={}",
                    config.getDefaultApplVerID() != null ? config.getDefaultApplVerID() : "9");
        } else {
            // FIX 4.x uses single dictionary
            settings.setString(sessionId, "DataDictionary",
                    config.getBeginString().contains("4.4") ? "FIX44.xml" : "FIX42.xml");
        }

        return settings;
    }

    // ==================== QuickFIX/J Application Interface ====================

    @Override
    public void onCreate(SessionID sessionId) {
        log.info("Session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        log.info("Logon: {}", sessionId);
        this.currentSession = sessionId;
        logonLatch.countDown();
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.info("Logout: {}", sessionId);
        if (sessionId.equals(currentSession)) {
            currentSession = null;
        }
    }

    @Override
    public void toAdmin(quickfix.Message message, SessionID sessionId) {
        // Log outgoing admin messages
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            log.debug("Sending admin message: {} to {}", msgType, sessionId);
        } catch (FieldNotFound e) {
            log.warn("Could not get message type", e);
        }
    }

    @Override
    public void fromAdmin(quickfix.Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        log.debug("Received admin message: {} from {}", msgType, sessionId);
    }

    @Override
    public void toApp(quickfix.Message message, SessionID sessionId) throws DoNotSend {
        // Log outgoing application messages
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            log.debug("Sending app message: {} to {}", msgType, sessionId);
        } catch (FieldNotFound e) {
            log.warn("Could not get message type", e);
        }
    }

    @Override
    public void fromApp(quickfix.Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        log.info("Received app message: {} from {}", msgType, sessionId);

        // Route message to appropriate handler
        crack(message, sessionId);
    }

    private void crack(quickfix.Message message, SessionID sessionId)
            throws FieldNotFound, UnsupportedMessageType {
        String msgType = message.getHeader().getString(MsgType.FIELD);

        switch (msgType) {
            case MsgType.ORDER_SINGLE -> handleNewOrderSingle(message, sessionId);
            case MsgType.ORDER_CANCEL_REQUEST -> handleOrderCancelRequest(message, sessionId);
            case MsgType.ORDER_CANCEL_REPLACE_REQUEST -> handleOrderCancelReplaceRequest(message, sessionId);
            case MsgType.ORDER_STATUS_REQUEST -> handleOrderStatusRequest(message, sessionId);
            default -> log.warn("Unsupported message type: {}", msgType);
        }
    }

    // ==================== Order Handlers ====================

    private void handleNewOrderSingle(quickfix.Message message, SessionID sessionId) throws FieldNotFound {
        String clOrdId = message.getString(ClOrdID.FIELD);
        String symbol = message.getString(Symbol.FIELD);
        char side = message.getChar(Side.FIELD);
        double orderQty = message.getDouble(OrderQty.FIELD);
        char ordType = message.getChar(OrdType.FIELD);
        double price = ordType == OrdType.LIMIT ? message.getDouble(Price.FIELD) : 0.0;

        log.info("New Order: ClOrdID={}, Symbol={}, Side={}, Qty={}, Type={}, Price={}",
                clOrdId, symbol, side == Side.BUY ? "BUY" : "SELL", orderQty, ordType, price);

        if (!autoAck) {
            log.info("Auto-ack disabled, not sending execution report");
            return;
        }

        // Simulate processing delay
        if (fillDelayMs > 0) {
            try {
                Thread.sleep(fillDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String orderId = String.valueOf(orderIdCounter.getAndIncrement());

        // Send New acknowledgment
        sendExecutionReport(sessionId, clOrdId, orderId, symbol, side, orderQty, price,
                ExecType.NEW, OrdStatus.NEW, 0, 0, "Order received");

        // Determine if order should be filled
        if (Math.random() < fillRate) {
            // Send Fill
            double fillPrice = price > 0 ? price : 100.0 + Math.random() * 10;
            sendExecutionReport(sessionId, clOrdId, orderId, symbol, side, orderQty, fillPrice,
                    ExecType.TRADE, OrdStatus.FILLED, orderQty, orderQty, "Order filled");
        } else {
            // Reject the order
            sendExecutionReport(sessionId, clOrdId, orderId, symbol, side, orderQty, price,
                    ExecType.REJECTED, OrdStatus.REJECTED, 0, 0, "Order rejected (simulated)");
        }
    }

    private void handleOrderCancelRequest(quickfix.Message message, SessionID sessionId) throws FieldNotFound {
        String clOrdId = message.getString(ClOrdID.FIELD);
        String origClOrdId = message.getString(OrigClOrdID.FIELD);
        String symbol = message.getString(Symbol.FIELD);
        char side = message.getChar(Side.FIELD);

        log.info("Cancel Request: ClOrdID={}, OrigClOrdID={}", clOrdId, origClOrdId);

        if (!autoAck) {
            log.info("Auto-ack disabled, not sending cancel confirmation");
            return;
        }

        // Simulate cancel - always succeed for testing
        String orderId = String.valueOf(orderIdCounter.getAndIncrement());
        sendExecutionReport(sessionId, clOrdId, orderId, symbol, side, 0, 0,
                ExecType.CANCELED, OrdStatus.CANCELED, 0, 0, "Order canceled");
    }

    private void handleOrderCancelReplaceRequest(quickfix.Message message, SessionID sessionId) throws FieldNotFound {
        String clOrdId = message.getString(ClOrdID.FIELD);
        String origClOrdId = message.getString(OrigClOrdID.FIELD);
        String symbol = message.getString(Symbol.FIELD);
        char side = message.getChar(Side.FIELD);
        double orderQty = message.getDouble(OrderQty.FIELD);
        double price = message.isSetField(Price.FIELD) ? message.getDouble(Price.FIELD) : 0.0;

        log.info("Replace Request: ClOrdID={}, OrigClOrdID={}, NewQty={}, NewPrice={}",
                clOrdId, origClOrdId, orderQty, price);

        if (!autoAck) {
            log.info("Auto-ack disabled, not sending replace confirmation");
            return;
        }

        // Simulate replace - always succeed for testing
        String orderId = String.valueOf(orderIdCounter.getAndIncrement());
        sendExecutionReport(sessionId, clOrdId, orderId, symbol, side, orderQty, price,
                ExecType.REPLACED, OrdStatus.NEW, 0, orderQty, "Order replaced");
    }

    private void handleOrderStatusRequest(quickfix.Message message, SessionID sessionId) throws FieldNotFound {
        String clOrdId = message.getString(ClOrdID.FIELD);
        String symbol = message.isSetField(Symbol.FIELD) ? message.getString(Symbol.FIELD) : "N/A";
        char side = message.isSetField(Side.FIELD) ? message.getChar(Side.FIELD) : Side.BUY;

        log.info("Order Status Request: ClOrdID={}", clOrdId);

        if (!autoAck) {
            return;
        }

        // Send status - simulate as filled
        String orderId = String.valueOf(orderIdCounter.get());
        sendExecutionReport(sessionId, clOrdId, orderId, symbol, side, 100, 100.0,
                ExecType.ORDER_STATUS, OrdStatus.FILLED, 100, 100, "Order status");
    }

    private void sendExecutionReport(SessionID sessionId, String clOrdId, String orderId,
                                      String symbol, char side, double orderQty, double price,
                                      char execType, char ordStatus, double cumQty, double leavesQty,
                                      String text) {
        try {
            String execId = generateExecId();

            ExecutionReport execReport = new ExecutionReport(
                    new OrderID(orderId),
                    new ExecID(execId),
                    new ExecType(execType),
                    new OrdStatus(ordStatus),
                    new Side(side),
                    new LeavesQty(leavesQty),
                    new CumQty(cumQty),
                    new AvgPx(price > 0 ? price : 0)
            );

            execReport.set(new ClOrdID(clOrdId));
            execReport.set(new Symbol(symbol));
            execReport.set(new OrderQty(orderQty));

            if (execType == ExecType.TRADE && price > 0) {
                execReport.set(new LastPx(price));
                execReport.set(new LastQty(orderQty));
            }

            if (text != null && !text.isEmpty()) {
                execReport.set(new Text(text));
            }

            execReport.set(new TransactTime(LocalDateTime.now()));

            Session.sendToTarget(execReport, sessionId);
            log.info("Sent ExecutionReport: ExecType={}, OrdStatus={}, ClOrdID={}",
                    execType, ordStatus, clOrdId);

        } catch (SessionNotFound e) {
            log.error("Failed to send execution report: session not found", e);
        }
    }

    private String generateExecId() {
        return "EXEC-" + LocalDateTime.now().format(EXEC_ID_FORMAT) + "-" + execIdCounter.getAndIncrement();
    }

    // ==================== Configuration ====================

    public void setFillRate(double fillRate) {
        this.fillRate = Math.max(0, Math.min(1, fillRate));
    }

    public void setFillDelayMs(int delayMs) {
        this.fillDelayMs = Math.max(0, delayMs);
    }

    public void setAutoAck(boolean autoAck) {
        this.autoAck = autoAck;
    }

    // ==================== Main for standalone testing ====================

    public static void main(String[] args) throws Exception {
        AcceptorConfig config = AcceptorConfig.builder()
                .port(9880)
                .senderCompId("EXCHANGE")
                .targetCompId("CLIENT")
                .build();

        ReferenceAcceptor acceptor = new ReferenceAcceptor(config);
        acceptor.start();

        System.out.println("Reference Acceptor running on port " + config.getPort());
        System.out.println("Press Enter to stop...");
        System.in.read();

        acceptor.stop();
    }
}
