package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.message.FixReader;
import com.omnibridge.fix.message.IncomingFixMessage;
import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Test checksum validation in FixReader.
 *
 * <p>Validates: FixReader correctly rejects messages with invalid checksums
 * (error code -4). Constructs a valid FIX message, corrupts the checksum field,
 * and verifies the reader returns the expected error code.</p>
 */
public class ChecksumValidationTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(ChecksumValidationTest.class);

    private static final char SOH = '\u0001';

    @Override
    public String getName() {
        return "ChecksumValidationTest";
    }

    @Override
    public String getDescription() {
        return "Validates FixReader rejects messages with invalid checksums (error code -4)";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Ensure session is logged on (for subsequent tests)
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Could not establish logged on state",
                            System.currentTimeMillis() - startTime);
                }
            }

            // Build a valid FIX message
            String validMsg = buildValidFixMessage();
            log.info("Valid FIX message: length={}", validMsg.length());

            // First, verify the valid message parses successfully
            FixReader reader = new FixReader();
            IncomingFixMessage message = new IncomingFixMessage();
            byte[] validBytes = validMsg.getBytes();
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(validBytes.length));
            buffer.putBytes(0, validBytes);

            int validResult = reader.read(buffer, 0, validBytes.length, message);
            if (validResult <= 0) {
                return TestResult.failed(getName(),
                        String.format("Valid message should parse successfully but got: %d", validResult),
                        System.currentTimeMillis() - startTime);
            }
            log.info("Valid message parsed successfully: result={}", validResult);

            // Now corrupt the checksum — change the last 3 digits before trailing SOH
            String corruptedMsg = corruptChecksum(validMsg);
            log.info("Corrupted checksum message built");

            // Feed corrupted message to a fresh reader
            FixReader corruptReader = new FixReader();
            IncomingFixMessage corruptMessage = new IncomingFixMessage();
            byte[] corruptBytes = corruptedMsg.getBytes();
            UnsafeBuffer corruptBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(corruptBytes.length));
            corruptBuffer.putBytes(0, corruptBytes);

            int corruptResult = corruptReader.read(corruptBuffer, 0, corruptBytes.length, corruptMessage);

            log.info("Corrupt message parse result: {}", corruptResult);

            if (corruptResult == -4) {
                return TestResult.passed(getName(),
                        "FixReader correctly rejected message with invalid checksum (error code -4)",
                        System.currentTimeMillis() - startTime);
            } else if (corruptResult < 0) {
                return TestResult.passed(getName(),
                        String.format("FixReader rejected corrupted message with error code %d (expected -4)", corruptResult),
                        System.currentTimeMillis() - startTime);
            } else {
                return TestResult.failed(getName(),
                        String.format("FixReader did not reject corrupted checksum: result=%d", corruptResult),
                        System.currentTimeMillis() - startTime);
            }

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }

    /**
     * Build a valid FIX Heartbeat message as a string.
     */
    private String buildValidFixMessage() {
        // Build body
        StringBuilder body = new StringBuilder();
        body.append("35=0").append(SOH);       // MsgType = Heartbeat
        body.append("34=1").append(SOH);       // MsgSeqNum
        body.append("49=SENDER").append(SOH);  // SenderCompID
        body.append("56=TARGET").append(SOH);  // TargetCompID
        body.append("52=20240101-00:00:00.000").append(SOH); // SendingTime

        String bodyStr = body.toString();
        int bodyLength = bodyStr.length();

        // Build header
        StringBuilder header = new StringBuilder();
        header.append("8=FIX.4.4").append(SOH);
        header.append("9=").append(bodyLength).append(SOH);

        // Full message without checksum
        String msgWithoutChecksum = header.toString() + bodyStr;

        // Calculate checksum
        int checksum = 0;
        for (int i = 0; i < msgWithoutChecksum.length(); i++) {
            checksum += msgWithoutChecksum.charAt(i);
        }
        checksum = checksum % 256;

        return msgWithoutChecksum + "10=" + String.format("%03d", checksum) + SOH;
    }

    /**
     * Corrupt the checksum of a FIX message by replacing the checksum value.
     */
    private String corruptChecksum(String validMsg) {
        // Find "10=" in the message
        int checksumTagPos = validMsg.lastIndexOf("10=");
        if (checksumTagPos < 0) {
            throw new IllegalStateException("Could not find checksum tag in message");
        }

        // Extract current checksum
        String beforeChecksum = validMsg.substring(0, checksumTagPos + 3);
        // Replace with a different checksum value
        String currentChecksum = validMsg.substring(checksumTagPos + 3, checksumTagPos + 6);
        int currentValue = Integer.parseInt(currentChecksum);
        int corruptValue = (currentValue + 1) % 256;

        return beforeChecksum + String.format("%03d", corruptValue) + SOH;
    }
}
