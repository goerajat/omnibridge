package com.omnibridge.fix.tester;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Generates test reports in various formats.
 */
public class ReportGenerator {

    /**
     * Report format options.
     */
    public enum Format {
        TEXT,
        JSON,
        HTML
    }

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Generate a report in the specified format.
     */
    public String generate(TestSuite suite, Format format) {
        switch (format) {
            case JSON:
                return generateJson(suite);
            case HTML:
                return generateHtml(suite);
            case TEXT:
            default:
                return generateText(suite);
        }
    }

    /**
     * Generate a text report.
     */
    public String generateText(TestSuite suite) {
        StringBuilder sb = new StringBuilder();

        sb.append("=".repeat(70)).append("\n");
        sb.append("FIX Session Test Report\n");
        sb.append("=".repeat(70)).append("\n\n");

        sb.append("Suite: ").append(suite.getSuiteName()).append("\n");
        sb.append("Started: ").append(suite.getStartTime()).append("\n");
        if (suite.getEndTime() != null) {
            sb.append("Completed: ").append(suite.getEndTime()).append("\n");
        }
        sb.append("\n");

        // Summary
        sb.append("-".repeat(70)).append("\n");
        sb.append("SUMMARY\n");
        sb.append("-".repeat(70)).append("\n");
        sb.append(String.format("Total Tests: %d%n", suite.getTotalTests()));
        sb.append(String.format("Passed:      %d%n", suite.getPassedCount()));
        sb.append(String.format("Failed:      %d%n", suite.getFailedCount()));
        sb.append(String.format("Errors:      %d%n", suite.getErrorCount()));
        sb.append(String.format("Skipped:     %d%n", suite.getSkippedCount()));
        sb.append(String.format("Duration:    %d ms%n", suite.getTotalDurationMs()));
        sb.append("\n");

        // Individual results
        sb.append("-".repeat(70)).append("\n");
        sb.append("TEST RESULTS\n");
        sb.append("-".repeat(70)).append("\n\n");

        for (TestResult result : suite.getResults()) {
            String statusIcon = getStatusIcon(result.getStatus());
            sb.append(String.format("[%s] %s%n", statusIcon, result.getTestName()));
            if (result.getDescription() != null && !result.getDescription().isEmpty()) {
                sb.append(String.format("    Validates: %s%n", result.getDescription()));
            }
            sb.append(String.format("    Status:   %s%n", result.getStatus()));
            sb.append(String.format("    Duration: %d ms%n", result.getDurationMs()));
            sb.append(String.format("    Message:  %s%n", result.getMessage()));

            if (result.getError() != null) {
                sb.append("    Error:    ").append(result.getError().getClass().getName())
                        .append(": ").append(result.getError().getMessage()).append("\n");
            }
            sb.append("\n");
        }

        // Final status
        sb.append("=".repeat(70)).append("\n");
        if (suite.isAllPassed()) {
            sb.append("RESULT: ALL TESTS PASSED\n");
        } else {
            sb.append("RESULT: SOME TESTS FAILED\n");
        }
        sb.append("=".repeat(70)).append("\n");

        return sb.toString();
    }

    /**
     * Generate a JSON report.
     */
    public String generateJson(TestSuite suite) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("suiteName", suite.getSuiteName());
            root.put("startTime", suite.getStartTime().toString());
            if (suite.getEndTime() != null) {
                root.put("endTime", suite.getEndTime().toString());
            }

            ObjectNode summary = root.putObject("summary");
            summary.put("totalTests", suite.getTotalTests());
            summary.put("passed", suite.getPassedCount());
            summary.put("failed", suite.getFailedCount());
            summary.put("errors", suite.getErrorCount());
            summary.put("skipped", suite.getSkippedCount());
            summary.put("durationMs", suite.getTotalDurationMs());
            summary.put("allPassed", suite.isAllPassed());

