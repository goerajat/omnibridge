package com.omnibridge.admin.factory;

import com.omnibridge.admin.AdminServer;
import com.omnibridge.admin.config.AdminServerConfig;
import com.omnibridge.admin.routes.SessionRoutes;
import com.omnibridge.admin.websocket.SessionStateWebSocket;
import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.config.session.SessionManagementService;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link AdminServer} instances.
 *
 * <p>Creates an admin server with session routes and WebSocket handlers
 * for session state monitoring.</p>
 */
public class AdminServerFactory implements ComponentFactory<AdminServer> {

    @Override
    public AdminServer create(String name, Config config, ComponentProvider provider) {
        AdminServerConfig adminConfig = AdminServerConfig.fromConfig(config);
        SessionManagementService sessionService = provider.getComponent(SessionManagementService.class);

        String serverName = name != null ? name : "admin-server";
        AdminServer server = new AdminServer(serverName, adminConfig);
        server.addRouteProvider(new SessionRoutes(sessionService));
        server.addWebSocketHandler(new SessionStateWebSocket(sessionService));

        return server;
    }
}
