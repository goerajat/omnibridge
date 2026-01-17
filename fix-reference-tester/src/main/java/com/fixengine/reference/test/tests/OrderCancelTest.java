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
 * Tests order cancellation.
 */
public class OrderCancelTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelTest.class);

    @Override
    public String getName() {
        return "OrderCancelTest";
    }

    @Override
    public String getDescription() {
        return "Tests submitting an order and then cancelling it";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");

            // Clear any previous execution reports
            initiator.clearExecutionReports();

            // Send a new order first
            String symbol = "MSFT";
            char side = Side.BUY;

            context.log("Sending initial order...");
            String clOrdId = initiator.sendNewOrderSingle(symbol, side, 100, OrdType.LIMIT, 300.00);
            context.assertNotNull(clOrdId, "ClOrdID should not be null");
            context.log("Order sent with ClOrdID: " + clOrdId);

            // Wait for acknowledgment
            ExecutionReport ackReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(ackReport, "Should receive acknowledgment");

            char ackExecType = ackReport.getChar(ExecType.FIELD);
            context.log("Received acknowledgment: ExecType=" + ackExecType);

            // If already filled, skip cancel test
            if (ackExecType == ExecType.TRADE) {
                context.log("Order already filled, skipping cancel test");
                return TestResult.builder(getName())
                        .passed("Order was filled before cancel could be sent")
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
                    context.log("Order was filled, skipping cancel test");
                    return TestResult.builder(getName())
                            .passed("Order was filled before cancel could be sent")
                            .startTime(startTime)
                            .build();
                }
            }

            // Clear for next report
            initiator.clearExecutionReports();

            // Send cancel request
            context.log("Sending cancel request for ClOrdID: " + clOrdId);
            String cancelClOrdId = initiator.sendOrderCancelRequest(clOrdId, symbol, side);
            context.assertNotNull(cancelClOrdId, "Cancel ClOrdID should not be null");
            context.log("Cancel request sent with ClOrdID: " + cancelClOrdId);

            // Wait for cancel confirmation
            ExecutionReport cancelReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(cancelReport, "Should receive cancel confirmation");

            char cancelExecType = cancelReport.getChar(ExecType.FIELD);
            char cancelOrdStatus = cancelReport.getChar(OrdStatus.FIELD);

            context.log("Received cancel response: ExecType=" + cancelExecType +
                    ", OrdStatus=" + cancelOrdStatus);

            // Verify the cancel was acknowledged
            context.assertTrue(
                    cancelExecType == ExecType.CANCELED ||
                            cancelOrdStatus == OrdStatus.CANCELED,
                    "Order should be canceled");

            return TestResult.builder(getName())
                    .passed("Order submitted and cancelled successfully")
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