            ArrayNode results = root.putArray("results");
            for (TestResult result : suite.getResults()) {
                ObjectNode resultNode = results.addObject();
                resultNode.put("testName", result.getTestName());
                if (result.getDescription() != null) {
                    resultNode.put("description", result.getDescription());
                }
                resultNode.put("status", result.getStatus().name());
                resultNode.put("message", result.getMessage());
                resultNode.put("durationMs", result.getDurationMs());

                if (result.getError() != null) {
                    ObjectNode errorNode = resultNode.putObject("error");
                    errorNode.put("type", result.getError().getClass().getName());
                    errorNode.put("message", result.getError().getMessage());

                    StringWriter sw = new StringWriter();
                    result.getError().printStackTrace(new PrintWriter(sw));
                    errorNode.put("stackTrace", sw.toString());
                }
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"Failed to generate JSON report: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Generate an HTML report.
     */
    public String generateHtml(TestSuite suite) {
        StringBuilder sb = new StringBuilder();

        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("    <meta charset=\"UTF-8\">\n");
        sb.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("    <title>FIX Session Test Report</title>\n");
        sb.append("    <style>\n");
        sb.append("        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 20px; background: #f5f5f5; }\n");
        sb.append("        .container { max-width: 900px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
        sb.append("        h1 { color: #333; border-bottom: 2px solid #007bff; padding-bottom: 10px; }\n");
        sb.append("        .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 15px; margin: 20px 0; }\n");
        sb.append("        .summary-item { background: #f8f9fa; padding: 15px; border-radius: 6px; text-align: center; }\n");
        sb.append("        .summary-item .label { font-size: 12px; color: #666; text-transform: uppercase; }\n");
        sb.append("        .summary-item .value { font-size: 24px; font-weight: bold; color: #333; }\n");
        sb.append("        .passed { color: #28a745 !important; }\n");
        sb.append("        .failed { color: #dc3545 !important; }\n");
        sb.append("        .error { color: #fd7e14 !important; }\n");
        sb.append("        .skipped { color: #6c757d !important; }\n");
        sb.append("        table { width: 100%; border-collapse: collapse; margin: 20px 0; }\n");
        sb.append("        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #dee2e6; }\n");
        sb.append("        th { background: #f8f9fa; font-weight: 600; }\n");
        sb.append("        tr:hover { background: #f8f9fa; }\n");
        sb.append("        .status-badge { padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: 600; }\n");
        sb.append("        .status-PASSED { background: #d4edda; color: #155724; }\n");
        sb.append("        .status-FAILED { background: #f8d7da; color: #721c24; }\n");
        sb.append("        .status-ERROR { background: #fff3cd; color: #856404; }\n");
        sb.append("        .status-SKIPPED { background: #e2e3e5; color: #383d41; }\n");
        sb.append("        .result-icon { font-size: 18px; }\n");
        sb.append("    </style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");
        sb.append("    <div class=\"container\">\n");
        sb.append("        <h1>FIX Session Test Report</h1>\n");
        sb.append("        <p><strong>Suite:</strong> ").append(escapeHtml(suite.getSuiteName())).append("</p>\n");
        sb.append("        <p><strong>Started:</strong> ").append(suite.getStartTime()).append("</p>\n");
        if (suite.getEndTime() != null) {
            sb.append("        <p><strong>Completed:</strong> ").append(suite.getEndTime()).append("</p>\n");
        }

        // Summary
        sb.append("        <div class=\"summary\">\n");
        sb.append("            <div class=\"summary-item\">\n");
        sb.append("                <div class=\"label\">Total</div>\n");
        sb.append("                <div class=\"value\">").append(suite.getTotalTests()).append("</div>\n");
        sb.append("            </div>\n");
        sb.append("            <div class=\"summary-item\">\n");
        sb.append("                <div class=\"label\">Passed</div>\n");
        sb.append("                <div class=\"value passed\">").append(suite.getPassedCount()).append("</div>\n");
        sb.append("            </div>\n");
        sb.append("            <div class=\"summary-item\">\n");
        sb.append("                <div class=\"label\">Failed</div>\n");
        sb.append("                <div class=\"value failed\">").append(suite.getFailedCount()).append("</div>\n");
        sb.append("            </div>\n");
        sb.append("            <div class=\"summary-item\">\n");
        sb.append("                <div class=\"label\">Errors</div>\n");
        sb.append("                <div class=\"value error\">").append(suite.getErrorCount()).append("</div>\n");
        sb.append("            </div>\n");
        sb.append("            <div class=\"summary-item\">\n");
        sb.append("                <div class=\"label\">Duration</div>\n");
        sb.append("                <div class=\"value\">").append(suite.getTotalDurationMs()).append(" ms</div>\n");
        sb.append("            </div>\n");
        sb.append("        </div>\n");

        // Results table
        sb.append("        <h2>Test Results</h2>\n");
        sb.append("        <table>\n");
        sb.append("            <thead>\n");
        sb.append("                <tr>\n");
        sb.append("                    <th></th>\n");
        sb.append("                    <th>Test Name</th>\n");
        sb.append("                    <th>Validates</th>\n");
        sb.append("                    <th>Status</th>\n");
        sb.append("                    <th>Duration</th>\n");
        sb.append("                    <th>Message</th>\n");
        sb.append("                </tr>\n");
        sb.append("            </thead>\n");
        sb.append("            <tbody>\n");

        for (TestResult result : suite.getResults()) {
            sb.append("                <tr>\n");
            sb.append("                    <td class=\"result-icon\">").append(getStatusIcon(result.getStatus())).append("</td>\n");
            sb.append("                    <td>").append(escapeHtml(result.getTestName())).append("</td>\n");
            sb.append("                    <td>").append(result.getDescription() != null ? escapeHtml(result.getDescription()) : "").append("</td>\n");
            sb.append("                    <td><span class=\"status-badge status-").append(result.getStatus().name())
                    .append("\">").append(result.getStatus()).append("</span></td>\n");
            sb.append("                    <td>").append(result.getDurationMs()).append(" ms</td>\n");
            sb.append("                    <td>").append(escapeHtml(result.getMessage())).append("</td>\n");
            sb.append("                </tr>\n");
        }

        sb.append("            </tbody>\n");
        sb.append("        </table>\n");

        // Overall result
        sb.append("        <div style=\"margin-top: 20px; padding: 15px; border-radius: 6px; text-align: center; font-size: 18px; font-weight: bold; ");
        if (suite.isAllPassed()) {
            sb.append("background: #d4edda; color: #155724;\">\n");
            sb.append("            ALL TESTS PASSED\n");
        } else {
            sb.append("background: #f8d7da; color: #721c24;\">\n");
            sb.append("            SOME TESTS FAILED\n");
        }
        sb.append("        </div>\n");

        sb.append("    </div>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    private String getStatusIcon(TestResult.Status status) {
        switch (status) {
            case PASSED:
                return "\u2714"; // Check mark
            case FAILED:
                return "\u2718"; // X mark
            case ERROR:
                return "\u26A0"; // Warning
            case SKIPPED:
                return "\u23ED"; // Skip
            default:
                return "?";
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
