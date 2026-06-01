package cn.jia.chat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import cn.jia.chat.handler.ChatWebSocketHandler;
import cn.jia.chat.handler.OpenClawChannelWebSocketHandler;
import lombok.RequiredArgsConstructor;

import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnProperty(name = "jia.chat.service.websocket.enable", havingValue = "true")
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatClient chatClient;
    private final OpenClawChannelWebSocketHandler openClawChannelWebSocketHandler;
    private final OpenClawApiKeyHandshakeInterceptor openClawApiKeyHandshakeInterceptor;

    @Value("${jia.chat.service.websocket.allowed-origin-patterns:*}")
    private String[] allowedOriginPatterns;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ChatWebSocketHandler(chatClient), "/ws/chat")
                .setAllowedOriginPatterns(allowedOriginPatterns);
        registry.addHandler(openClawChannelWebSocketHandler, "/ws/agent/channel")
                .addInterceptors(openClawApiKeyHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }
}
