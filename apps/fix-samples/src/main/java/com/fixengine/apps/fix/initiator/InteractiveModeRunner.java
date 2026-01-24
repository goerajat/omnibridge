package com.fixengine.apps.fix.initiator;

import com.fixengine.engine.session.FixSession;
import com.fixengine.message.FixTags;
import com.fixengine.message.RingBufferOutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interactive mode for the FIX initiator.
 * Provides a command-line interface for sending orders.
 */
public class InteractiveModeRunner {

    private static final Logger log = LoggerFactory.getLogger(InteractiveModeRunner.class);

    private final FixSession session;
    private final AtomicLong clOrdIdCounter = new AtomicLong(1);
    private volatile boolean running = true;

    public InteractiveModeRunner(FixSession session) {
        this.session = session;
    }

    /**
     * Run the interactive command loop.
     */
    public void run() {
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

                processCommand(line);
            } catch (Exception e) {
                log.error("Error processing command", e);
            }
        }
    }

    private void processCommand(String line) {
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
    }

    private void sendNewOrder(String symbol, char side, int qty, char ordType, double price) {
        String clOrdId = "ORDER" + clOrdIdCounter.getAndIncrement();

        try {
            RingBufferOutgoingMessage order = session.tryClaimMessage(FixTags.MsgTypes.NewOrderSingle);
            if (order == null) {
                log.error("Ring buffer full - could not send order {}", clOrdId);
                return;
            }

            order.setField(FixTags.ClOrdID, clOrdId);
            order.setField(FixTags.Symbol, symbol);
            order.setField(FixTags.Side, side);
            order.setField(FixTags.OrderQty, qty);
            order.setField(FixTags.OrdType, ordType);
            order.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
            order.setField(FixTags.TransactTime, Instant.now().toString());

            if (ordType == FixTags.ORD_TYPE_LIMIT && price > 0) {
                order.setField(FixTags.Price, price, 2);
            }

            int seqNum = order.getSeqNum();
            session.commitMessage(order);
            log.info("Sent NewOrderSingle: ClOrdID={}, Symbol={}, Side={}, Qty={}, Price={}, SeqNum={}",
                    clOrdId, symbol, side == FixTags.SIDE_BUY ? "BUY" : "SELL", qty, price, seqNum);
        } catch (Exception e) {
            log.error("Error sending order", e);
        }
    }

    private void sendCancelRequest(String origClOrdId) {
        String clOrdId = "CANCEL" + clOrdIdCounter.getAndIncrement();

        try {
            RingBufferOutgoingMessage cancel = session.tryClaimMessage(FixTags.MsgTypes.OrderCancelRequest);
            if (cancel == null) {
                log.error("Ring buffer full - could not send cancel request {}", clOrdId);
                return;
            }

            cancel.setField(FixTags.ClOrdID, clOrdId);
            cancel.setField(41, origClOrdId); // OrigClOrdID
            cancel.setField(FixTags.Symbol, "UNKNOWN"); // In real app, would lookup
            cancel.setField(FixTags.Side, FixTags.SIDE_BUY); // In real app, would lookup
            cancel.setField(FixTags.TransactTime, Instant.now().toString());

            int seqNum = cancel.getSeqNum();
            session.commitMessage(cancel);
            log.info("Sent OrderCancelRequest: ClOrdID={}, OrigClOrdID={}, SeqNum={}",
                    clOrdId, origClOrdId, seqNum);
        } catch (Exception e) {
            log.error("Error sending cancel request", e);
        }
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
