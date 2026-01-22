package com.fixengine.reference.test.tests;

import com.fixengine.reference.initiator.ReferenceInitiator;
import com.fixengine.reference.test.ReferenceTest;
import com.fixengine.reference.test.TestContext;
import com.fixengine.reference.test.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;

import java.time.Instant;

/**
 * Tests order modification (cancel/replace).
 */
public class OrderModifyTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(OrderModifyTest.class);

    @Override
    public String getName() {
        return "OrderModifyTest";
    }

    @Override
    public String getDescription() {
        return "Tests submitting an order and modifying it via cancel/replace";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");

            // Clear any previous execution reports
            initiator.clearExecutionReports();

            // Send initial order
            String symbol = "GOOGL";
            char side = Side.SELL;
            double originalQty = 50;
            double originalPrice = 140.00;

            context.log("Sending initial order: " + symbol + " SELL " + originalQty + " @ " + originalPrice);
            String clOrdId = initiator.sendNewOrderSingle(symbol, side, originalQty, OrdType.LIMIT, originalPrice);
            context.assertNotNull(clOrdId, "ClOrdID should not be null");
            context.log("Order sent with ClOrdID: " + clOrdId);

            // Wait for acknowledgment
            ExecutionReport ackReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(ackReport, "Should receive acknowledgment");

            char ackExecType = ackReport.getChar(ExecType.FIELD);
            context.log("Received acknowledgment: ExecType=" + ackExecType);

            // If already filled, skip modify test
            if (ackExecType == ExecType.TRADE) {
                context.log("Order already filled, skipping modify test");
                return TestResult.builder(getName())
                        .passed("Order was filled before modify could be sent")
                        .startTime(startTime)
                        .build();
            }

            // Small delay to allow any pending fills to arrive
            Thread.sleep(100);

            // Check if there's a fill report already in the queue
            ExecutionReport possibleFill = initiator.pollExecutionReport(100);
            if (possibleFill != null) {
                char fillExecType = possibleFill.getChar(ExecType.FIELD);
                context.log("Found additional report in queue: ExecType=" + fillExecType);
                if (fillExecType == ExecType.TRADE) {
                    context.log("Order was filled, skipping modify test");
                    return TestResult.builder(getName())
                            .passed("Order was filled before modify could be sent")
                            .startTime(startTime)
                            .build();
                }
            }

            // Clear for next report
            initiator.clearExecutionReports();

            // Send cancel/replace to modify
            double newQty = 75;
            double newPrice = 142.50;

            context.log("Sending cancel/replace: new qty=" + newQty + ", new price=" + newPrice);
            String replaceClOrdId = initiator.sendOrderCancelReplaceRequest(
                    clOrdId, symbol, side, newQty, newPrice, OrdType.LIMIT);
            context.assertNotNull(replaceClOrdId, "Replace ClOrdID should not be null");
            context.log("Replace request sent with ClOrdID: " + replaceClOrdId);

            // Wait for replace confirmation
            ExecutionReport replaceReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(replaceReport, "Should receive replace confirmation");

            char replaceExecType = replaceReport.getChar(ExecType.FIELD);
            context.log("Received replace response: ExecType=" + replaceExecType);

            // Verify the replace was acknowledged
            context.assertTrue(
                    replaceExecType == ExecType.REPLACED ||
                            replaceExecType == ExecType.NEW,
                    "Order should be replaced");

            return TestResult.builder(getName())
                    .passed("Order submitted and modified successfully")
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
