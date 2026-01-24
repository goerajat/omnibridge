package com.omnibridge.build;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/**
 * Integration test runner that executes test scripts (.bat or .sh) based on the OS.
 *
 * <p>This class is used by Maven to run integration tests during the build lifecycle.
 * It automatically detects the OS and runs the appropriate script version.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -cp build-tools.jar com.fixengine.build.IntegrationTestRunner [test-type] [options...]
 *
 * Test types:
 *   reference  - Run reference tests (QuickFIX/J interoperability)
 *   session    - Run session tests (FIX engine tests)
 *   latency    - Run latency tests
 *   all        - Run all tests
 *
 * Latency test options:
 *   --warmup N     - Number of warmup orders (default: 1000)
 *   --orders N     - Number of test orders (default: 100)
 *   --rate N       - Orders per second (default: 100)
 * </pre>
 */
public class IntegrationTestRunner {

    private static final String SEPARATOR = "=".repeat(70);
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    public static void main(String[] args) {
        String testType = args.length > 0 ? args[0].toLowerCase() : "all";

        // Parse latency options
        int warmup = 1000;
        int orders = 100;
        int rate = 100;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--orders" -> orders = Integer.parseInt(args[++i]);
                case "--rate" -> rate = Integer.parseInt(args[++i]);
            }
        }

        // Find project root directory
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) {
            System.err.println("ERROR: Could not find project root (looking for pom.xml)");
            System.exit(1);
        }

        System.out.println(SEPARATOR);
        System.out.println("Integration Test Runner");
        System.out.println(SEPARATOR);
        System.out.println("OS: " + System.getProperty("os.name"));
        System.out.println("Project Root: " + projectRoot);
        System.out.println("Test Type: " + testType);
        System.out.println(SEPARATOR);
        System.out.println();

        int exitCode = 0;

        try {
            switch (testType) {
                case "reference" -> exitCode = runReferenceTests(projectRoot);
                case "session" -> exitCode = runSessionTests(projectRoot);
                case "latency" -> exitCode = runLatencyTests(projectRoot, warmup, orders, rate);
                case "all" -> {
                    exitCode = runReferenceTests(projectRoot);
                    if (exitCode == 0) {
                        exitCode = runSessionTests(projectRoot);
                    }
                    if (exitCode == 0) {
                        exitCode = runLatencyTests(projectRoot, warmup, orders, rate);
                    }
                }
                default -> {
                    System.err.println("Unknown test type: " + testType);
                    System.err.println("Valid types: reference, session, latency, all");
                    exitCode = 1;
                }
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            exitCode = 1;
        }

        System.out.println();
        System.out.println(SEPARATOR);
        if (exitCode == 0) {
            System.out.println("INTEGRATION TESTS PASSED");
        } else {
            System.out.println("INTEGRATION TESTS FAILED (exit code: " + exitCode + ")");
        }
        System.out.println(SEPARATOR);

        System.exit(exitCode);
    }

    private static Path findProjectRoot() {
        Path current = Paths.get("").toAbsolutePath();

        // Look for pom.xml with connectivity artifact
        while (current != null) {
            Path pom = current.resolve("pom.xml");
            if (Files.exists(pom)) {
                try {
                    String content = Files.readString(pom);
                    if (content.contains("<artifactId>connectivity</artifactId>")) {
                        return current;
                    }
                } catch (IOException ignored) {}
            }
            // Also check for build-tools as sibling
            if (Files.exists(current.resolve("build-tools/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }

        return null;
    }

    private static int runReferenceTests(Path projectRoot) throws Exception {
        System.out.println(">>> Running Reference Tests (QuickFIX/J interoperability)");
        System.out.println();
        return runScript(projectRoot, "run-reference-test", "all");
    }

    private static int runSessionTests(Path projectRoot) throws Exception {
        System.out.println(">>> Running Session Tests (FIX Engine)");
        System.out.println();
        return runScript(projectRoot, "run-session-test", "all", "text");
    }

    private static int runLatencyTests(Path projectRoot, int warmup, int orders, int rate) throws Exception {
        System.out.println(">>> Running Latency Tests");
        System.out.println("    Warmup: " + warmup + " orders");
        System.out.println("    Test: " + orders + " orders");
        System.out.println("    Rate: " + rate + " orders/sec");
        System.out.println();
        return runScript(projectRoot, "run-latency-test",
                String.valueOf(warmup), String.valueOf(orders), String.valueOf(rate));
    }

    private static int runScript(Path projectRoot, String scriptName, String... args) throws Exception {
        String extension = IS_WINDOWS ? ".bat" : ".sh";
        Path scriptPath = projectRoot.resolve(scriptName + extension);

        if (!Files.exists(scriptPath)) {
            System.err.println("ERROR: Script not found: " + scriptPath);
            return 1;
        }

        ProcessBuilder pb;
        if (IS_WINDOWS) {
            String[] command = new String[3 + args.length];
            command[0] = "cmd.exe";
            command[1] = "/c";
            command[2] = scriptPath.toString();
            System.arraycopy(args, 0, command, 3, args.length);
            pb = new ProcessBuilder(command);
        } else {
            String[] command = new String[1 + args.length];
            command[0] = scriptPath.toString();
            System.arraycopy(args, 0, command, 1, args.length);
            pb = new ProcessBuilder(command);
        }

        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Stream output in real-time
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        outputThread.start();

        // Wait for process with timeout (5 minutes)
        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        outputThread.join(1000);

        if (!completed) {
            System.err.println("ERROR: Test script timed out after 5 minutes");
            process.destroyForcibly();
            return 1;
        }

        return process.exitValue();
    }
}
