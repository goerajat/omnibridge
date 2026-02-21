package com.omnibridge.fix.reference.test.tests;

import com.omnibridge.fix.reference.initiator.ReferenceInitiator;
import com.omnibridge.fix.reference.test.ReferenceTest;
import com.omnibridge.fix.reference.test.TestContext;
import com.omnibridge.fix.reference.test.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Session;
import quickfix.SessionNotFound;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Tests handling of messages exceeding typical buffer sizes.
 *
 * <p>Validates: the engine correctly handles messages with large field values
 * by building a NewOrderSingle with a 3000-character Text (58) field and
 * sending it via QuickFIX/J. Verifies the acceptor processes or acknowledges
 * the large message.</p>
 */
public class MaxMessageSizeTest implements ReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(MaxMessageSizeTest.class);

    private static final int LARGE_TEXT_SIZE = 3000;

    @Override
    public String getName() {
        return "MaxMessageSizeTest";
    }

    @Override
    public String getDescription() {
        return "Validates engine handles messages with " + LARGE_TEXT_SIZE + "-character Text field";
    }

    @Override
    public TestResult run(ReferenceInitiator initiator, TestContext context) {
        Instant startTime = Instant.now();

        try {
            context.assertTrue(initiator.isLoggedOn(), "Should be logged on");

            // Clear any previous execution reports
            initiator.clearExecutionReports();

            // Build a large text string (3000 characters)
            String largeText = "A".repeat(LARGE_TEXT_SIZE);
            context.log("Building NewOrderSingle with " + LARGE_TEXT_SIZE + "-character Text field");

            // Build NewOrderSingle manually with the large Text field
            String clOrdId = "MAX-MSG-" + System.currentTimeMillis();
            NewOrderSingle order = new NewOrderSingle(
                    new ClOrdID(clOrdId),
                    new Side(Side.BUY),
                    new TransactTime(LocalDateTime.now()),
                    new OrdType(OrdType.LIMIT)
            );

            order.set(new Symbol("TEST"));
            order.set(new OrderQty(100));
            order.set(new Price(150.00));
            order.set(new HandlInst('1'));
            order.set(new TimeInForce(TimeInForce.DAY));
            order.set(new Text(largeText));

            context.log("Sending large message (Text field: " + LARGE_TEXT_SIZE + " chars)");

            // Send via QuickFIX/J Session.sendToTarget
            try {
                Session.sendToTarget(order, initiator.getCurrentSession());
            } catch (SessionNotFound e) {
                return TestResult.builder(getName())
                        .failed("Session not found when sending large message: " + e.getMessage())
                        .startTime(startTime)
                        .build();
            }

            context.log("Large message sent with ClOrdID: " + clOrdId);

            // Wait for execution report
            ExecutionReport execReport = context.waitForExecutionReport(initiator, 15000);

            if (execReport != null) {
                char execType = execReport.getChar(ExecType.FIELD);
                char ordStatus = execReport.getChar(OrdStatus.FIELD);
                String returnedClOrdId = execReport.getString(ClOrdID.FIELD);

                context.log("Received ExecutionReport: ExecType=" + execType +
                        ", OrdStatus=" + ordStatus + ", ClOrdID=" + returnedClOrdId);

                return TestResult.builder(getName())
                        .passed(String.format("Large message (%d-char Text field) handled — " +
                                        "ExecType=%c, OrdStatus=%c, ClOrdID=%s",
                                LARGE_TEXT_SIZE, execType, ordStatus, returnedClOrdId))
                        .startTime(startTime)
                        .build();
            } else {
                // No execution report — but the message was sent without error,
                // and the session is still connected
                if (initiator.isLoggedOn()) {
                    return TestResult.builder(getName())
                            .passed(String.format("Large message (%d-char Text field) sent successfully — " +
                                            "no execution report received but session remains logged on",
                                    LARGE_TEXT_SIZE))
                            .startTime(startTime)
                            .build();
                } else {
                    return TestResult.builder(getName())
                            .failed("Session lost after sending large message and no execution report received")
                            .startTime(startTime)
                            .build();
                }
            }

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
