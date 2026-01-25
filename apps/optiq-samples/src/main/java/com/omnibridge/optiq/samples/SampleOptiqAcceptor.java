package com.omnibridge.optiq.samples;

import com.omnibridge.optiq.engine.OptiqEngine;
import com.omnibridge.optiq.engine.OptiqSession;
import com.omnibridge.optiq.engine.config.OptiqEngineConfig;
import com.omnibridge.optiq.engine.config.OptiqSessionConfig;
import com.omnibridge.optiq.message.order.ExecutionReportMessage;
import com.omnibridge.optiq.message.order.NewOrderMessage;
import com.omnibridge.sbe.engine.session.SbeSession;
import com.omnibridge.sbe.message.SbeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sample Optiq acceptor application.
 * <p>
 * Listens for incoming connections and handles order messages.
 */
@Command(name = "optiq-acceptor", mixinStandardHelpOptions = true,
         description = "Sample Euronext Optiq Acceptor")
public class SampleOptiqAcceptor implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SampleOptiqAcceptor.class);

    @Option(names = {"-p", "--port"}, description = "Listen port (default: 9890)")
    private int port = 9890;

    @Option(names = {"-s", "--session"}, description = "Session ID (default: OPTIQ-ACC)")
    private String sessionId = "OPTIQ-ACC";

    @Option(names = {"--firm"}, description = "Firm ID (default: 1)")
    private int firmId = 1;

    private final AtomicLong orderIdSequence = new AtomicLong(1000000);

    @Override
    public Integer call() throws Exception {
        log.info("Starting Optiq Acceptor on port {}", port);

        // Create engine configuration
        OptiqEngineConfig engineConfig = new OptiqEngineConfig();

        // Create session configuration
        OptiqSessionConfig sessionConfig = OptiqSessionConfig.builder()
                .sessionId(sessionId)
                .port(port)
                .acceptor()
                .logicalAccessId(100)
                .oegInstance(1)
                .firmId(firmId)
                .softwareProvider("OmniBridge")
                .heartbeatInterval(30)
                .build();

        engineConfig.addSession(sessionConfig);

        // Create and start engine
        OptiqEngine engine = new OptiqEngine(engineConfig);

        // Add message listener
        engine.addMessageListener((session, message) -> handleMessage(session, message));

        engine.initialize();
        engine.start();

        log.info("Optiq Acceptor started. Press Ctrl+C to stop.");

        // Wait for shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            engine.stop();
        }));

        Thread.currentThread().join();
        return 0;
    }

    private void handleMessage(SbeSession<?> session, SbeMessage message) {
        if (message instanceof NewOrderMessage order) {
            handleNewOrder((OptiqSession) session, order);
        }
    }

    private void handleNewOrder(OptiqSession session, NewOrderMessage order) {
        long clientOrderId = order.getClientOrderId();
        double price = order.getOrderPx();
        long qty = order.getOrderQty();
        byte side = order.getOrderSide();

        log.info("Received NewOrder: clientOrderId={}, price={}, qty={}, side={}",
                clientOrderId, price, qty, side);

        // Generate execution report
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport != null) {
            try {
                long orderId = orderIdSequence.incrementAndGet();

                execReport.writeHeader();
                execReport.setMsgSeqNum(session.getOutboundSeqNum())
                         .setFirmId(session.getConfig().getFirmId())
                         .setClientOrderId(clientOrderId)
                         .setOrderId(orderId)
                         .setSymbolIndex(order.getSymbolIndex())
                         .setOrderSide(side)
                         .setOrderStatus((byte) 0) // New
                         .setOrderType(order.getOrderType())
                         .setOrderPx(price)
                         .setOrderQty(qty)
                         .setLeavesQty(qty)
                         .setCumQty(0)
                         .setOegIn(System.nanoTime())
                         .setOegOut(System.nanoTime());

                session.commit(execReport);
                log.info("Sent ExecutionReport: clientOrderId={}, orderId={}", clientOrderId, orderId);
            } catch (Exception e) {
                session.abort(execReport);
                log.error("Error sending ExecutionReport", e);
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleOptiqAcceptor()).execute(args);
        System.exit(exitCode);
    }
}
