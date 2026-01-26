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
 * Tests market order submission.
 */
public class MarketOrderTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(MarketOrderTest.class);

    @Override
    public String getName() {
        return "MarketOrderTest";
    }

    @Override
    public String getDescription() {
        return "Tests submitting a market order and receiving execution report";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            // Verify we're logged on
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");

            // Clear any previous execution reports
            initiator.clearExecutionReports();

            // Send a market order (no price)
            String symbol = "TSLA";
            char side = Side.BUY;
            double quantity = 50;

            context.log("Sending market order: " + symbol + " " +
                    (side == Side.BUY ? "BUY" : "SELL") + " " + quantity + " MARKET");

            String clOrdId = initiator.sendNewOrderSingle(symbol, side, quantity, OrdType.MARKET, 0);
            context.assertNotNull(clOrdId, "ClOrdID should not be null");
            context.log("Market order sent with ClOrdID: " + clOrdId);

            // Wait for execution report
            ExecutionReport execReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(execReport, "Should receive an execution report");

            char execType = execReport.getChar(ExecType.FIELD);
            char ordStatus = execReport.getChar(OrdStatus.FIELD);
            String returnedClOrdId = execReport.getString(ClOrdID.FIELD);

            context.log("Received ExecutionReport: ExecType=" + execType +
                    ", OrdStatus=" + ordStatus + ", ClOrdID=" + returnedClOrdId);

            // Verify the execution report is for our order
            context.assertEquals(clOrdId, returnedClOrdId, "ClOrdID should match");

            // Verify we got an acknowledgment or fill
            context.assertTrue(
                    execType == ExecType.NEW || execType == ExecType.TRADE ||
                            execType == ExecType.PENDING_NEW || execType == ExecType.REJECTED,
                    "ExecType should be valid response type, got: " + execType);

            return TestResult.builder(getName())
                    .passed("Market order processed - ExecType: " + execType)
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
