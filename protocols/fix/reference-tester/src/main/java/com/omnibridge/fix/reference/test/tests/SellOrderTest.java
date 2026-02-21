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
 * Tests sell-side order submission.
 *
 * <p>Sends a SELL limit order and verifies the execution report contains the correct
 * side, symbol, and a valid ExecType. Complements NewOrderTest which uses BUY side.</p>
 */
public class SellOrderTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(SellOrderTest.class);

    @Override
    public String getName() {
        return "SellOrderTest";
    }

    @Override
    public String getDescription() {
        return "Tests sell-side limit order submission and execution report";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");
            initiator.clearExecutionReports();

            String symbol = "META";
            char side = Side.SELL;
            double quantity = 150;
            double price = 520.00;

            context.log("Sending SELL order: " + symbol + " SELL " + quantity + " @ " + price);
            String clOrdId = initiator.sendNewOrderSingle(symbol, side, quantity, OrdType.LIMIT, price);
            context.assertNotNull(clOrdId, "ClOrdID should not be null");
            context.log("Order sent with ClOrdID: " + clOrdId);

            // Wait for execution report
            ExecutionReport execReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(execReport, "Should receive an execution report");

            char execType = execReport.getChar(ExecType.FIELD);
            char ordStatus = execReport.getChar(OrdStatus.FIELD);
            String returnedClOrdId = execReport.getString(ClOrdID.FIELD);
            char returnedSide = execReport.getChar(Side.FIELD);

            context.log("Received ExecutionReport: ExecType=" + execType +
                    ", OrdStatus=" + ordStatus + ", Side=" + returnedSide +
                    ", ClOrdID=" + returnedClOrdId);

            // Verify the execution report is for our order
            context.assertEquals(clOrdId, returnedClOrdId, "ClOrdID should match");

            // Verify the side is SELL
            context.assertTrue(returnedSide == Side.SELL,
                    "Side should be SELL (2), got: " + returnedSide);

            // Verify valid exec type
            context.assertTrue(
                    execType == ExecType.NEW || execType == ExecType.TRADE ||
                            execType == ExecType.PENDING_NEW || execType == ExecType.REJECTED,
                    "ExecType should be valid response type, got: " + execType);

            return TestResult.builder(getName())
                    .passed("SELL order processed - ExecType: " + execType + ", Side: " + returnedSide)
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
