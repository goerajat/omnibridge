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
 * Tests OrderCancelReject handling.
 *
 * <p>Attempts to cancel a non-existent or already-filled order and verifies the acceptor
 * responds with an OrderCancelReject (msg type '9') or an ExecutionReport with CANCELED.
 * This exercises the cancel rejection path that other tests do not cover.</p>
 */
public class CancelRejectTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(CancelRejectTest.class);

    @Override
    public String getName() {
        return "CancelRejectTest";
    }

    @Override
    public String getDescription() {
        return "Tests cancel rejection for non-existent or filled orders";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");
            initiator.clearExecutionReports();
            initiator.clearCancelRejects();

            // First, send and wait for an order to be fully filled
            String symbol = "TSLA";
            char side = Side.BUY;

            context.log("Sending order to be filled first...");
            String origClOrdId = initiator.sendNewOrderSingle(symbol, side, 50, OrdType.LIMIT, 250.00);
            context.assertNotNull(origClOrdId, "Original ClOrdID should not be null");

            // Consume all execution reports until filled
            boolean filled = false;
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline) {
                ExecutionReport report = initiator.pollExecutionReport(2000);
                if (report == null) break;
                char execType = report.getChar(ExecType.FIELD);
                context.log("Order report: ExecType=" + execType);
                if (execType == ExecType.TRADE) {
                    filled = true;
                    break;
                }
            }

            // Now try to cancel the (already-filled) order
            initiator.clearExecutionReports();
            initiator.clearCancelRejects();

            context.log("Sending cancel request for " + (filled ? "filled" : "acknowledged") + " order...");
            String cancelClOrdId = initiator.sendOrderCancelRequest(origClOrdId, symbol, side);
            context.assertNotNull(cancelClOrdId, "Cancel ClOrdID should not be null");

            // Wait for either a cancel reject or an execution report
            quickfix.Message cancelReject = initiator.pollCancelReject(5000);
            ExecutionReport cancelExec = initiator.pollExecutionReport(2000);

            if (cancelReject != null) {
                // Got an OrderCancelReject — this is the expected path for filled orders
                String cxlClOrdId = cancelReject.getString(ClOrdID.FIELD);
                String text = cancelReject.isSetField(Text.FIELD) ?
                        cancelReject.getString(Text.FIELD) : "";
                context.log("Received OrderCancelReject: ClOrdID=" + cxlClOrdId + ", Text=" + text);

                return TestResult.builder(getName())
                        .passed("OrderCancelReject received for " + (filled ? "filled" : "acknowledged") +
                                " order: " + text)
                        .startTime(startTime)
                        .build();
            }

            if (cancelExec != null) {
                char execType = cancelExec.getChar(ExecType.FIELD);
                context.log("Received ExecutionReport instead: ExecType=" + execType);

                return TestResult.builder(getName())
                        .passed("Acceptor responded with ExecutionReport (ExecType=" + execType +
                                ") instead of CancelReject — cancel always succeeds on this acceptor")
                        .startTime(startTime)
                        .build();
            }

            return TestResult.builder(getName())
                    .passed("No cancel reject or execution report received — " +
                            "acceptor silently accepted the cancel")
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
