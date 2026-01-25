package com.omnibridge.pillar.samples;

import com.omnibridge.pillar.engine.PillarEngine;
import com.omnibridge.pillar.engine.PillarSession;
import com.omnibridge.pillar.engine.config.PillarEngineConfig;
import com.omnibridge.pillar.engine.config.PillarSessionConfig;
import com.omnibridge.pillar.message.order.ExecutionReportMessage;
import com.omnibridge.pillar.message.order.NewOrderMessage;
import com.omnibridge.pillar.message.order.OrderAckMessage;
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
 * Sample NYSE Pillar initiator application.
 * <p>
 * Connects to an acceptor and sends test orders following
 * the Pillar stream protocol.
 */
@Command(name = "pillar-initiator", mixinStandardHelpOptions = true,
         description = "Sample NYSE Pillar Initiator")
public class SamplePillarInitiator implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SamplePillarInitiator.class);

    @Option(names = {"-h", "--host"}, description = "Host to connect to (default: localhost)")
    private String host = "localhost";

    @Option(names = {"-p", "--port"}, description = "Port to connect to (default: 9400)")
    private int port = 9400;

    @Option(names = {"-s", "--session"}, description = "Session ID (default: PILLAR-INIT)")
    private String sessionId = "PILLAR-INIT";

    @Option(names = {"-n", "--num-orders"}, description = "Number of orders to send (default: 10)")
    private int numOrders = 10;

    @Option(names = {"--mpid"}, description = "Market Participant ID (default: TEST)")
    private String mpid = "TEST";

    @Option(names = {"--account"}, description = "Account (default: TESTACCT)")
    private String account = "TESTACCT";

    private final AtomicLong clOrdIdSequence = new AtomicLong(1);
    private final CountDownLatch establishedLatch = new CountDownLatch(1);

    @Override
    public Integer call() throws Exception {
        log.info("Starting NYSE Pillar Initiator connecting to {}:{}", host, port);

        // Create engine configuration
        PillarEngineConfig engineConfig = new PillarEngineConfig();

        // Create session configuration
        PillarSessionConfig sessionConfig = PillarSessionConfig.builder()
                .sessionId(sessionId)
                .host(host)
                .port(port)
                .initiator(true)
                .mpid(mpid)
                .account(account)
                .username("PILLAR-TEST")
                .password("password")
                .heartbeatIntervalMillis(1000)
                .protocolVersion(1, 0)
                .build();

        engineConfig.addSession(sessionConfig);

        // Create engine
        PillarEngine engine = new PillarEngine(engineConfig);

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

        PillarSession session = engine.getPillarSession(sessionId);

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

    private void sendTestOrder(PillarSession session, int index) {
        NewOrderMessage order = session.tryClaim(NewOrderMessage.class);
        if (order == null) {
            log.warn("Failed to claim NewOrder message");
            return;
        }

        try {
            long clOrdId = clOrdIdSequence.incrementAndGet();
            // Price with 8 decimal places (10^8 scale)
            long rawPrice = (100_00000000L) + (index * 25_000000L); // 100.00 + 0.25 increment
            long qty = 100 + (index * 10);
            byte side = (byte) ((index % 2 == 0) ? 1 : 2); // Alternate buy/sell

            order.writeHeader();
            order.setClOrdId(clOrdId)
                 .setSymbol("IBM")
                 .setMpid(mpid)
                 .setAccount(account)
                 .setSide(side)
                 .setOrdType((byte) 2) // Limit
                 .setRawPrice(rawPrice)
                 .setOrderQty(qty)
                 .setTimeInForce((byte) 0) // Day
                 .setTransactTime(System.nanoTime());

            session.commit(order);
            log.info("Sent NewOrder: clOrdId={}, price={}, qty={}, side={}",
                    clOrdId, rawPrice / 100000000.0, qty, side == 1 ? "Buy" : "Sell");
        } catch (Exception e) {
            session.abort(order);
            log.error("Error sending order", e);
        }
    }

    private void handleMessage(PillarSession session, SbeMessage message) {
        if (message instanceof OrderAckMessage ack) {
            log.info("Received OrderAck: clOrdId={}, orderId={}, status={}",
                    ack.getClOrdId(), ack.getOrderId(), ack.getOrdStatus());
        } else if (message instanceof ExecutionReportMessage execReport) {
            log.info("Received ExecutionReport: clOrdId={}, execId={}, ordStatus={}, lastQty={}, cumQty={}",
                    execReport.getClOrdId(), execReport.getExecId(),
                    execReport.getOrdStatus(), execReport.getLastQty(), execReport.getCumQty());
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SamplePillarInitiator()).execute(args);
        System.exit(exitCode);
    }
}
