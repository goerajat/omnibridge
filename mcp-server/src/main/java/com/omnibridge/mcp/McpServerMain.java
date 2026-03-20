package com.omnibridge.mcp;

import com.omnibridge.mcp.config.McpServerConfig;
import com.omnibridge.mcp.index.BackgroundIndexer;
import com.omnibridge.mcp.index.SecondaryIndex;
import com.omnibridge.mcp.server.McpServerBuilder;
import com.omnibridge.persistence.LogStore;
import com.omnibridge.persistence.chronicle.ChronicleLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * Entry point for the FIX Message Query MCP Server.
 *
 * <p>Reads Chronicle Queue persistence files and exposes them via MCP tools
 * for AI agent queries.</p>
 */
@Command(name = "mcp-server",
        mixinStandardHelpOptions = true,
        version = "FIX MCP Server 1.0",
        description = "MCP server for querying FIX messages from Chronicle Queue persistence")
public class McpServerMain implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(McpServerMain.class);

    @Option(names = {"--base-path", "-b"},
            description = "Path to Chronicle Queue data directory (default: from config)")
    private String basePath;

    @Option(names = {"--transport", "-t"},
            description = "Transport: stdio or http (default: stdio)")
    private String transport;

    @Option(names = {"--port", "-p"},
            description = "HTTP/SSE port (default: 8090)")
    private int port;

    @Option(names = {"--index"},
            description = "Enable RocksDB secondary index")
    private boolean indexEnabled;

    @Option(names = {"--index-dir"},
            description = "RocksDB index data directory")
    private String indexDataDir;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new McpServerMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        McpServerConfig config = McpServerConfig.fromArgs(basePath, transport, port,
                indexEnabled, indexDataDir);

        LogStore logStore = null;
        SecondaryIndex index = null;
        BackgroundIndexer indexer = null;
        McpServerBuilder.McpServerHandle serverHandle = null;

        try {
            // Open LogStore
            File dataDir = new File(config.getBasePath());
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }
            logStore = new ChronicleLogStore(dataDir);
            log.info("Opened LogStore at {}", config.getBasePath());

            // Optional: secondary index
            if (config.isIndexEnabled()) {
                index = new SecondaryIndex(config.getIndexDataDir());
                index.open();
                log.info("Opened secondary index at {}", config.getIndexDataDir());

                indexer = new BackgroundIndexer(logStore, index, config.getCheckpointInterval());
                indexer.start();
            }

            // Build and start MCP server
            McpServerBuilder builder = new McpServerBuilder(config, logStore, index);
            serverHandle = builder.build();
            log.info("MCP server started (transport={}, basePath={})",
                    config.getTransport(), config.getBasePath());

            // Block until shutdown
            serverHandle.awaitShutdown();
            return 0;

        } catch (Exception e) {
            log.error("MCP server failed", e);
            return 1;
        } finally {
            // Cleanup in reverse order
            if (serverHandle != null) {
                try { serverHandle.close(); } catch (Exception e) {
                    log.warn("Error closing server", e);
                }
            }
            if (indexer != null) indexer.close();
            if (index != null) index.close();
            if (logStore != null) {
                try { logStore.close(); } catch (Exception e) {
                    log.warn("Error closing LogStore", e);
                }
            }
        }
    }
}
