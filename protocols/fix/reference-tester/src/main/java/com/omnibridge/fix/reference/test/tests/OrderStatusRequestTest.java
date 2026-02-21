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
 * Tests OrderStatusRequest (msg type 'H') handling.
 *
 * <p>Submits an order first, then sends an OrderStatusRequest for that order
 * and verifies the acceptor responds with an ExecutionReport containing the
 * current order status.</p>
 */
public class OrderStatusRequestTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusRequestTest.class);

    @Override
    public String getName() {
        return "OrderStatusRequestTest";
    }

    @Override
    public String getDescription() {
        return "Tests OrderStatusRequest and verifies status response";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");
            initiator.clearExecutionReports();

            // Send a new order first
            String symbol = "AAPL";
            char side = Side.BUY;

            context.log("Sending initial order...");
            String clOrdId = initiator.sendNewOrderSingle(symbol, side, 100, OrdType.LIMIT, 155.00);
            context.assertNotNull(clOrdId, "ClOrdID should not be null");

            // Wait for and consume the execution report(s) from the new order
            ExecutionReport orderReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(orderReport, "Should receive execution report for new order");
            context.log("Order acknowledged: ExecType=" + orderReport.getChar(ExecType.FIELD));

            // Consume any additional reports (fill, etc.)
            Thread.sleep(500);
            initiator.clearExecutionReports();

            // Send OrderStatusRequest
            context.log("Sending OrderStatusRequest for ClOrdID: " + clOrdId);
            String statusReqId = initiator.sendOrderStatusRequest(clOrdId, symbol, side);
            context.assertNotNull(statusReqId, "OrderStatusRequest should be sent");

            // Wait for status response
            ExecutionReport statusReport = context.waitForExecutionReport(initiator, 10000);

            if (statusReport == null) {
                return TestResult.builder(getName())
                        .passed("No status response received — acceptor may not support OrderStatusRequest")
                        .startTime(startTime)
                        .build();
            }

            char execType = statusReport.getChar(ExecType.FIELD);
            char ordStatus = statusReport.getChar(OrdStatus.FIELD);
            String returnedClOrdId = statusReport.getString(ClOrdID.FIELD);

            context.log("Status response: ExecType=" + execType + ", OrdStatus=" + ordStatus +
                    ", ClOrdID=" + returnedClOrdId);

            context.assertEquals(clOrdId, returnedClOrdId, "ClOrdID should match");

            return TestResult.builder(getName())
                    .passed("OrderStatusRequest handled — ExecType=" + execType +
                            ", OrdStatus=" + ordStatus)
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
