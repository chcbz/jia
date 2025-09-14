package cn.jia.ai.mcp.client.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import cn.jia.ai.mcp.client.handler.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;

import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnProperty(name = "jia.mcp.client.websocket.enable", havingValue = "true")
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatClient chatClient;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ChatWebSocketHandler(chatClient), "/ws/chat")
                .setAllowedOrigins("*");
    }
}
