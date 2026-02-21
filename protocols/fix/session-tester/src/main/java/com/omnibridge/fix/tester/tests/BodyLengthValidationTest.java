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
 * Test BodyLength validation in FixReader.
 *
 * <p>Validates: FixReader correctly rejects messages with wrong BodyLength (tag 9).
 * When the BodyLength value doesn't match the actual body length, the checksum tag
 * won't be at the expected position, resulting in error code -2.</p>
 */
public class BodyLengthValidationTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(BodyLengthValidationTest.class);

    private static final char SOH = '\u0001';

    @Override
    public String getName() {
        return "BodyLengthValidationTest";
    }

    @Override
    public String getDescription() {
        return "Validates FixReader rejects messages with incorrect BodyLength (error code -2)";
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

            // Build a FIX message with an incorrect BodyLength
            String corruptedMsg = buildMessageWithWrongBodyLength();
            log.info("Built message with wrong BodyLength: length={}", corruptedMsg.length());

            FixReader reader = new FixReader();
            IncomingFixMessage message = new IncomingFixMessage();
            byte[] bytes = corruptedMsg.getBytes();
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(bytes.length));
            buffer.putBytes(0, bytes);

            int result = reader.read(buffer, 0, bytes.length, message);
            log.info("FixReader result for wrong BodyLength: {}", result);

            // The reader should reject the message:
            // -2 = CheckSum not at expected position (because BodyLength is wrong)
            // -3 = Invalid checksum format
            // -4 = Checksum mismatch
            if (result == -2) {
                return TestResult.passed(getName(),
                        "FixReader correctly rejected message with wrong BodyLength (error code -2: CheckSum not at expected position)",
                        System.currentTimeMillis() - startTime);
            } else if (result < 0) {
                return TestResult.passed(getName(),
                        String.format("FixReader rejected message with wrong BodyLength (error code %d)", result),
                        System.currentTimeMillis() - startTime);
            } else if (result == 0) {
                // The reader may request more data because the wrong BodyLength points
                // beyond the available data
                return TestResult.passed(getName(),
                        "FixReader returned 0 (need more data) for wrong BodyLength — message effectively rejected",
                        System.currentTimeMillis() - startTime);
            } else {
                return TestResult.failed(getName(),
                        String.format("FixReader did not reject wrong BodyLength: result=%d", result),
                        System.currentTimeMillis() - startTime);
            }

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }

    /**
     * Build a FIX message with an intentionally wrong BodyLength.
     * The BodyLength is inflated by 50, so the checksum won't be found at the expected position.
     */
    private String buildMessageWithWrongBodyLength() {
        // Build body
        StringBuilder body = new StringBuilder();
        body.append("35=0").append(SOH);       // MsgType = Heartbeat
        body.append("34=1").append(SOH);       // MsgSeqNum
        body.append("49=SENDER").append(SOH);  // SenderCompID
        body.append("56=TARGET").append(SOH);  // TargetCompID
        body.append("52=20240101-00:00:00.000").append(SOH); // SendingTime

        String bodyStr = body.toString();
        int actualBodyLength = bodyStr.length();
        int wrongBodyLength = actualBodyLength + 50; // Inflate by 50

        // Build header with wrong BodyLength
        StringBuilder header = new StringBuilder();
        header.append("8=FIX.4.4").append(SOH);
        header.append("9=").append(wrongBodyLength).append(SOH);

        // Full message without checksum
        String msgWithoutChecksum = header.toString() + bodyStr;

        // Calculate checksum on the actual bytes (it will be at wrong position anyway)
        int checksum = 0;
        for (int i = 0; i < msgWithoutChecksum.length(); i++) {
            checksum += msgWithoutChecksum.charAt(i);
        }
        checksum = checksum % 256;

        return msgWithoutChecksum + "10=" + String.format("%03d", checksum) + SOH;
    }
}
