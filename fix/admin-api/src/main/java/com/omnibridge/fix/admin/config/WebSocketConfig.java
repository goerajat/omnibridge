package com.omnibridge.fix.admin.config;

import com.omnibridge.fix.admin.websocket.SessionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration for real-time session updates.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionWebSocketHandler sessionWebSocketHandler;

    public WebSocketConfig(SessionWebSocketHandler sessionWebSocketHandler) {
        this.sessionWebSocketHandler = sessionWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sessionWebSocketHandler, "/ws/sessions")
                .setAllowedOrigins("*");
    }
}
