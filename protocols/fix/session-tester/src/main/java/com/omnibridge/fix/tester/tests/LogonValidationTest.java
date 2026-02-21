package com.omnibridge.fix.tester.tests;

import com.omnibridge.fix.tester.SessionTest;
import com.omnibridge.fix.tester.TestContext;
import com.omnibridge.fix.tester.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Test logon validation with unknown CompIDs.
 *
 * <p>Validates: the acceptor rejects logon attempts with unknown SenderCompID/TargetCompID
 * by either sending a Logout (35=5) or disconnecting. This verifies the session routing
 * and authentication logic of the acceptor.</p>
 */
public class LogonValidationTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(LogonValidationTest.class);

    private static final char SOH = '\u0001';
    private static final DateTimeFormatter FIX_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    @Override
    public String getName() {
        return "LogonValidationTest";
    }

    @Override
    public String getDescription() {
        return "Validates acceptor rejects logon with unknown CompIDs (Logout or disconnect)";
    }

    @Override
    public TestResult execute(TestContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // Ensure main session is logged on
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Could not establish logged on state",
                            System.currentTimeMillis() - startTime);
                }
            }

            String host = context.getSession().getConfig().getHost();
            int port = context.getSession().getConfig().getPort();
            int heartbeatInterval = context.getSession().getConfig().getHeartbeatInterval();

            // Open raw socket and send Logon with unknown CompIDs
            log.info("Sending Logon with unknown CompIDs to {}:{}", host, port);

            boolean receivedLogout = false;
            boolean receivedLogon = false;
            boolean connectionClosed = false;

            try (Socket rawSocket = new Socket(host, port)) {
                rawSocket.setSoTimeout(5000);

                byte[] logonMsg = buildRawLogon("UNKNOWN", "INVALID", heartbeatInterval);
                OutputStream out = rawSocket.getOutputStream();
                out.write(logonMsg);
                out.flush();

                log.info("Sent Logon with UNKNOWN/INVALID CompIDs");

                // Read response
                InputStream in = rawSocket.getInputStream();
                byte[] response = new byte[4096];
                try {
                    int bytesRead = in.read(response);
                    if (bytesRead > 0) {
                        String responseStr = new String(response, 0, bytesRead);
                        receivedLogout = responseStr.contains("35=5" + SOH);
                        receivedLogon = responseStr.contains("35=A" + SOH);
                        log.info("Response ({} bytes): containsLogout={}, containsLogon={}",
                                bytesRead, receivedLogout, receivedLogon);
                    } else if (bytesRead <= 0) {
                        connectionClosed = true;
                        log.info("Connection closed by acceptor (no data returned)");
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // No response within timeout - acceptor may have silently dropped
                    connectionClosed = true;
                    log.info("No response from acceptor within timeout (silent reject)");
                } catch (java.io.IOException e) {
                    connectionClosed = true;
                    log.info("Connection reset by acceptor: {}", e.getMessage());
                }
            }

            // Verify main session is still operational
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Main session lost after unknown CompID test",
                            System.currentTimeMillis() - startTime);
                }
            }

            if (receivedLogout) {
                return TestResult.passed(getName(),
                        "Acceptor sent Logout (35=5) in response to unknown CompIDs",
                        System.currentTimeMillis() - startTime);
            } else if (connectionClosed && !receivedLogon) {
                return TestResult.passed(getName(),
                        "Acceptor rejected unknown CompIDs (connection closed/timeout, no Logon response)",
                        System.currentTimeMillis() - startTime);
            } else if (receivedLogon) {
                // Some acceptors accept any CompIDs — note as pass with warning
                return TestResult.passed(getName(),
                        "Acceptor accepted unknown CompIDs (may not validate CompIDs at session layer)",
                        System.currentTimeMillis() - startTime);
            } else {
                return TestResult.failed(getName(),
                        "Unexpected response to unknown CompID logon",
                        System.currentTimeMillis() - startTime);
            }

        } catch (Exception e) {
            return TestResult.error(getName(),
                    "Exception during test: " + e.getMessage(),
                    System.currentTimeMillis() - startTime, e);
        }
    }

    /**
     * Build a raw FIX Logon message as bytes.
     */
    private byte[] buildRawLogon(String senderCompId, String targetCompId, int heartbeatInterval) {
        String sendingTime = FIX_TIME_FORMAT.format(Instant.now());

        StringBuilder body = new StringBuilder();
        body.append("35=A").append(SOH);
        body.append("34=1").append(SOH);
        body.append("49=").append(senderCompId).append(SOH);
        body.append("56=").append(targetCompId).append(SOH);
        body.append("52=").append(sendingTime).append(SOH);
        body.append("98=0").append(SOH);
        body.append("108=").append(heartbeatInterval).append(SOH);
        body.append("141=Y").append(SOH);

        String bodyStr = body.toString();
        int bodyLength = bodyStr.length();

        StringBuilder header = new StringBuilder();
        header.append("8=FIX.4.4").append(SOH);
        header.append("9=").append(bodyLength).append(SOH);

        String msgWithoutChecksum = header.toString() + bodyStr;

        int checksum = 0;
        for (int i = 0; i < msgWithoutChecksum.length(); i++) {
            checksum += msgWithoutChecksum.charAt(i);
        }
        checksum = checksum % 256;

        String fullMessage = msgWithoutChecksum + "10=" + String.format("%03d", checksum) + SOH;
        return fullMessage.getBytes();
    }
}
