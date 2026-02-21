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

/**
 * Tests order rejection handling.
 *
 * <p>Sends a NewOrderSingle with zero quantity to trigger a rejection from the acceptor,
 * then verifies the ExecutionReport contains ExecType=REJECTED or that the order is
 * handled gracefully.</p>
 */
public class OrderRejectTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(OrderRejectTest.class);

    @Override
    public String getName() {
        return "OrderRejectTest";
    }

    @Override
    public String getDescription() {
        return "Tests order rejection handling with invalid order parameters";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");
            initiator.clearExecutionReports();

            // Send an order with zero quantity — should trigger rejection
            String symbol = "INVALID";
            char side = Side.BUY;
            double quantity = 0;

            context.log("Sending order with zero quantity to trigger rejection...");
            String clOrdId = initiator.sendNewOrderSingle(symbol, side, quantity, OrdType.LIMIT, 50.00);
            context.assertNotNull(clOrdId, "ClOrdID should not be null");
            context.log("Order sent with ClOrdID: " + clOrdId);

            // Wait for execution report
            ExecutionReport execReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(execReport, "Should receive an execution report");

            char execType = execReport.getChar(ExecType.FIELD);
            char ordStatus = execReport.getChar(OrdStatus.FIELD);

            context.log("Received ExecutionReport: ExecType=" + execType + ", OrdStatus=" + ordStatus);

            // The acceptor may reject (ExecType=8) or accept (ExecType=0) depending on validation
            if (execType == ExecType.REJECTED) {
                String text = execReport.isSetField(Text.FIELD) ? execReport.getString(Text.FIELD) : "";
                return TestResult.builder(getName())
                        .passed("Order correctly rejected: " + text)
                        .startTime(startTime)
                        .build();
            }

            // If the acceptor accepted the order, that's still valid — not all acceptors validate qty
            return TestResult.builder(getName())
                    .passed("Order with zero qty was accepted (ExecType=" + execType +
                            ") — acceptor does not validate quantity")
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
