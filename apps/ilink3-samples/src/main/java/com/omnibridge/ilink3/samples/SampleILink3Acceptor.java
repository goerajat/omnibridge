package com.omnibridge.ilink3.samples;

import com.omnibridge.ilink3.engine.ILink3Engine;
import com.omnibridge.ilink3.engine.ILink3Session;
import com.omnibridge.ilink3.engine.config.ILink3EngineConfig;
import com.omnibridge.ilink3.engine.config.ILink3SessionConfig;
import com.omnibridge.ilink3.message.order.ExecutionReportNewMessage;
import com.omnibridge.ilink3.message.order.NewOrderSingleMessage;
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
 * Sample iLink 3 acceptor application.
 * <p>
 * Listens for incoming connections and handles order messages.
 */
@Command(name = "ilink3-acceptor", mixinStandardHelpOptions = true,
         description = "Sample CME iLink 3 Acceptor")
public class SampleILink3Acceptor implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SampleILink3Acceptor.class);

    @Option(names = {"-p", "--port"}, description = "Listen port (default: 9880)")
    private int port = 9880;

    @Option(names = {"-s", "--session"}, description = "Session ID (default: ILINK3-ACC)")
    private String sessionId = "ILINK3-ACC";

    @Option(names = {"--firm"}, description = "Firm ID (default: CME)")
    private String firmId = "CME";

    private final AtomicLong orderIdSequence = new AtomicLong(1000000);

    @Override
    public Integer call() throws Exception {
        log.info("Starting iLink 3 Acceptor on port {}", port);

        // Create engine configuration
        ILink3EngineConfig engineConfig = new ILink3EngineConfig();

        // Create session configuration
        ILink3SessionConfig sessionConfig = ILink3SessionConfig.builder()
                .sessionId(sessionId)
                .port(port)
                .acceptor()
                .firmId(firmId)
                .session("001")
                .keepAliveInterval(30000)
                .build();

        engineConfig.addSession(sessionConfig);

        // Create and start engine
        ILink3Engine engine = new ILink3Engine(engineConfig);

        // Add message listener
        engine.addMessageListener((session, message) -> handleMessage(session, message));

        engine.initialize();
        engine.start();

        log.info("iLink 3 Acceptor started. Press Ctrl+C to stop.");

        // Wait for shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            engine.stop();
        }));

        Thread.currentThread().join();
        return 0;
    }

    private void handleMessage(SbeSession<?> session, SbeMessage message) {
        if (message instanceof NewOrderSingleMessage order) {
            handleNewOrder((ILink3Session) session, order);
        }
    }

    private void handleNewOrder(ILink3Session session, NewOrderSingleMessage order) {
        String clOrdId = order.getClOrdId();
        double price = order.getPrice();
        int qty = order.getOrderQty();
        byte side = order.getSide();

        log.info("Received NewOrderSingle: clOrdId={}, price={}, qty={}, side={}",
                clOrdId, price, qty, side);

        // Generate execution report
        ExecutionReportNewMessage execReport = session.tryClaim(ExecutionReportNewMessage.class);
        if (execReport != null) {
            try {
                long orderId = orderIdSequence.incrementAndGet();

                execReport.writeHeader();
                execReport.setSeqNum((int) session.getOutboundSeqNum())
                         .setUuid(session.getUuid())
                         .setClOrdId(clOrdId)
                         .setOrderId(orderId)
                         .setPrice(price)
                         .setOrderQty(qty)
                         .setLeavesQty(qty)
                         .setCumQty(0)
                         .setTransactTime(System.nanoTime())
                         .setSendingTimeEpoch(System.nanoTime());

                session.commit(execReport);
                log.info("Sent ExecutionReportNew: clOrdId={}, orderId={}", clOrdId, orderId);
            } catch (Exception e) {
                session.abort(execReport);
                log.error("Error sending ExecutionReport", e);
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SampleILink3Acceptor()).execute(args);
        System.exit(exitCode);
    }
}
