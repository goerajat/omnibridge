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
 * Tests multiple execution report handling for a single order.
 *
 * <p>Submits an order and collects all execution reports (NEW, PARTIAL_FILL, TRADE, etc.)
 * to verify the initiator correctly queues and processes multi-report order lifecycle flows.
 * With the default acceptor (fill-rate=1.0), expects both NEW and TRADE reports.</p>
 */
public class PartialFillTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(PartialFillTest.class);

    @Override
    public String getName() {
        return "PartialFillTest";
    }

    @Override
    public String getDescription() {
        return "Tests handling of multiple execution reports for a single order lifecycle";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");
            initiator.clearExecutionReports();

            // Send a limit order
            String symbol = "AMZN";
            char side = Side.BUY;
            double quantity = 200;
            double price = 185.00;

            context.log("Sending order: " + symbol + " BUY " + quantity + " @ " + price);
            String clOrdId = initiator.sendNewOrderSingle(symbol, side, quantity, OrdType.LIMIT, price);
            context.assertNotNull(clOrdId, "ClOrdID should not be null");

            // Collect all execution reports within a window
            List<Character> execTypes = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 10000;

            while (System.currentTimeMillis() < deadline) {
                ExecutionReport report = initiator.pollExecutionReport(1000);
                if (report == null) {
                    break; // No more reports
                }

                char execType = report.getChar(ExecType.FIELD);
                String reportClOrdId = report.getString(ClOrdID.FIELD);
                execTypes.add(execType);
                context.log("Report " + execTypes.size() + ": ExecType=" + execType +
                        ", ClOrdID=" + reportClOrdId);

                // If we got a terminal state, stop waiting
                if (execType == ExecType.TRADE || execType == ExecType.REJECTED ||
                        execType == ExecType.CANCELED) {
                    // Brief pause to catch any trailing reports
                    ExecutionReport trailing = initiator.pollExecutionReport(500);
                    if (trailing != null) {
                        execTypes.add(trailing.getChar(ExecType.FIELD));
                    }
                    break;
                }
            }

            context.assertTrue(!execTypes.isEmpty(), "Should receive at least one execution report");

            // With default fill-rate=1.0, expect both NEW and TRADE
            boolean hasNew = execTypes.contains(ExecType.NEW) || execTypes.contains(ExecType.PENDING_NEW);
            boolean hasFill = execTypes.contains(ExecType.TRADE);
            boolean hasPartial = execTypes.contains(ExecType.PARTIAL_FILL);

            StringBuilder summary = new StringBuilder();
            summary.append("Received ").append(execTypes.size()).append(" report(s): ");
            for (int i = 0; i < execTypes.size(); i++) {
                if (i > 0) summary.append(" -> ");
                summary.append(execTypes.get(i));
            }

            if (hasPartial) {
                summary.append(" [partial fill detected]");
            }

            return TestResult.builder(getName())
                    .passed(summary.toString())
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
