package com.omnibridge.fix.reference;

import com.omnibridge.fix.reference.acceptor.AcceptorConfig;
import com.omnibridge.fix.reference.acceptor.ReferenceAcceptor;
import com.omnibridge.fix.reference.initiator.InitiatorConfig;
import com.omnibridge.fix.reference.initiator.ReferenceInitiator;
import com.omnibridge.fix.reference.test.*;
import com.omnibridge.fix.reference.test.tests.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Reference FIX Tester CLI Application.
 *
 * <p>This tool provides a reference FIX implementation using QuickFIX/J for
 * interoperability testing with the custom FIX engine.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run as acceptor (exchange simulator)
 * java -jar fix-reference-tester.jar acceptor --port 9880
 *
 * # Run as initiator (client simulator) and send orders
 * java -jar fix-reference-tester.jar initiator --host localhost --port 9880
 *
 * # Run test suite against a FIX acceptor
 * java -jar fix-reference-tester.jar test --host localhost --port 9880 --tests all
 *
 * # List available tests
 * java -jar fix-reference-tester.jar test --list-tests
 * </pre>
 */
@Command(name = "fix-reference-tester",
        mixinStandardHelpOptions = true,
        version = "FIX Reference Tester 1.0",
        description = "Reference FIX implementation tester using QuickFIX/J",
        subcommands = {
                ReferenceTester.AcceptorCommand.class,
                ReferenceTester.InitiatorCommand.class,
                ReferenceTester.TestCommand.class,
                CommandLine.HelpCommand.class
        })
