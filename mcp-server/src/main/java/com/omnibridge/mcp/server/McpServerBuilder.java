package com.omnibridge.mcp.server;

import com.omnibridge.mcp.config.McpServerConfig;
import com.omnibridge.mcp.index.SecondaryIndex;
import com.omnibridge.mcp.tools.GetSessionStatusTool;
import com.omnibridge.mcp.tools.ListStreamsTool;
import com.omnibridge.mcp.tools.QueryFixMessagesTool;
import com.omnibridge.mcp.util.JsonResultBuilder;
import com.omnibridge.persistence.LogStore;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and configures the MCP server with tools and selected transport.
 */
public class McpServerBuilder {

    private static final Logger log = LoggerFactory.getLogger(McpServerBuilder.class);

    private final McpServerConfig config;
    private final LogStore logStore;
    private final SecondaryIndex index; // nullable

    public McpServerBuilder(McpServerConfig config, LogStore logStore, SecondaryIndex index) {
        this.config = config;
        this.logStore = logStore;
        this.index = index;
    }

    /**
     * Build and start the MCP server. Returns a handle for shutdown.
     */
    public McpServerHandle build() throws Exception {
        String transport = config.getTransport();

        if ("http".equalsIgnoreCase(transport)) {
            return buildHttpServer();
        } else {
            return buildStdioServer();
        }
    }

    private McpServerHandle buildStdioServer() {
        log.info("Starting MCP server with STDIO transport");
        return buildStdioServerWithTools();
    }

    private McpServerHandle buildHttpServer() throws Exception {
        int port = config.getPort();
        log.info("Starting MCP server with HTTP/SSE transport on port {}", port);

        HttpServletSseServerTransportProvider transportProvider =
                new HttpServletSseServerTransportProvider(
                        JsonResultBuilder.getObjectMapper(), "/mcp/message");

        McpSyncServer mcpServer = McpServer.sync(transportProvider)
                .serverInfo("omnibridge-fix-query", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();

        // Create tool instances (shared between MCP and REST)
        ListStreamsTool listStreamsTool = new ListStreamsTool(logStore, index);
        QueryFixMessagesTool queryTool = new QueryFixMessagesTool(
                logStore, index, config.getDefaultLimit(), config.getMaxLimit());
        GetSessionStatusTool statusTool = new GetSessionStatusTool(logStore);

        registerTools(mcpServer, listStreamsTool, queryTool, statusTool);

        // Embedded Jetty
        Server jettyServer = new Server();
        ServerConnector connector = new ServerConnector(jettyServer);
        connector.setPort(port);
        jettyServer.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(
                new ToolRestServlet(listStreamsTool, queryTool, statusTool)), "/api/tools/*");
        context.addServlet(new ServletHolder(transportProvider), "/*");
        jettyServer.setHandler(context);

        jettyServer.start();
        log.info("MCP HTTP/SSE server started on port {} (SSE: /sse, Messages: /mcp/message, REST: /api/tools/*)", port);

        return new McpServerHandle(mcpServer, jettyServer);
    }

    private McpServerHandle buildStdioServerWithTools() {
        // For STDIO, create tools locally (no REST servlet needed)
        ListStreamsTool listStreamsTool = new ListStreamsTool(logStore, index);
        QueryFixMessagesTool queryTool = new QueryFixMessagesTool(
                logStore, index, config.getDefaultLimit(), config.getMaxLimit());
        GetSessionStatusTool statusTool = new GetSessionStatusTool(logStore);

        StdioServerTransportProvider transportProvider =
                new StdioServerTransportProvider(JsonResultBuilder.getObjectMapper());

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("omnibridge-fix-query", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();

        registerTools(server, listStreamsTool, queryTool, statusTool);

        log.info("MCP STDIO server ready");
        return new McpServerHandle(server, null);
    }

    private void registerTools(McpSyncServer server,
                               ListStreamsTool listStreamsTool,
                               QueryFixMessagesTool queryTool,
                               GetSessionStatusTool statusTool) {
        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(ListStreamsTool.NAME, ListStreamsTool.DESCRIPTION,
                        ListStreamsTool.INPUT_SCHEMA),
                (exchange, args) -> listStreamsTool.execute(args)
        ));

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(QueryFixMessagesTool.NAME, QueryFixMessagesTool.DESCRIPTION,
                        QueryFixMessagesTool.INPUT_SCHEMA),
                (exchange, args) -> queryTool.execute(args)
        ));

        server.addTool(new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(GetSessionStatusTool.NAME, GetSessionStatusTool.DESCRIPTION,
                        GetSessionStatusTool.INPUT_SCHEMA),
                (exchange, args) -> statusTool.execute(args)
        ));

        log.info("Registered {} MCP tools", 3);
    }

    /**
     * Handle for shutting down the MCP server.
     */
    public static class McpServerHandle implements AutoCloseable {
        private final McpSyncServer mcpServer;
        private final Server jettyServer; // null for STDIO

        McpServerHandle(McpSyncServer mcpServer, Server jettyServer) {
            this.mcpServer = mcpServer;
            this.jettyServer = jettyServer;
        }

        public void awaitShutdown() throws InterruptedException {
            if (jettyServer != null) {
                jettyServer.join();
            } else {
                // STDIO: block until process is killed
                Thread.currentThread().join();
            }
        }

        @Override
        public void close() throws Exception {
            mcpServer.closeGracefully();
            if (jettyServer != null) {
                jettyServer.stop();
            }
        }
    }
}
