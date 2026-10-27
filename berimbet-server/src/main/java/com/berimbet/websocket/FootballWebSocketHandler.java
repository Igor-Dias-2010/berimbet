package com.berimbet.websocket;

import com.berimbet.dto.*; 
import com.berimbet.engine.MatchEngine; 
import com.fasterxml.jackson.databind.JsonNode; 
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component; 
import org.springframework.web.socket.*; 
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.util.Map; 
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FootballWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper mapper = new ObjectMapper(); 
    private final Map<String, MatchEngine> activeMatches = new ConcurrentHashMap<>();

    @Override 
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        if ("START".equals(node.has("action") ? node.get("action").asText() : "")) {
            if (activeMatches.containsKey(session.getId())) return; 
            MatchEngine engine = new MatchEngine(session.getId(),
                () -> sendMessageSafe(session, new MatchStateDTO(activeMatches.get(session.getId()))),
                () -> { 
                    MatchEngine eng = activeMatches.get(session.getId()); 
                    if (eng != null) { 
                        sendMessageSafe(session, new MatchMessageDTO("END", "Fim de Jogo", eng.getHomeScore(), eng.getAwayScore())); 
                        activeMatches.remove(session.getId()); 
                    } 
                },
                (msg, eng) -> sendMessageSafe(session, new MatchMessageDTO("EVENT", msg, eng.getHomeScore(), eng.getAwayScore()))
            );
            activeMatches.put(session.getId(), engine); 
            engine.start();
        }
    }

    @Override 
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { 
        MatchEngine engine = activeMatches.remove(session.getId()); 
        if (engine != null) engine.stop(); 
    }

    private void sendMessageSafe(WebSocketSession session, Object payload) { 
        if (session.isOpen()) { 
            try { session.sendMessage(new TextMessage(mapper.writeValueAsString(payload))); } catch (Exception ignored) {} 
        } 
    }
}