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
 * Tests order fill timing and response latency.
 *
 * <p>Sends an order and measures the time to receive the first execution report.
 * With the default acceptor (fill-delay=0), the response should be near-immediate.
 * When run against an acceptor with --fill-delay, the delay should be reflected.</p>
 */
public class FillDelayTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(FillDelayTest.class);

    @Override
    public String getName() {
        return "FillDelayTest";
    }

    @Override
    public String getDescription() {
        return "Tests order fill timing and measures response latency";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");
            initiator.clearExecutionReports();

            String symbol = "NFLX";
            char side = Side.BUY;

            context.log("Sending timed order...");
            long sendTime = System.nanoTime();
            String clOrdId = initiator.sendNewOrderSingle(symbol, side, 100, OrdType.LIMIT, 450.00);
            context.assertNotNull(clOrdId, "ClOrdID should not be null");

            // Wait for first execution report
            ExecutionReport report = context.waitForExecutionReport(initiator, 30000);
            long receiveTime = System.nanoTime();

            context.assertNotNull(report, "Should receive execution report");

            long latencyUs = (receiveTime - sendTime) / 1000;
            double latencyMs = latencyUs / 1000.0;

            char execType = report.getChar(ExecType.FIELD);
            context.log(String.format("Response received in %.2f ms: ExecType=%c", latencyMs, execType));

            // Wait for any additional reports (fill after ack)
            ExecutionReport fillReport = initiator.pollExecutionReport(5000);
            String fillInfo = "";
            if (fillReport != null) {
                long fillTime = System.nanoTime();
                double fillLatencyMs = (fillTime - sendTime) / 1_000_000.0;
                char fillExecType = fillReport.getChar(ExecType.FIELD);
                fillInfo = String.format(", fill ExecType=%c in %.2f ms", fillExecType, fillLatencyMs);
            }

            return TestResult.builder(getName())
                    .passed(String.format("First response in %.2f ms (ExecType=%c)%s",
                            latencyMs, execType, fillInfo))
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
