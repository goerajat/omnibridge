package com.omnibridge.fix.reference.initiator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.*;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Reference FIX Initiator using QuickFIX/J.
 *
 * <p>This initiator can connect to a FIX engine and send orders, test requests,
 * and other messages. It's used for testing the FIX engine implementation.</p>
 */
public class ReferenceInitiator implements Application {

    private static final Logger log = LoggerFactory.getLogger(ReferenceInitiator.class);
    private static final DateTimeFormatter CLORDID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final InitiatorConfig config;
    private SocketInitiator initiator;
    private final AtomicInteger clOrdIdCounter = new AtomicInteger(1);
    private final CountDownLatch logonLatch = new CountDownLatch(1);
    private volatile SessionID currentSession;

    // Execution report tracking
    private final Map<String, CompletableFuture<ExecutionReport>> pendingOrders = new ConcurrentHashMap<>();
    private final BlockingQueue<ExecutionReport> executionReports = new LinkedBlockingQueue<>();
    private Consumer<ExecutionReport> executionReportListener;

    public ReferenceInitiator(InitiatorConfig config) {
        this.config = config;
    }

    public void start() throws ConfigError {
        SessionSettings settings = createSettings();
        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = new SLF4JLogFactory(settings);
        quickfix.MessageFactory messageFactory = new DefaultMessageFactory();

        initiator = new SocketInitiator(this, storeFactory, settings, logFactory, messageFactory);
        initiator.start();
        log.info("Reference Initiator started, connecting to {}:{}", config.getHost(), config.getPort());
    }

    public void stop() {
        if (initiator != null) {
            initiator.stop();
            log.info("Reference Initiator stopped");
        }
    }

