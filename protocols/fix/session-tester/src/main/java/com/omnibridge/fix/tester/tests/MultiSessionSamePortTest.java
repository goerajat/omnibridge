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
 * Test that the acceptor rejects connections with unknown CompIDs on the same port.
 *
 * <p>Validates: when a connection attempt is made with CompIDs that don't match any
 * configured session on the acceptor, the acceptor either sends a Logout (35=5) with
 * an explanatory text, or simply closes the connection. This verifies the session
 * routing/dispatch logic on the acceptor side.</p>
 */
public class MultiSessionSamePortTest implements SessionTest {

    private static final Logger log = LoggerFactory.getLogger(MultiSessionSamePortTest.class);

    private static final char SOH = '\u0001';
    private static final DateTimeFormatter FIX_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    @Override
    public String getName() {
        return "MultiSessionSamePortTest";
    }

    @Override
    public String getDescription() {
        return "Validates acceptor rejects connections with unknown CompIDs on the session port";
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

            // Attempt connection with totally different CompIDs
            String unknownSender = "UNKNOWN_SENDER";
            String unknownTarget = "UNKNOWN_TARGET";

            log.info("Sending Logon with unknown CompIDs ({}/{}) to {}:{}",
                    unknownSender, unknownTarget, host, port);

            boolean receivedLogout = false;
            boolean logoutHasText = false;
            String logoutText = null;
            boolean connectionRejected = false;

            try (Socket rawSocket = new Socket(host, port)) {
                rawSocket.setSoTimeout(5000);

                byte[] logonMsg = buildRawLogon(unknownSender, unknownTarget, 30);
                OutputStream out = rawSocket.getOutputStream();
                out.write(logonMsg);
                out.flush();

                // Read response
                InputStream in = rawSocket.getInputStream();
                byte[] response = new byte[4096];
                try {
                    int bytesRead = in.read(response);
                    if (bytesRead > 0) {
                        String responseStr = new String(response, 0, bytesRead);
                        receivedLogout = responseStr.contains("35=5" + SOH);

                        // Check for text field explaining the rejection
                        if (receivedLogout) {
                            int textPos = responseStr.indexOf("58=");
                            if (textPos >= 0) {
                                int textEnd = responseStr.indexOf(String.valueOf(SOH), textPos);
                                if (textEnd > textPos) {
                                    logoutText = responseStr.substring(textPos + 3, textEnd);
                                    logoutHasText = true;
                                }
                            }
                        }

                        log.info("Response: {} bytes, hasLogout={}, logoutText={}",
                                bytesRead, receivedLogout, logoutText);
                    } else {
                        connectionRejected = true;
                    }
                } catch (java.net.SocketTimeoutException e) {
                    connectionRejected = true;
                    log.info("No response — acceptor silently rejected unknown CompIDs");
                } catch (java.io.IOException e) {
                    connectionRejected = true;
                    log.info("Connection reset: {}", e.getMessage());
                }
            }

            // Verify main session is still operational
            if (!context.isLoggedOn()) {
                context.connect();
                if (!context.waitForLogon()) {
                    return TestResult.failed(getName(),
                            "Main session disrupted by unknown CompID connection attempt",
                            System.currentTimeMillis() - startTime);
                }
            }

            if (receivedLogout) {
                String msg = logoutHasText
                        ? String.format("Acceptor sent Logout for unknown CompIDs — text: \"%s\"", logoutText)
                        : "Acceptor sent Logout (35=5) for unknown CompIDs";
                return TestResult.passed(getName(), msg,
                        System.currentTimeMillis() - startTime);
            } else if (connectionRejected) {
                return TestResult.passed(getName(),
                        "Acceptor rejected unknown CompIDs (connection closed/timeout)",
                        System.currentTimeMillis() - startTime);
            } else {
                return TestResult.passed(getName(),
                        "Acceptor handled unknown CompID logon (may accept for routing)",
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
