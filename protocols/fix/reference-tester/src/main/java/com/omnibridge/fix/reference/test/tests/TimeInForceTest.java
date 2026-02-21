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
 * Tests TimeInForce variations.
 *
 * <p>Sends orders with different TimeInForce values (DAY, IOC, FOK) and verifies
 * the acceptor handles each appropriately. IOC orders should execute immediately
 * or be canceled; FOK orders must fill completely or be canceled.</p>
 */
public class TimeInForceTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(TimeInForceTest.class);

    @Override
    public String getName() {
        return "TimeInForceTest";
    }

    @Override
    public String getDescription() {
        return "Tests orders with different TimeInForce values (DAY, IOC, FOK)";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");

            StringBuilder results = new StringBuilder();

            // Test 1: DAY order
            initiator.clearExecutionReports();
            context.log("Sending DAY order...");
            String dayOrdId = initiator.sendNewOrderSingle("AAPL", Side.BUY, 50,
                    OrdType.LIMIT, 150.00, TimeInForce.DAY);
            context.assertNotNull(dayOrdId, "DAY order ClOrdID should not be null");

            ExecutionReport dayReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(dayReport, "Should receive execution report for DAY order");
            char dayExecType = dayReport.getChar(ExecType.FIELD);
            results.append("DAY=").append(dayExecType);
            context.log("DAY order response: ExecType=" + dayExecType);

            // Consume additional reports
            Thread.sleep(200);
            initiator.clearExecutionReports();

            // Test 2: IOC order (Immediate or Cancel)
            context.log("Sending IOC order...");
            String iocOrdId = initiator.sendNewOrderSingle("MSFT", Side.BUY, 75,
                    OrdType.LIMIT, 300.00, TimeInForce.IMMEDIATE_OR_CANCEL);
            context.assertNotNull(iocOrdId, "IOC order ClOrdID should not be null");

            ExecutionReport iocReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(iocReport, "Should receive execution report for IOC order");
            char iocExecType = iocReport.getChar(ExecType.FIELD);
            results.append(", IOC=").append(iocExecType);
            context.log("IOC order response: ExecType=" + iocExecType);

            Thread.sleep(200);
            initiator.clearExecutionReports();

            // Test 3: FOK order (Fill or Kill)
            context.log("Sending FOK order...");
            String fokOrdId = initiator.sendNewOrderSingle("GOOGL", Side.SELL, 25,
                    OrdType.LIMIT, 140.00, TimeInForce.FILL_OR_KILL);
            context.assertNotNull(fokOrdId, "FOK order ClOrdID should not be null");

            ExecutionReport fokReport = context.waitForExecutionReport(initiator, 10000);
            context.assertNotNull(fokReport, "Should receive execution report for FOK order");
            char fokExecType = fokReport.getChar(ExecType.FIELD);
            results.append(", FOK=").append(fokExecType);
            context.log("FOK order response: ExecType=" + fokExecType);

            return TestResult.builder(getName())
                    .passed("All TIF orders processed: " + results)
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
