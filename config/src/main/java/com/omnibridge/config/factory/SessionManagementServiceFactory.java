package com.omnibridge.config.factory;

import com.omnibridge.config.ComponentFactory;
import com.omnibridge.config.provider.ComponentProvider;
import com.omnibridge.config.session.DefaultSessionManagementService;
import com.omnibridge.config.session.SessionManagementService;
import com.typesafe.config.Config;

/**
 * Factory for creating {@link SessionManagementService} instances.
 *
 * <p>Creates a default session management service for tracking sessions
 * across different protocol engines.</p>
 */
public class SessionManagementServiceFactory implements ComponentFactory<SessionManagementService> {

    @Override
    public SessionManagementService create(String name, Config config, ComponentProvider provider) {
        String serviceName = name != null ? name : "session-management-service";
        return new DefaultSessionManagementService(serviceName);
    }
}
