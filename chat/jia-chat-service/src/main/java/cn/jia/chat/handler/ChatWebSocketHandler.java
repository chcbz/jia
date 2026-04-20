package cn.jia.chat.handler;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatWebSocketHandler(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, String> payload = objectMapper.readValue(message.getPayload(), Map.class);
        String conversationId = payload.get("conversationId");
        String question = payload.get("content");

        Flux<String> responseFlux = chatClient.prompt(question)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();

        // 使用concatMap处理每个响应并发送消息，避免直接subscribe
        responseFlux
            .concatMap(content -> {
                try {
                    return Mono.fromCallable(() -> {
                        session.sendMessage(new TextMessage(content));
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
                    session.sendMessage(new TextMessage("[EOM]"));
                } catch (Exception e) {
                    log.error("Error broadcasting message to all sessions", e);
                }
            });
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
                    session.sendMessage(new TextMessage(message));
                }
            } catch (Exception e) {
                log.error("Error sending broadcast message", e);
            }
        });
    }
}
