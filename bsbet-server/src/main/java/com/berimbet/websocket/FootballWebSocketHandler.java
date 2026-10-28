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

            final String sessionId = session.getId();

            MatchEngine engine = new MatchEngine(
                sessionId,
                // Lookup no map é seguro: put acontece ANTES de engine.start()
                // e o thread do jogo só começa a rodar depois
                () -> {
                    MatchEngine eng = activeMatches.get(sessionId);
                    if (eng != null) {
                        sendMessageSafe(session, new MatchStateDTO(eng));
                    }
                },
                () -> {
                    MatchEngine eng = activeMatches.get(sessionId);
                    if (eng != null) {
                        sendMessageSafe(session, new MatchMessageDTO(
                            "END", "Fim de Jogo",
                            eng.getHomeScore(), eng.getAwayScore()
                        ));
                        activeMatches.remove(sessionId);
                    }
                },
                (msg, eng) -> sendMessageSafe(session, new MatchMessageDTO(
                    "EVENT", msg,
                    eng.getHomeScore(), eng.getAwayScore()
                ))
            );

            activeMatches.put(sessionId, engine);
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
            try {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            } catch (Exception ignored) {
                // Sessão fechou no meio do envio – ignora silenciosamente
            }
        }
    }
}