    public boolean waitForLogon(long timeoutMs) throws InterruptedException {
        return logonLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void waitForLogon() throws InterruptedException {
        logonLatch.await();
    }

    public SessionID getCurrentSession() {
        return currentSession;
    }

    public boolean isLoggedOn() {
        return currentSession != null && Session.lookupSession(currentSession).isLoggedOn();
    }

    public void setExecutionReportListener(Consumer<ExecutionReport> listener) {
        this.executionReportListener = listener;
    }

    private SessionSettings createSettings() throws ConfigError {
        // Try to load from config file first
        InputStream configStream = getClass().getResourceAsStream("/initiator.cfg");
        if (configStream != null && !config.isCustomConfig()) {
            return new SessionSettings(configStream);
        }

        // Create settings programmatically
        SessionSettings settings = new SessionSettings();

        // Default settings
        settings.setString("ConnectionType", "initiator");
        settings.setString("StartTime", "00:00:00");
        settings.setString("EndTime", "00:00:00");
        settings.setLong("HeartBtInt", config.getHeartbeatInterval());
        settings.setLong("ReconnectInterval", config.getReconnectInterval());
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

        settings.setString(sessionId, "SocketConnectHost", config.getHost());
        settings.setString(sessionId, "SocketConnectPort", String.valueOf(config.getPort()));

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
        log.info("Logon successful: {}", sessionId);
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

        if (MsgType.EXECUTION_REPORT.equals(msgType)) {
            handleExecutionReport(message);
        } else if (MsgType.ORDER_CANCEL_REJECT.equals(msgType)) {
            handleOrderCancelReject(message);
        }
    }

    private void handleExecutionReport(quickfix.Message message) throws FieldNotFound {
        // Extract fields directly from the message
        String clOrdId = message.getString(ClOrdID.FIELD);
        char execType = message.getChar(ExecType.FIELD);
        char ordStatus = message.getChar(OrdStatus.FIELD);

        // Create ExecutionReport for storage/listener
        ExecutionReport execReport = new ExecutionReport();
        try {
            execReport.fromString(message.toString(), null, false);
        } catch (InvalidMessage e) {
            log.warn("Could not parse execution report fully: {}", e.getMessage());
            // Continue with what we have
        }

        log.info("ExecutionReport: ClOrdID={}, ExecType={}, OrdStatus={}", clOrdId, execType, ordStatus);

        // Add to queue
        executionReports.offer(execReport);

        // Notify pending order
        CompletableFuture<ExecutionReport> future = pendingOrders.remove(clOrdId);
        if (future != null) {
            future.complete(execReport);
        }

        // Notify listener
        if (executionReportListener != null) {
            executionReportListener.accept(execReport);
        }
    }

    private void handleOrderCancelReject(quickfix.Message message) throws FieldNotFound {
        String clOrdId = message.getString(ClOrdID.FIELD);
        String origClOrdId = message.isSetField(OrigClOrdID.FIELD) ?
                message.getString(OrigClOrdID.FIELD) : "";
        String text = message.isSetField(Text.FIELD) ? message.getString(Text.FIELD) : "";

        log.warn("OrderCancelReject: ClOrdID={}, OrigClOrdID={}, Text={}",
                clOrdId, origClOrdId, text);
    }

    // ==================== Order Operations ====================

    /**
     * Send a new order.
     */
    public String sendNewOrderSingle(String symbol, char side, double quantity, char ordType, double price) {
        String clOrdId = generateClOrdId();

        try {
            NewOrderSingle order = new NewOrderSingle(
                    new ClOrdID(clOrdId),
                    new Side(side),
                    new TransactTime(LocalDateTime.now()),
                    new OrdType(ordType)
            );

            order.set(new Symbol(symbol));
            order.set(new OrderQty(quantity));
            order.set(new HandlInst('1')); // Automated execution

            if (ordType == OrdType.LIMIT) {
                order.set(new Price(price));
                order.set(new TimeInForce(TimeInForce.DAY));
            }

            Session.sendToTarget(order, currentSession);
            log.info("Sent NewOrderSingle: ClOrdID={}, Symbol={}, Side={}, Qty={}, Price={}",
                    clOrdId, symbol, side == Side.BUY ? "BUY" : "SELL", quantity, price);

            return clOrdId;

        } catch (SessionNotFound e) {
            log.error("Failed to send order: session not found", e);
            return null;
        }
    }

    /**
     * Send a new order and wait for execution report.
     */
    public ExecutionReport sendNewOrderSingleAndWait(String symbol, char side, double quantity,
                                                      char ordType, double price, long timeoutMs)
            throws InterruptedException, ExecutionException, TimeoutException {
        String clOrdId = generateClOrdId();
        CompletableFuture<ExecutionReport> future = new CompletableFuture<>();
        pendingOrders.put(clOrdId, future);

        try {
            NewOrderSingle order = new NewOrderSingle(
                    new ClOrdID(clOrdId),
                    new Side(side),
                    new TransactTime(LocalDateTime.now()),
                    new OrdType(ordType)
            );

            order.set(new Symbol(symbol));
            order.set(new OrderQty(quantity));
            order.set(new HandlInst('1')); // Automated execution

            if (ordType == OrdType.LIMIT) {
                order.set(new Price(price));
                order.set(new TimeInForce(TimeInForce.DAY));
            }

            Session.sendToTarget(order, currentSession);
            log.info("Sent NewOrderSingle: ClOrdID={}, waiting for response...", clOrdId);

            return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        } catch (SessionNotFound e) {
            log.error("Failed to send order: session not found", e);
            pendingOrders.remove(clOrdId);
            throw new ExecutionException("Session not found", e);
        }
    }

    /**
     * Send an order cancel request.
     */
    public String sendOrderCancelRequest(String origClOrdId, String symbol, char side) {
        String clOrdId = generateClOrdId();

        try {
            OrderCancelRequest cancel = new OrderCancelRequest(
                    new OrigClOrdID(origClOrdId),
                    new ClOrdID(clOrdId),
                    new Side(side),
                    new TransactTime(LocalDateTime.now())
            );

            cancel.set(new Symbol(symbol));
            cancel.set(new OrderQty(0)); // Not required but good practice

            Session.sendToTarget(cancel, currentSession);
            log.info("Sent OrderCancelRequest: ClOrdID={}, OrigClOrdID={}", clOrdId, origClOrdId);

            return clOrdId;

        } catch (SessionNotFound e) {
            log.error("Failed to send cancel request: session not found", e);
            return null;
        }
    }

    /**
     * Send an order cancel/replace request.
     */
    public String sendOrderCancelReplaceRequest(String origClOrdId, String symbol, char side,
                                                 double newQuantity, double newPrice, char ordType) {
        String clOrdId = generateClOrdId();

        try {
            OrderCancelReplaceRequest replace = new OrderCancelReplaceRequest(
                    new OrigClOrdID(origClOrdId),
                    new ClOrdID(clOrdId),
                    new Side(side),
                    new TransactTime(LocalDateTime.now()),
                    new OrdType(ordType)
            );

            replace.set(new Symbol(symbol));
            replace.set(new OrderQty(newQuantity));
            replace.set(new HandlInst('1')); // Automated execution

            if (ordType == OrdType.LIMIT) {
                replace.set(new Price(newPrice));
            }

            Session.sendToTarget(replace, currentSession);
            log.info("Sent OrderCancelReplaceRequest: ClOrdID={}, OrigClOrdID={}, NewQty={}, NewPrice={}",
                    clOrdId, origClOrdId, newQuantity, newPrice);

            return clOrdId;

        } catch (SessionNotFound e) {
            log.error("Failed to send replace request: session not found", e);
            return null;
        }
    }

    /**
     * Send a test request.
     */
    public String sendTestRequest() {
        String testReqId = "TEST-" + System.currentTimeMillis();

        try {
            TestRequest testRequest = new TestRequest(new TestReqID(testReqId));
            Session.sendToTarget(testRequest, currentSession);
            log.info("Sent TestRequest: TestReqID={}", testReqId);
            return testReqId;

        } catch (SessionNotFound e) {
            log.error("Failed to send test request: session not found", e);
            return null;
        }
    }

    /**
     * Request logout.
     */
    public void logout() {
        if (currentSession != null) {
            Session session = Session.lookupSession(currentSession);
            if (session != null) {
                session.logout("User requested logout");
                log.info("Logout requested");
            }
        }
    }

    /**
     * Get the next execution report from the queue.
     */
    public ExecutionReport pollExecutionReport(long timeoutMs) throws InterruptedException {
        return executionReports.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Clear execution report queue.
     */
    public void clearExecutionReports() {
        executionReports.clear();
    }

    /**
     * Get session sequence numbers.
     */
    public int getExpectedTargetNum() {
        if (currentSession != null) {
            Session session = Session.lookupSession(currentSession);
            if (session != null) {
                return session.getExpectedTargetNum();
            }
        }
        return 0;
    }

    public int getExpectedSenderNum() {
        if (currentSession != null) {
            Session session = Session.lookupSession(currentSession);
            if (session != null) {
                return session.getExpectedSenderNum();
            }
        }
        return 0;
    }

    private String generateClOrdId() {
        return "ORD-" + LocalDateTime.now().format(CLORDID_FORMAT) + "-" + clOrdIdCounter.getAndIncrement();
    }

    // ==================== Main for standalone testing ====================

    public static void main(String[] args) throws Exception {
        InitiatorConfig config = InitiatorConfig.builder()
                .host("localhost")
                .port(9880)
                .senderCompId("CLIENT")
                .targetCompId("EXCHANGE")
                .build();

        ReferenceInitiator initiator = new ReferenceInitiator(config);
        initiator.start();

        if (initiator.waitForLogon(10000)) {
            System.out.println("Logged on successfully!");

            // Send a test order
            String clOrdId = initiator.sendNewOrderSingle("AAPL", Side.BUY, 100, OrdType.LIMIT, 150.00);
            System.out.println("Order sent: " + clOrdId);

            // Wait for execution report
            ExecutionReport report = initiator.pollExecutionReport(5000);
            if (report != null) {
                System.out.println("Received execution report: " + report.getString(ExecType.FIELD));
            }

            Thread.sleep(2000);
        } else {
            System.err.println("Failed to logon within timeout");
        }

        initiator.stop();
    }
}
