package cn.jia.chat.handler;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final TypeReference<Map<String, String>> MESSAGE_TYPE = new TypeReference<>() {
    };

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatWebSocketHandler(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, String> payload = objectMapper.readValue(message.getPayload(), MESSAGE_TYPE);
        String conversationId = payload.get("conversationId");
        String question = payload.get("content");

        Flux<String> responseFlux = chatClient.prompt(question)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();

        responseFlux
            .concatMap(content -> {
                try {
                    return Mono.fromCallable(() -> {
                        synchronized (session) {
                            session.sendMessage(new TextMessage(content));
                        }
                        return content;
                    }).onErrorResume(error -> {
                        log.error("Error sending WebSocket message", error);
                        return Mono.empty();
                    });
                } catch (Exception e) {
                    log.error("Error processing content", e);
                    return Mono.empty();
                }
            })
            .doOnComplete(() -> {
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage("[EOM]"));
                    }
                } catch (Exception e) {
                    log.error("Error broadcasting message to all sessions", e);
                }
            })
            .doOnError(error -> log.error("Error processing WebSocket chat response", error))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
    }

    public void sendMessageToAll(String message) {
        sessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(message));
                    }
                }
            } catch (Exception e) {
                log.error("Error sending broadcast message", e);
            }
        });
    }
}
