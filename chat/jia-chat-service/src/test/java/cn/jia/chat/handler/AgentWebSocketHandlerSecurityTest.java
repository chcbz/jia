package cn.jia.chat.handler;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketHandlerSecurityTest extends BaseMockTest {
    @Mock
    ChatClient chatClient;
    @Mock
    AgentService agentService;
    @Mock
    ObjectProvider<AgentService> agentServiceProvider;
    @Mock
    ChatMessageDao chatMessageDao;
    @Mock
    ChatConversationEventBroker chatConversationEventBroker;
    @Mock
    WebSocketSession session;

    @Test
    void sendEventSanitizesSensitiveFieldsBeforeWebSocketOutput() throws Exception {
        when(session.getId()).thenReturn("session-001");
        when(session.isOpen()).thenReturn(true);
        when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class)))
                .thenReturn(new AgentRegisterResultDTO("agent-001", "registration-token", AgentConstants.STATUS_ONLINE));

        AgentWebSocketHandler handler = new AgentWebSocketHandler(chatClient, agentServiceProvider,
                chatMessageDao, chatConversationEventBroker);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"agent.register","requestId":"reg-1","agentId":"agent-001","name":"Wu Yong"}
                """));

        handler.sendDirectMessageToAgent("agent-001", Map.of(
                "conversationId", "1001",
                "content", "secret check",
                "accessToken", "raw-access-token"));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeast(3)).sendMessage(messageCaptor.capture());
        String messages = messageCaptor.getAllValues().stream().map(TextMessage::getPayload).reduce("", String::concat);

        assertFalse(messages.contains("raw-access-token"));
        assertTrue(messages.contains("\"accessToken\":null"));
    }
}
