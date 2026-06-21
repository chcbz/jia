package cn.jia.chat.service;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JuyitingAgentRelayServiceTest extends BaseMockTest {
    @Mock
    AgentWebSocketHandler agentWebSocketHandler;
    @Mock
    ChatConversationEventBroker chatConversationEventBroker;
    @Mock
    BuiltinHallAgentSupport builtinHallAgentSupport;
    @Mock
    ChatMessageDao chatMessageDao;

    @AfterEach
    void tearDown() {
        EsContextHolder.setContext(new EsContext());
    }

    @Test
    void relaysSingleOnlineAgentAndSavesUserMessage() {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);
        when(agentWebSocketHandler.isAgentConnected("agent-wuyong")).thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(eq("agent-wuyong"), any(Map.class))).thenReturn(true);
        when(chatConversationEventBroker.stream("1001")).thenReturn(Flux.just("""
                {"type":"agent_message","conversationId":"1001","agentId":"agent-wuyong","senderType":"agent","content":"ok"}
                """).delayElements(Duration.ofMillis(10)));

        JuyitingAgentRelayService service = service();
        ChatMessageDTO request = request(List.of("agent-wuyong"));

        JuyitingAgentRelayResult result = service.relay(request, "1001", () -> Flux.just("builtin"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.attempted());
        assertTrue(result.delivered());
        assertTrue(events.stream().anyMatch(item -> item.contains("\"agentDelivery\"")));
        verify(agentWebSocketHandler).sendDirectMessageToAgent(eq("agent-wuyong"), any(Map.class));
        ArgumentCaptor<ChatMessageEntity> messageCaptor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageDao).insert(messageCaptor.capture());
        assertEquals("USER", messageCaptor.getValue().getMessageType());
        assertTrue(messageCaptor.getValue().getMetadata().contains("\"conversationScopeKey\":\"public\""));
    }

    @Test
    void emitsOfflineDeliveryEventWithoutWebSocketSend() {
        when(agentWebSocketHandler.isAgentConnected("agent-wuyong")).thenReturn(false);
        JuyitingAgentRelayService service = service();

        JuyitingAgentRelayResult result = service.relay(request(List.of("agent-wuyong")), "1001", () -> Flux.just("builtin"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.attempted());
        assertEquals(false, result.delivered());
        assertTrue(events.getFirst().contains("\"delivered\":false"));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(eq("agent-wuyong"), any(Map.class));
        verify(chatMessageDao).insert(any(ChatMessageEntity.class));
    }

    @Test
    void relaysMultiTargetAgentMessagesIndependently() {
        when(agentWebSocketHandler.isAgentConnected("agent-wuyong")).thenReturn(true);
        when(agentWebSocketHandler.isAgentConnected("agent-linchong")).thenReturn(false);
        when(agentWebSocketHandler.sendDirectMessageToAgent(eq("agent-wuyong"), any(Map.class))).thenReturn(true);
        JuyitingAgentRelayService service = service();

        JuyitingAgentRelayResult result = service.relay(request(List.of("agent-wuyong", "agent-linchong")), "1001", () -> Flux.just("builtin"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.delivered());
        assertEquals(2, events.size());
        assertTrue(events.stream().anyMatch(item -> item.contains("\"agentId\":\"agent-wuyong\"") && item.contains("\"delivered\":true")));
        assertTrue(events.stream().anyMatch(item -> item.contains("\"agentId\":\"agent-linchong\"") && item.contains("\"delivered\":false")));
        verify(agentWebSocketHandler).sendDirectMessageToAgent(eq("agent-wuyong"), any(Map.class));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(eq("agent-linchong"), any(Map.class));
    }

    @Test
    void delegatesBuiltinSongJiangToSuppliedStream() {
        when(builtinHallAgentSupport.isBuiltinAgent("builtin-songjiang")).thenReturn(true);
        JuyitingAgentRelayService service = service();

        JuyitingAgentRelayResult result = service.relay(request(List.of("builtin-songjiang")), "1001", () -> Flux.just("builtin-stream"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.attempted());
        assertTrue(result.delivered());
        assertEquals(List.of("builtin-stream"), events);
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
        verify(chatMessageDao).insert(any(ChatMessageEntity.class));
    }

    @Test
    void rejectsBountyTargetOutsideParticipantsWithoutSavingMessage() {
        JuyitingAgentRelayService service = service();
        ChatMessageDTO request = request(List.of("agent-linchong"));
        request.setConversationScopeType("bounty");
        request.setTaskId("372");
        request.setMetadata(Map.of("participantAgentIds", List.of("agent-wuyong")));

        JuyitingAgentRelayResult result = service.relay(request, "1001", () -> Flux.just("builtin"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.attempted());
        assertTrue(result.delivered());
        assertTrue(events.getFirst().contains("target outside bounty participants"));
        verify(chatMessageDao, never()).insert(any(ChatMessageEntity.class));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
    }

    private JuyitingAgentRelayService service() {
        JuyitingConversationScopeService scopeService = new JuyitingConversationScopeService(builtinHallAgentSupport);
        return new JuyitingAgentRelayService(agentWebSocketHandler, chatConversationEventBroker, builtinHallAgentSupport, chatMessageDao, scopeService);
    }

    private ChatMessageDTO request(List<String> targetAgentIds) {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("请回报当前进度");
        request.setConversationType("juyiting");
        request.setConversationScopeType("public");
        request.setConversationScopeKey("public");
        request.setSenderType("user");
        request.setSenderName("测试用户");
        request.setTargetAgentIds(targetAgentIds);
        return request;
    }
}
