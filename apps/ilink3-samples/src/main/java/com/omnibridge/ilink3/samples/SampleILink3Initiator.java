package com.omnibridge.ilink3.samples;

import com.omnibridge.ilink3.engine.ILink3Engine;
import com.omnibridge.ilink3.engine.ILink3Session;
import com.omnibridge.ilink3.engine.config.ILink3EngineConfig;
import com.omnibridge.ilink3.engine.config.ILink3SessionConfig;
import com.omnibridge.ilink3.message.order.ExecutionReportNewMessage;
import com.omnibridge.ilink3.message.order.NewOrderSingleMessage;
import com.omnibridge.sbe.engine.session.SbeSession;
import com.omnibridge.sbe.engine.session.SbeSessionState;
import com.omnibridge.sbe.message.SbeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sample iLink 3 initiator application.
 * <p>
 * Connects to an acceptor and sends test orders.
 */
@Command(name = "ilink3-initiator", mixinStandardHelpOptions = true,
         description = "Sample CME iLink 3 Initiator")
public class SampleILink3Initiator implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SampleILink3Initiator.class);

    @Option(names = {"-h", "--host"}, description = "Host to connect to (default: localhost)")
    private String host = "localhost";

    @Option(names = {"-p", "--port"}, description = "Port to connect to (default: 9880)")
    private int port = 9880;

    @Option(names = {"-s", "--session"}, description = "Session ID (default: ILINK3-INIT)")
    private String sessionId = "ILINK3-INIT";

    @Option(names = {"-n", "--num-orders"}, description = "Number of orders to send (default: 10)")
    private int numOrders = 10;

    @Option(names = {"--firm"}, description = "Firm ID (default: TEST)")
    private String firmId = "TEST";

    private final AtomicLong clOrdIdSequence = new AtomicLong(1);
    private final CountDownLatch establishedLatch = new CountDownLatch(1);

    @Override
    public Integer call() throws Exception {
        log.info("Starting iLink 3 Initiator connecting to {}:{}", host, port);

        // Create engine configuration
        ILink3EngineConfig engineConfig = new ILink3EngineConfig();

        // Create session configuration
        ILink3SessionConfig sessionConfig = ILink3SessionConfig.builder()
                .sessionId(sessionId)
                .host(host)
                .port(port)
                .initiator(true)
                .firmId(firmId)
                .session("001")
                .uuid(System.currentTimeMillis())
                .keepAliveInterval(30000)
                .build();

        engineConfig.addSession(sessionConfig);

        // Create engine
        ILink3Engine engine = new ILink3Engine(engineConfig);

        // Add state listener
        engine.addStateListener((session, oldState, newState) -> {
            log.info("Session state: {} -> {}", oldState, newState);
            if (newState == SbeSessionState.ESTABLISHED) {
                establishedLatch.countDown();
            }
        });

        // Add message listener
        engine.addMessageListener((session, message) -> handleMessage(session, message));

        engine.initialize();
        engine.start();

        // Connect
        engine.connect(sessionId);

        // Wait for session to be established
        if (!establishedLatch.await(30, TimeUnit.SECONDS)) {
            log.error("Timeout waiting for session to establish");
            engine.stop();
            return 1;
        }

        ILink3Session session = engine.getILink3Session(sessionId);

        // Send test orders
        log.info("Sending {} test orders...", numOrders);
        for (int i = 0; i < numOrders; i++) {
            sendTestOrder(session, i);
            Thread.sleep(100); // Small delay between orders
        }

        log.info("All orders sent. Waiting for responses...");
        Thread.sleep(2000);

        // Disconnect
        session.disconnect();
        Thread.sleep(1000);
        engine.stop();

        log.info("Initiator completed");
        return 0;
    }

    private void sendTestOrder(ILink3Session session, int index) {
        NewOrderSingleMessage order = session.tryClaim(NewOrderSingleMessage.class);
        if (order == null) {
            log.warn("Failed to claim NewOrderSingle message");
            return;
        }

        try {
            String clOrdId = String.format("ORD%06d", clOrdIdSequence.incrementAndGet());
            double price = 100.0 + (index * 0.25);
            int qty = 100 + (index * 10);
            byte side = (byte) ((index % 2 == 0) ? 1 : 2); // Alternate buy/sell

            order.writeHeader();
            order.setClOrdId(clOrdId)
                 .setPrice(price)
                 .setOrderQty(qty)
                 .setSide(side)
                 .setSecurityId(12345)
                 .setOrdType((byte) 2) // Limit
                 .setTimeInForce((byte) 0) // Day
                 .setSendingTimeEpoch(System.nanoTime())
                 .setSeqNum((int) session.getOutboundSeqNum())
                 .setManualOrderIndicator((byte) 0);

            session.commit(order);
            log.info("Sent NewOrderSingle: clOrdId={}, price={}, qty={}, side={}",
                    clOrdId, price, qty, side == 1 ? "Buy" : "Sell");
        } catch (Exception e) {
            session.abort(order);
            log.error("Error sending order", e);
        }
    }

    private void handleMessage(SbeSession<?> session, SbeMessage message) {
        if (message instanceof ExecutionReportNewMessage execReport) {
            log.info("Received ExecutionReportNew: clOrdId={}, orderId={}, leavesQty={}",
                    execReport.getClOrdId(), execReport.getOrderId(), execReport.getLeavesQty());
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleILink3Initiator()).execute(args);
        System.exit(exitCode);
    }
}
