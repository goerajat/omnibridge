package com.fixengine.samples.initiator;

import com.fixengine.engine.session.FixSession;
import com.fixengine.message.FixTags;
import com.fixengine.message.RingBufferOutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Auto mode for the FIX initiator.
 * Automatically sends a specified number of sample orders.
 */
public class AutoModeRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoModeRunner.class);
    private static final String[] SYMBOLS = {"AAPL", "GOOGL", "MSFT", "AMZN", "META"};

    private final FixSession session;
    private final int orderCount;
    private final AtomicLong clOrdIdCounter = new AtomicLong(1);
    private volatile boolean running = true;

    public AutoModeRunner(FixSession session, int orderCount) {
        this.session = session;
        this.orderCount = orderCount;
    }

    /**
     * Run auto mode, sending sample orders.
     */
    public void run() throws InterruptedException {
        log.info("Auto mode: sending {} sample orders", orderCount);

        for (int i = 0; i < orderCount && running; i++) {
            String symbol = SYMBOLS[i % SYMBOLS.length];
            char side = (i % 2 == 0) ? FixTags.SIDE_BUY : FixTags.SIDE_SELL;
            int qty = (i + 1) * 100;
            double price = 100.0 + (i * 0.5);

            sendNewOrder(symbol, side, qty, price);

            Thread.sleep(500); // Wait between orders
        }

        log.info("Finished sending {} orders", orderCount);
        Thread.sleep(2000); // Wait for responses
    }

    private void sendNewOrder(String symbol, char side, int qty, double price) {
        String clOrdId = "AUTO" + clOrdIdCounter.getAndIncrement();

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
            order.setField(FixTags.OrdType, FixTags.ORD_TYPE_LIMIT);
            order.setField(FixTags.Price, price, 2);
            order.setField(FixTags.TimeInForce, FixTags.TIF_DAY);
            order.setField(FixTags.TransactTime, Instant.now().toString());

            int seqNum = order.getSeqNum();
            session.commitMessage(order);
            log.info("Sent NewOrderSingle: ClOrdID={}, Symbol={}, Side={}, Qty={}, Price={}, SeqNum={}",
                    clOrdId, symbol, side == FixTags.SIDE_BUY ? "BUY" : "SELL", qty, price, seqNum);
        } catch (Exception e) {
            log.error("Error sending order", e);
        }
    }

    public void stop() {
        running = false;
    }
}