public class ReferenceTester implements Callable<Integer> {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ReferenceTester())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    // ==================== Acceptor Command ====================

    @Command(name = "acceptor",
            description = "Run reference FIX acceptor (exchange simulator)",
            mixinStandardHelpOptions = true)
    static class AcceptorCommand implements Callable<Integer> {

        @Option(names = {"-p", "--port"}, description = "Port to listen on (default: 9880)")
        int port = 9880;

        @Option(names = {"--sender"}, description = "SenderCompID (default: EXCHANGE)")
        String senderCompId = "EXCHANGE";

        @Option(names = {"--target"}, description = "TargetCompID (default: CLIENT)")
        String targetCompId = "CLIENT";

        @Option(names = {"--begin-string"}, description = "FIX version: FIX.4.2, FIX.4.4, FIX.5.0, FIX.5.0SP1, FIX.5.0SP2 (default: FIX.4.4)")
        String beginString = "FIX.4.4";

        @Option(names = {"--default-appl-ver-id"}, description = "DefaultApplVerID for FIX 5.0+ (9=FIX50, 10=FIX50SP1, 11=FIX50SP2)")
        String defaultApplVerID = null;

        @Option(names = {"--heartbeat"}, description = "Heartbeat interval in seconds (default: 30)")
        int heartbeatInterval = 30;

        @Option(names = {"--fill-rate"}, description = "Order fill rate 0.0-1.0 (default: 1.0)")
        double fillRate = 1.0;

        @Option(names = {"--fill-delay"}, description = "Delay before sending fill in ms (default: 0)")
        int fillDelayMs = 0;

        @Option(names = {"--no-auto-ack"}, description = "Disable automatic execution reports")
        boolean noAutoAck = false;

        @Option(names = {"--reset-on-logon"}, description = "Reset sequence numbers on logon (default: true)")
        boolean resetOnLogon = true;

        @Option(names = {"--daemon"}, description = "Run in daemon mode (no interactive input required)")
        boolean daemon = false;

        @Override
        public Integer call() throws Exception {
            // Parse FIX version and convert FIX.5.0* to FIXT.1.1 + ApplVerID
            String effectiveBeginString = beginString;
            String effectiveApplVerID = defaultApplVerID;
            if (beginString.startsWith("FIX.5.0")) {
                effectiveBeginString = "FIXT.1.1";
                if (effectiveApplVerID == null) {
                    if (beginString.contains("SP2")) {
                        effectiveApplVerID = "11";  // FIX 5.0 SP2
                    } else if (beginString.contains("SP1")) {
                        effectiveApplVerID = "10";  // FIX 5.0 SP1
                    } else {
                        effectiveApplVerID = "9";   // FIX 5.0
                    }
                }
            }

            AcceptorConfig config = AcceptorConfig.builder()
                    .port(port)
                    .senderCompId(senderCompId)
                    .targetCompId(targetCompId)
                    .beginString(effectiveBeginString)
                    .heartbeatInterval(heartbeatInterval)
                    .fillRate(fillRate)
                    .fillDelayMs(fillDelayMs)
                    .autoAck(!noAutoAck)
                    .resetOnLogon(resetOnLogon)
                    .defaultApplVerID(effectiveApplVerID)
                    .build();

            ReferenceAcceptor acceptor = new ReferenceAcceptor(config);
            acceptor.start();

            System.out.println("========================================");
            System.out.println("Reference FIX Acceptor (QuickFIX/J)");
            System.out.println("========================================");
            System.out.println("Port: " + port);
            System.out.println("SenderCompID: " + senderCompId);
            System.out.println("TargetCompID: " + targetCompId);
            System.out.println("FIX Version: " + beginString);
            if (config.usesFixt()) {
                System.out.println("Transport: FIXT.1.1");
                System.out.println("DefaultApplVerID: " + effectiveApplVerID);
            }
            System.out.println("Fill Rate: " + (fillRate * 100) + "%");
            System.out.println("Auto-Ack: " + !noAutoAck);
            System.out.println("Daemon Mode: " + daemon);
            System.out.println("========================================");

            if (daemon) {
                System.out.println("Running in daemon mode. Use Ctrl+C to stop.");
                // Add shutdown hook for graceful shutdown
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutdown signal received, stopping acceptor...");
                    acceptor.stop();
                }));
                // Wait indefinitely
                Thread.currentThread().join();
            } else {
                System.out.println("Press Enter to stop...");
                System.in.read();
                acceptor.stop();
            }
            return 0;
        }
    }

    // ==================== Initiator Command ====================

    @Command(name = "initiator",
            description = "Run reference FIX initiator (client simulator)",
            mixinStandardHelpOptions = true)
    static class InitiatorCommand implements Callable<Integer> {

        @Option(names = {"-h", "--host"}, description = "Target host (default: localhost)")
        String host = "localhost";

        @Option(names = {"-p", "--port"}, description = "Target port (default: 9880)")
        int port = 9880;

        @Option(names = {"--sender"}, description = "SenderCompID (default: CLIENT)")
        String senderCompId = "CLIENT";

        @Option(names = {"--target"}, description = "TargetCompID (default: SERVER)")
        String targetCompId = "SERVER";

        @Option(names = {"--begin-string"}, description = "FIX version: FIX.4.2, FIX.4.4, FIX.5.0, FIX.5.0SP1, FIX.5.0SP2 (default: FIX.4.4)")
        String beginString = "FIX.4.4";

        @Option(names = {"--default-appl-ver-id"}, description = "DefaultApplVerID for FIX 5.0+ (9=FIX50, 10=FIX50SP1, 11=FIX50SP2)")
        String defaultApplVerID = null;

        @Option(names = {"--heartbeat"}, description = "Heartbeat interval in seconds (default: 30)")
        int heartbeatInterval = 30;

        @Option(names = {"--reset-on-logon"}, description = "Reset sequence numbers on logon (default: true)")
        boolean resetOnLogon = true;

        @Option(names = {"--interactive"}, description = "Interactive mode for sending orders")
        boolean interactive = false;

        @Option(names = {"--send-order"}, description = "Send a test order after logon")
        boolean sendOrder = false;

        @Option(names = {"--symbol"}, description = "Symbol for test order (default: AAPL)")
        String symbol = "AAPL";

        @Option(names = {"--side"}, description = "Side for test order: buy/sell (default: buy)")
        String side = "buy";

        @Option(names = {"--qty"}, description = "Quantity for test order (default: 100)")
        double quantity = 100;

        @Option(names = {"--price"}, description = "Price for test order (default: 150.00)")
        double price = 150.00;

        @Override
        public Integer call() throws Exception {
            // Parse FIX version and convert FIX.5.0* to FIXT.1.1 + ApplVerID
            String effectiveBeginString = beginString;
            String effectiveApplVerID = defaultApplVerID;
            if (beginString.startsWith("FIX.5.0")) {
                effectiveBeginString = "FIXT.1.1";
                if (effectiveApplVerID == null) {
                    if (beginString.contains("SP2")) {
                        effectiveApplVerID = "11";  // FIX 5.0 SP2
                    } else if (beginString.contains("SP1")) {
                        effectiveApplVerID = "10";  // FIX 5.0 SP1
                    } else {
                        effectiveApplVerID = "9";   // FIX 5.0
                    }
                }
            }

            InitiatorConfig config = InitiatorConfig.builder()
                    .host(host)
                    .port(port)
                    .senderCompId(senderCompId)
                    .targetCompId(targetCompId)
                    .beginString(effectiveBeginString)
                    .heartbeatInterval(heartbeatInterval)
                    .resetOnLogon(resetOnLogon)
                    .defaultApplVerID(effectiveApplVerID)
                    .build();

            ReferenceInitiator initiator = new ReferenceInitiator(config);

            System.out.println("========================================");
            System.out.println("Reference FIX Initiator (QuickFIX/J)");
            System.out.println("========================================");
            System.out.println("Target: " + host + ":" + port);
            System.out.println("SenderCompID: " + senderCompId);
            System.out.println("TargetCompID: " + targetCompId);
            System.out.println("FIX Version: " + beginString);
            if (config.usesFixt()) {
                System.out.println("Transport: FIXT.1.1");
                System.out.println("DefaultApplVerID: " + effectiveApplVerID);
            }
            System.out.println("========================================");

            initiator.start();

            if (!initiator.waitForLogon(30000)) {
                System.err.println("Failed to logon within timeout");
                initiator.stop();
                return 1;
            }

            System.out.println("Logged on successfully!");

            if (sendOrder) {
                char sideChar = "sell".equalsIgnoreCase(side) ?
                        quickfix.field.Side.SELL : quickfix.field.Side.BUY;

                System.out.println("Sending test order: " + symbol + " " + side.toUpperCase() +
                        " " + quantity + " @ " + price);

                String clOrdId = initiator.sendNewOrderSingle(symbol, sideChar, quantity,
                        quickfix.field.OrdType.LIMIT, price);
                System.out.println("Order sent: ClOrdID=" + clOrdId);

                // Wait for execution report
                var report = initiator.pollExecutionReport(10000);
                if (report != null) {
                    System.out.println("Received ExecutionReport: ExecType=" +
                            report.getChar(quickfix.field.ExecType.FIELD));
                } else {
                    System.out.println("No execution report received within timeout");
                }
            }

            if (interactive) {
                runInteractiveMode(initiator);
            } else if (!sendOrder) {
                System.out.println("Press Enter to disconnect...");
                System.in.read();
            }

            initiator.stop();
            return 0;
        }

        private void runInteractiveMode(ReferenceInitiator initiator) throws Exception {
            java.util.Scanner scanner = new java.util.Scanner(System.in);
            boolean running = true;

            System.out.println("\nInteractive Mode Commands:");
            System.out.println("  buy <symbol> <qty> <price>   - Send buy order");
            System.out.println("  sell <symbol> <qty> <price>  - Send sell order");
            System.out.println("  test                         - Send test request");
            System.out.println("  logout                       - Logout and exit");
            System.out.println("  quit                         - Exit");
            System.out.println();

            while (running && initiator.isLoggedOn()) {
                System.out.print("> ");
                String line = scanner.nextLine().trim();
                String[] parts = line.split("\\s+");

                if (parts.length == 0 || parts[0].isEmpty()) continue;

                String cmd = parts[0].toLowerCase();

                try {
                    switch (cmd) {
                        case "buy" -> {
                            if (parts.length >= 4) {
                                String sym = parts[1];
                                double qty = Double.parseDouble(parts[2]);
                                double prc = Double.parseDouble(parts[3]);
                                String clOrdId = initiator.sendNewOrderSingle(sym,
                                        quickfix.field.Side.BUY, qty, quickfix.field.OrdType.LIMIT, prc);
                                System.out.println("Buy order sent: " + clOrdId);
                            } else {
                                System.out.println("Usage: buy <symbol> <qty> <price>");
                            }
                        }
                        case "sell" -> {
                            if (parts.length >= 4) {
                                String sym = parts[1];
                                double qty = Double.parseDouble(parts[2]);
                                double prc = Double.parseDouble(parts[3]);
                                String clOrdId = initiator.sendNewOrderSingle(sym,
                                        quickfix.field.Side.SELL, qty, quickfix.field.OrdType.LIMIT, prc);
                                System.out.println("Sell order sent: " + clOrdId);
                            } else {
                                System.out.println("Usage: sell <symbol> <qty> <price>");
                            }
                        }
                        case "test" -> {
                            String testReqId = initiator.sendTestRequest();
                            System.out.println("TestRequest sent: " + testReqId);
                        }
                        case "logout" -> {
                            initiator.logout();
                            running = false;
                        }
                        case "quit", "exit" -> running = false;
                        default -> System.out.println("Unknown command: " + cmd);
                    }
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                }
            }
        }
    }

    // ==================== Test Command ====================

    @Command(name = "test",
            description = "Run test suite against a FIX acceptor",
            mixinStandardHelpOptions = true)
    static class TestCommand implements Callable<Integer> {

        @Option(names = {"-h", "--host"}, description = "Target host (default: localhost)")
        String host = "localhost";

        @Option(names = {"-p", "--port"}, description = "Target port (default: 9880)")
        int port = 9880;

        @Option(names = {"--sender"}, description = "SenderCompID (default: CLIENT)")
        String senderCompId = "CLIENT";

        @Option(names = {"--target"}, description = "TargetCompID (default: SERVER)")
        String targetCompId = "SERVER";

        @Option(names = {"--begin-string"}, description = "FIX version: FIX.4.2, FIX.4.4, FIX.5.0, FIX.5.0SP1, FIX.5.0SP2 (default: FIX.4.4)")
        String beginString = "FIX.4.4";

        @Option(names = {"--default-appl-ver-id"}, description = "DefaultApplVerID for FIX 5.0+ (9=FIX50, 10=FIX50SP1, 11=FIX50SP2)")
        String defaultApplVerID = null;

        @Option(names = {"-t", "--tests"}, description = "Comma-separated test names or 'all' (default: all)")
        String tests = "all";

        @Option(names = {"-l", "--list-tests"}, description = "List available tests and exit")
        boolean listTests = false;

        @Option(names = {"--timeout"}, description = "Default timeout in seconds (default: 30)")
        int timeout = 30;

        private static final List<ReferenceTest> ALL_TESTS = Arrays.asList(
                new HeartbeatTest(),
                new SequenceNumberTest(),
                new NewOrderTest(),
                new MarketOrderTest(),
                new SellOrderTest(),
                new OrderCancelTest(),
                new OrderModifyTest(),
                new OrderRejectTest(),
                new OrderStatusRequestTest(),
                new CancelRejectTest(),
                new TimeInForceTest(),
                new PartialFillTest(),
                new FillDelayTest(),
                new MultipleOrdersTest(),
                new Fix42LogonTest(),   // FIX 4.2 specific test
                new Fix50LogonTest(),   // FIX 5.0 specific test
                new MaxMessageSizeTest(),
                new LogonLogoutTest()   // Run last as it logs out
        );

        @Override
        public Integer call() throws Exception {
            if (listTests) {
                listAvailableTests();
                return 0;
            }

            // Parse FIX version and convert FIX.5.0* to FIXT.1.1 + ApplVerID
            String effectiveBeginString = beginString;
            String effectiveApplVerID = defaultApplVerID;
            if (beginString.startsWith("FIX.5.0")) {
                effectiveBeginString = "FIXT.1.1";
                if (effectiveApplVerID == null) {
                    if (beginString.contains("SP2")) {
                        effectiveApplVerID = "11";  // FIX 5.0 SP2
                    } else if (beginString.contains("SP1")) {
                        effectiveApplVerID = "10";  // FIX 5.0 SP1
                    } else {
                        effectiveApplVerID = "9";   // FIX 5.0
                    }
                }
            }

            InitiatorConfig config = InitiatorConfig.builder()
                    .host(host)
                    .port(port)
                    .senderCompId(senderCompId)
                    .targetCompId(targetCompId)
                    .beginString(effectiveBeginString)
                    .defaultApplVerID(effectiveApplVerID)
                    .build();

            TestContext context = new TestContext(timeout * 1000L);
            TestSuite suite = new TestSuite(config, context);

            // Add selected tests
            if ("all".equalsIgnoreCase(tests)) {
                suite.addTests(ALL_TESTS);
            } else {
                String[] testNames = tests.split(",");
                for (String testName : testNames) {
                    ReferenceTest test = findTest(testName.trim());
                    if (test != null) {
                        suite.addTest(test);
                    } else {
                        System.err.println("Unknown test: " + testName);
                    }
                }
            }

            if (suite.getTests().isEmpty()) {
                System.err.println("No tests to run");
                return 1;
            }

            System.out.println("========================================");
            System.out.println("FIX Reference Tester - Test Suite");
            System.out.println("========================================");
            System.out.println("Target: " + host + ":" + port);
            System.out.println("FIX Version: " + beginString);
            if (config.usesFixt()) {
                System.out.println("Transport: FIXT.1.1");
                System.out.println("DefaultApplVerID: " + effectiveApplVerID);
            }
            System.out.println("Tests: " + suite.getTests().size());
            System.out.println("========================================");
            System.out.println();

            suite.runAll();

            return suite.allPassed() ? 0 : 1;
        }

        private void listAvailableTests() {
            System.out.println("Available Tests:");
            System.out.println("================");
            for (ReferenceTest test : ALL_TESTS) {
                System.out.printf("  %-25s - %s%n", test.getName(), test.getDescription());
            }
        }

        private ReferenceTest findTest(String name) {
            for (ReferenceTest test : ALL_TESTS) {
                if (test.getName().equalsIgnoreCase(name)) {
                    return test;
                }
            }
            return null;
        }
    }
}
