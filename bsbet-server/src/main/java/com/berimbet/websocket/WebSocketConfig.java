package com.berimbet.websocket;

import org.springframework.context.annotation.Configuration; 
import org.springframework.web.socket.config.annotation.*;

@Configuration 
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final FootballWebSocketHandler footballWebSocketHandler;
    
    public WebSocketConfig(FootballWebSocketHandler footballWebSocketHandler) { 
        this.footballWebSocketHandler = footballWebSocketHandler; 
    }
    
    @Override 
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) { 
        registry.addHandler(footballWebSocketHandler, "/ws/football").setAllowedOrigins("*"); 
    }
}