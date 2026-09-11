package cn.jia.chat.service;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.AgentProtocolMessageNormalizer;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.doThrow;

class JuyitingAgentRelayServiceTest extends BaseMockTest {
    @Mock
    AgentWebSocketHandler agentWebSocketHandler;
    @Mock
    ChatConversationEventBroker chatConversationEventBroker;
    @Mock
    BuiltinHallAgentSupport builtinHallAgentSupport;
    @Mock
    ChatConversationService chatConversationService;
    @Mock
    AgentService agentService;

    @BeforeEach
    void setUpConversationScope() {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(1001L)
                .setJiacn("tester")
                .setConversationType("juyiting")
                .setConversationScopeType("public")
                .setConversationScopeKey("public");
        conversation.setTenantId("0");
        conversation.setClientId("web-client");
        org.mockito.Mockito.lenient().when(chatConversationService.getOwned(
                "tester", "web-client", "1001")).thenReturn(conversation);
        org.mockito.Mockito.lenient().when(chatConversationService.appendOwnedMessage(
                org.mockito.ArgumentMatchers.eq("tester"),
                org.mockito.ArgumentMatchers.eq("web-client"), any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

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
        when(agentWebSocketHandler.isAgentConnected("tester", "web-client", "agent-wuyong")).thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class))).thenReturn(true);
        when(chatConversationEventBroker.stream("1001")).thenReturn(Flux.just("""
                {"type":"agent_message","conversationId":"1001","agentId":"agent-wuyong","senderType":"agent","content":"ok"}
                """).delayElements(Duration.ofMillis(10)));

        JuyitingAgentRelayService service = service();
        ChatMessageDTO request = request(List.of("agent-wuyong"));
        request.setMetadata(Map.of(
                "conversationId", "999",
                "conversationScopeKey", "attacker-scope",
                "accessToken", "secret"));

