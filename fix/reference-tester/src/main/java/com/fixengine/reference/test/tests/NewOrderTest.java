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
 * Tests new order submission.
 */
public class NewOrderTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(NewOrderTest.class);

    @Override
    public String getName() {
        return "NewOrderTest";
    }

    @Override
    public String getDescription() {
        return "Tests submitting a new limit order and receiving execution report";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");

            // Clear any previous execution reports
            initiator.clearExecutionReports();

            // Send a new limit order
            String symbol = "AAPL";
            char side = Side.BUY;
            double quantity = 100;
            char ordType = OrdType.LIMIT;
            double price = 150.00;

            context.log("Sending NewOrderSingle: " + symbol + " " +
                    (side == Side.BUY ? "BUY" : "SELL") + " " + quantity + " @ " + price);

            String clOrdId = initiator.sendNewOrderSingle(symbol, side, quantity, ordType, price);
            context.assertNotNull(clOrdId, "ClOrdID should not be null");
            context.log("Order sent with ClOrdID: " + clOrdId);

            // Wait for execution report
            ExecutionReport execReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(execReport, "Should receive an execution report");

            char execType = execReport.getChar(ExecType.FIELD);
            char ordStatus = execReport.getChar(OrdStatus.FIELD);
            String returnedClOrdId = execReport.getString(ClOrdID.FIELD);

            context.log("Received ExecutionReport: ExecType=" + execType +
                    ", OrdStatus=" + ordStatus + ", ClOrdID=" + returnedClOrdId);

            // Verify the execution report is for our order
            context.assertEquals(clOrdId, returnedClOrdId,
                    "ClOrdID should match");

            // Verify we got an acknowledgment (NEW) or fill (TRADE/FILLED)
            context.assertTrue(
                    execType == ExecType.NEW || execType == ExecType.TRADE ||
                            execType == ExecType.PENDING_NEW,
                    "ExecType should be NEW, PENDING_NEW, or TRADE, got: " + execType);

            return TestResult.builder(getName())
                    .passed("Order submitted and execution report received - ExecType: " + execType)
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
