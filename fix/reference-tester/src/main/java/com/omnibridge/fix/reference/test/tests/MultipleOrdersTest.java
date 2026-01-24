package com.omnibridge.fix.reference.test.tests;

import com.omnibridge.fix.reference.initiator.ReferenceInitiator;
import com.omnibridge.fix.reference.test.ReferenceTest;
import com.omnibridge.fix.reference.test.TestContext;
import com.omnibridge.fix.reference.test.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests multiple order submission in sequence.
 */
public class MultipleOrdersTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(MultipleOrdersTest.class);
    private static final int ORDER_COUNT = 5;

    @Override
    public String getName() {
        return "MultipleOrdersTest";
    }

    @Override
    public String getDescription() {
        return "Tests submitting multiple orders in sequence and receiving all execution reports";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");

            // Clear any previous execution reports
            initiator.clearExecutionReports();

            // Send multiple orders
            List<String> clOrdIds = new ArrayList<>();
            String[] symbols = {"AAPL", "MSFT", "GOOGL", "AMZN", "META"};

            context.log("Sending " + ORDER_COUNT + " orders...");

            for (int i = 0; i < ORDER_COUNT; i++) {
                String symbol = symbols[i % symbols.length];
                char side = i % 2 == 0 ? Side.BUY : Side.SELL;
                double quantity = 100 + (i * 10);
                double price = 100.00 + (i * 5);

                String clOrdId = initiator.sendNewOrderSingle(symbol, side, quantity, OrdType.LIMIT, price);
                context.assertNotNull(clOrdId, "ClOrdID should not be null for order " + i);
                clOrdIds.add(clOrdId);

                context.log("Sent order " + (i + 1) + ": " + symbol + " " +
                        (side == Side.BUY ? "BUY" : "SELL") + " " + quantity + " @ " + price);

                // Small delay between orders
                context.sleep(100);
            }

            // Wait for all execution reports
            context.log("Waiting for execution reports...");
            int reportsReceived = 0;
            int timeout = 30000; // 30 seconds total timeout
            long deadline = System.currentTimeMillis() + timeout;

            while (reportsReceived < ORDER_COUNT && System.currentTimeMillis() < deadline) {
                ExecutionReport report = context.waitForExecutionReport(initiator, 5000);
                if (report != null) {
                    reportsReceived++;
                    String clOrdId = report.getString(ClOrdID.FIELD);
                    char execType = report.getChar(ExecType.FIELD);
                    context.log("Received report " + reportsReceived + ": ClOrdID=" + clOrdId +
                            ", ExecType=" + execType);
                }
            }

            context.log("Received " + reportsReceived + " of " + ORDER_COUNT + " expected reports");

            // We should have received at least some execution reports
            context.assertTrue(reportsReceived >= ORDER_COUNT,
                    "Should receive at least " + ORDER_COUNT + " execution reports, got: " + reportsReceived);

            return TestResult.builder(getName())
                    .passed("Sent " + ORDER_COUNT + " orders, received " + reportsReceived + " execution reports")
                    .startTime(startTime)
                    .build();

        } catch (AssertionError e) {
            return TestResult.builder(getName())
                    .failed(e.getMessage())
                    .startTime(startTime)
                    .build();
        } catch (Exception e) {
            return TestResult.builder(getName())
                    .error("Exception: " + e.getMessage(), e)
                    .startTime(startTime)
                    .build();
        }
    }
}