        JuyitingAgentRelayResult result = service.relay(request, "1001", () -> Flux.just("builtin"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.attempted());
        assertTrue(result.delivered());
        assertTrue(events.stream().anyMatch(item -> item.contains("\"agentDelivery\"")));
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agentWebSocketHandler).sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals(AgentProtocolConstants.VERSION_1, payload.get("schemaVersion"));
        assertEquals(AgentProtocolConstants.TYPE_CHAT_MESSAGE, payload.get("messageType"));
        assertEquals("agent-wuyong", payload.get("targetAgentId"));
        assertEquals("1001", payload.get("conversationId"));
        assertEquals(payload.get("sentAt"), payload.get("timestamp"));
        AgentProtocolMessageNormalizer.NormalizedMessage normalized =
                new AgentProtocolMessageNormalizer().normalizeInbound(payload);
        assertEquals(AgentProtocolConstants.TYPE_CHAT_MESSAGE, normalized.canonicalType());
        assertTrue(!payload.containsKey("commandType"));
        assertTrue(!payload.containsKey("taskId"));
        assertEquals("请回报当前进度", ((Map<?, ?>) payload.get("payload")).get("content"));
        Map<?, ?> outboundMetadata = (Map<?, ?>) payload.get("metadata");
        assertEquals("1001", outboundMetadata.get("conversationId"));
        assertEquals("public", outboundMetadata.get("conversationScopeKey"));
        assertTrue(!outboundMetadata.containsKey("accessToken"));
        ArgumentCaptor<ChatMessageEntity> messageCaptor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), messageCaptor.capture());
        assertEquals("USER", messageCaptor.getValue().getMessageType());
        assertTrue(messageCaptor.getValue().getMetadata().contains("\"conversationId\":\"1001\""));
        assertTrue(messageCaptor.getValue().getMetadata().contains(
                "\"conversationScopeKey\":\"public\""));
        assertTrue(!messageCaptor.getValue().getMetadata().contains("attacker-scope"));
        assertTrue(!messageCaptor.getValue().getMetadata().contains("accessToken"));
    }

    @Test
    void expiredHostingIsRecheckedAtDeferredSendUsingCapturedScope() {
        EsContext context = new EsContext();
        context.setJiacn("tenant-a"); context.setClientId("client-a"); EsContextHolder.setContext(context);
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(1001L).setJiacn("tenant-a").setConversationType("juyiting")
                .setConversationScopeType("public").setConversationScopeKey("public");
        conversation.setTenantId("0"); conversation.setClientId("client-a");
        when(chatConversationService.getOwned("tenant-a", "client-a", "1001"))
                .thenReturn(conversation);
        when(chatConversationService.appendOwnedMessage(
                eq("tenant-a"), eq("client-a"), any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(agentWebSocketHandler.isAgentConnected(
                "tenant-a", "client-a", "agent-wuyong")).thenReturn(true);
        when(chatConversationEventBroker.stream("1001")).thenReturn(Flux.never());
        var result = service().relay(request(List.of("agent-wuyong")), "1001", () -> Flux.just("builtin"));
        doThrow(new IllegalStateException("HOSTING_RENT_RENEWAL_REQUIRED"))
                .when(agentService).requireHostingNewWork("tenant-a", "client-a", "agent-wuyong");
        EsContextHolder.setContext(new EsContext()); // Subscription does not use ambient replacement identity.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> result.stream().collectList().block());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                eq("tenant-a"), eq("client-a"), eq("agent-wuyong"), any(Map.class));
    }

    @Test
    void emitsOfflineDeliveryEventWithoutWebSocketSend() {
        when(agentWebSocketHandler.isAgentConnected("tester", "web-client", "agent-wuyong")).thenReturn(false);
        JuyitingAgentRelayService service = service();

        JuyitingAgentRelayResult result = service.relay(request(List.of("agent-wuyong")), "1001", () -> Flux.just("builtin"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.attempted());
        assertEquals(false, result.delivered());
        assertTrue(events.getFirst().contains("\"delivered\":false"));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class));
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), any(ChatMessageEntity.class));
    }

    @Test
    void relaysMultiTargetAgentMessagesIndependently() {
        when(agentWebSocketHandler.isAgentConnected("tester", "web-client", "agent-wuyong")).thenReturn(true);
        when(agentWebSocketHandler.isAgentConnected("tester", "web-client", "agent-linchong")).thenReturn(false);
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class))).thenReturn(true);
        JuyitingAgentRelayService service = service();

        JuyitingAgentRelayResult result = service.relay(request(List.of("agent-wuyong", "agent-linchong")), "1001", () -> Flux.just("builtin"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.delivered());
        assertEquals(2, events.size());
        assertTrue(events.stream().anyMatch(item -> item.contains("\"agentId\":\"agent-wuyong\"") && item.contains("\"delivered\":true")));
        assertTrue(events.stream().anyMatch(item -> item.contains("\"agentId\":\"agent-linchong\"") && item.contains("\"delivered\":false")));
        verify(agentWebSocketHandler).sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-linchong"), any(Map.class));
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
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), any(ChatMessageEntity.class));
    }

    @Test
    void rejectsTargetOutsideOwnedRosterWithoutSavingMessage() {
        doThrow(new RuntimeException("forbidden")).when(agentService).get("agent-other");
        JuyitingAgentRelayService service = service();

        JuyitingAgentRelayResult result = service.relay(request(List.of("agent-other")), "1001", () -> Flux.just("builtin"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.attempted());
        assertTrue(result.delivered());
        assertTrue(events.getFirst().contains("target outside owned roster"));
        verify(chatConversationService, never()).appendOwnedMessage(
                any(), any(), any(ChatMessageEntity.class));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
    }

    @Test
    void rejectsBountyTargetOutsideParticipantsWithoutSavingMessage() {
        JuyitingAgentRelayService service = service();
        ChatMessageDTO request = request(List.of("agent-linchong"));
        request.setConversationScopeType("bounty");
        request.setConversationScopeKey("task:372");
        request.setTaskId("372");
        request.setMetadata(Map.of("participantAgentIds", List.of("agent-wuyong")));
        ChatConversationEntity bounty = new ChatConversationEntity()
                .setId(1001L).setJiacn("tester").setConversationType("juyiting")
                .setConversationScopeType("bounty").setConversationScopeKey("task:372")
                .setTaskId("372");
        bounty.setTenantId("0"); bounty.setClientId("web-client");
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(bounty);

        JuyitingAgentRelayResult result = service.relay(request, "1001", () -> Flux.just("builtin"));
        List<String> events = result.stream().collectList().block();

        assertTrue(result.attempted());
        assertTrue(result.delivered());
        assertTrue(events.getFirst().contains("target outside bounty participants"));
        verify(chatConversationService, never()).appendOwnedMessage(
                any(), any(), any(ChatMessageEntity.class));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
    }

    private JuyitingAgentRelayService service() {
        JuyitingConversationScopeService scopeService = new JuyitingConversationScopeService(builtinHallAgentSupport);
        return new JuyitingAgentRelayService(agentWebSocketHandler, chatConversationEventBroker,
                builtinHallAgentSupport, chatConversationService, agentService, scopeService);
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
