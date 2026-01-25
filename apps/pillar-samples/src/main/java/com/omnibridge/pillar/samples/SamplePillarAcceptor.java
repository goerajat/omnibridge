package com.omnibridge.pillar.samples;

import com.omnibridge.pillar.engine.PillarEngine;
import com.omnibridge.pillar.engine.PillarSession;
import com.omnibridge.pillar.engine.config.PillarEngineConfig;
import com.omnibridge.pillar.engine.config.PillarSessionConfig;
import com.omnibridge.pillar.message.order.ExecutionReportMessage;
import com.omnibridge.pillar.message.order.NewOrderMessage;
import com.omnibridge.pillar.message.order.OrderAckMessage;
import com.omnibridge.sbe.message.SbeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sample NYSE Pillar acceptor application.
 * <p>
 * Listens for incoming connections and handles order messages
 * following the Pillar stream protocol.
 */
@Command(name = "pillar-acceptor", mixinStandardHelpOptions = true,
         description = "Sample NYSE Pillar Acceptor")
public class SamplePillarAcceptor implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SamplePillarAcceptor.class);

    @Option(names = {"-p", "--port"}, description = "Listen port (default: 9400)")
    private int port = 9400;

    @Option(names = {"-s", "--session"}, description = "Session ID (default: PILLAR-ACC)")
    private String sessionId = "PILLAR-ACC";

    @Option(names = {"--mpid"}, description = "Market Participant ID (default: TSIM)")
    private String mpid = "TSIM";

    private final AtomicLong orderIdSequence = new AtomicLong(1000000);
    private final AtomicLong execIdSequence = new AtomicLong(2000000);

    @Override
    public Integer call() throws Exception {
        log.info("Starting NYSE Pillar Acceptor on port {}", port);

        // Create engine configuration
        PillarEngineConfig engineConfig = new PillarEngineConfig();

        // Create session configuration
        PillarSessionConfig sessionConfig = PillarSessionConfig.builder()
                .sessionId(sessionId)
                .port(port)
                .acceptor()
                .mpid(mpid)
                .username("PILLAR-SIM")
                .password("password")
                .heartbeatIntervalMillis(1000)
                .protocolVersion(1, 0)
                .build();

        engineConfig.addSession(sessionConfig);

        // Create and start engine
        PillarEngine engine = new PillarEngine(engineConfig);

        // Add message listener
        engine.addMessageListener((session, message) -> handleMessage(session, message));

        // Add state listener
        engine.addStateListener((session, oldState, newState) -> {
            log.info("Session {} state change: {} -> {}", session.getSessionId(), oldState, newState);
        });

        engine.initialize();
        engine.start();

        log.info("NYSE Pillar Acceptor started. Press Ctrl+C to stop.");

        // Wait for shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            engine.stop();
        }));

        Thread.currentThread().join();
        return 0;
    }

    private void handleMessage(PillarSession session, SbeMessage message) {
        if (message instanceof NewOrderMessage order) {
            handleNewOrder(session, order);
        }
    }

    private void handleNewOrder(PillarSession session, NewOrderMessage order) {
        long clOrdId = order.getClOrdId();
        long rawPrice = order.getRawPrice();
        long qty = order.getOrderQty();
        byte side = order.getSide();

        log.info("Received NewOrder: clOrdId={}, price={}, qty={}, side={}",
                clOrdId, order.getPrice(), qty, side == 1 ? "Buy" : "Sell");

        // Send order acknowledgment
        OrderAckMessage ack = session.tryClaim(OrderAckMessage.class);
        if (ack != null) {
            try {
                long orderId = orderIdSequence.incrementAndGet();

                ack.writeHeader();
                ack.setClOrdId(clOrdId)
                   .setOrderId(orderId)
                   .setSymbol(order.getSymbol())
                   .setSide(side)
                   .setPrice(order.getPrice())
                   .setOrderQty(qty)
                   .setOrdStatus((byte) 0) // New
                   .setTransactTime(System.nanoTime());

                session.commit(ack);
                log.info("Sent OrderAck: clOrdId={}, orderId={}", clOrdId, orderId);

                // Simulate a fill (execution report)
                sendExecutionReport(session, clOrdId, orderId, order.getSymbol(),
                                   side, rawPrice, qty);
            } catch (Exception e) {
                session.abort(ack);
                log.error("Error sending OrderAck", e);
            }
        }
    }

    private void sendExecutionReport(PillarSession session, long clOrdId, long orderId,
                                     String symbol, byte side, long rawPrice, long qty) {
        ExecutionReportMessage execReport = session.tryClaim(ExecutionReportMessage.class);
        if (execReport != null) {
            try {
                long execId = execIdSequence.incrementAndGet();

                execReport.writeHeader();
                execReport.setClOrdId(clOrdId)
                         .setOrderId(orderId)
                         .setExecId(execId)
                         .setSymbol(symbol)
                         .setSide(side)
                         .setOrdStatus((byte) 2) // Filled
                         .setExecType((byte) 'F') // Fill
                         .setRawPrice(rawPrice)
                         .setRawLastPx(rawPrice)
                         .setOrderQty(qty)
                         .setLastQty(qty)
                         .setCumQty(qty)
                         .setLeavesQty(0)
                         .setTransactTime(System.nanoTime());

                session.commit(execReport);
                log.info("Sent ExecutionReport: clOrdId={}, execId={}, fillQty={}",
                        clOrdId, execId, qty);
            } catch (Exception e) {
                session.abort(execReport);
                log.error("Error sending ExecutionReport", e);
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SamplePillarAcceptor()).execute(args);
        System.exit(exitCode);
    }
}
