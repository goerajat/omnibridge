package com.omnibridge.optiq.samples;

import com.omnibridge.optiq.engine.OptiqEngine;
import com.omnibridge.optiq.engine.OptiqSession;
import com.omnibridge.optiq.engine.config.OptiqEngineConfig;
import com.omnibridge.optiq.engine.config.OptiqSessionConfig;
import com.omnibridge.optiq.message.order.ExecutionReportMessage;
import com.omnibridge.optiq.message.order.NewOrderMessage;
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
 * Sample Optiq initiator application.
 * <p>
 * Connects to an acceptor and sends test orders.
 */
@Command(name = "optiq-initiator", mixinStandardHelpOptions = true,
         description = "Sample Euronext Optiq Initiator")
public class SampleOptiqInitiator implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SampleOptiqInitiator.class);

    @Option(names = {"-h", "--host"}, description = "Host to connect to (default: localhost)")
    private String host = "localhost";

    @Option(names = {"-p", "--port"}, description = "Port to connect to (default: 9890)")
    private int port = 9890;

    @Option(names = {"-s", "--session"}, description = "Session ID (default: OPTIQ-INIT)")
    private String sessionId = "OPTIQ-INIT";

    @Option(names = {"-n", "--num-orders"}, description = "Number of orders to send (default: 10)")
    private int numOrders = 10;

    @Option(names = {"--firm"}, description = "Firm ID (default: 1)")
    private int firmId = 1;

    private final AtomicLong clientOrderIdSequence = new AtomicLong(1);
    private final CountDownLatch establishedLatch = new CountDownLatch(1);

    @Override
    public Integer call() throws Exception {
        log.info("Starting Optiq Initiator connecting to {}:{}", host, port);

        // Create engine configuration
        OptiqEngineConfig engineConfig = new OptiqEngineConfig();

        // Create session configuration
        OptiqSessionConfig sessionConfig = OptiqSessionConfig.builder()
                .sessionId(sessionId)
                .host(host)
                .port(port)
                .initiator(true)
                .logicalAccessId(200)
                .oegInstance(1)
                .firmId(firmId)
                .softwareProvider("OmniBridge")
                .heartbeatInterval(30)
                .build();

        engineConfig.addSession(sessionConfig);

        // Create engine
        OptiqEngine engine = new OptiqEngine(engineConfig);

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

        OptiqSession session = engine.getOptiqSession(sessionId);

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

    private void sendTestOrder(OptiqSession session, int index) {
        NewOrderMessage order = session.tryClaim(NewOrderMessage.class);
        if (order == null) {
            log.warn("Failed to claim NewOrder message");
            return;
        }

        try {
            long clientOrderId = clientOrderIdSequence.incrementAndGet();
            double price = 50.0 + (index * 0.10);
            long qty = 100 + (index * 10);
            byte side = (byte) ((index % 2 == 0) ? 1 : 2); // Alternate buy/sell

            order.writeHeader();
            order.setClMsgSeqNum(session.getOutboundSeqNum())
                 .setFirmId(session.getConfig().getFirmId())
                 .setClientOrderId(clientOrderId)
                 .setOrderPx(price)
                 .setOrderQty(qty)
                 .setOrderSide(side)
                 .setSymbolIndex(1001)
                 .setOrderType((byte) 2) // Limit
                 .setTimeInForce((byte) 0) // Day
                 .setEmm((byte) 1)
                 .setSendingTime(System.nanoTime());

            session.commit(order);
            log.info("Sent NewOrder: clientOrderId={}, price={}, qty={}, side={}",
                    clientOrderId, price, qty, side == 1 ? "Buy" : "Sell");
        } catch (Exception e) {
            session.abort(order);
            log.error("Error sending order", e);
        }
    }

    private void handleMessage(SbeSession<?> session, SbeMessage message) {
        if (message instanceof ExecutionReportMessage execReport) {
            log.info("Received ExecutionReport: clientOrderId={}, orderId={}, status={}, leavesQty={}",
                    execReport.getClientOrderId(), execReport.getOrderId(),
                    execReport.getOrderStatus(), execReport.getLeavesQty());
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleOptiqInitiator()).execute(args);
        System.exit(exitCode);
    }
}
