package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JuyitingAgentRelayServiceTest extends BaseMockTest {
    @Mock AgentWebSocketHandler agentWebSocketHandler;
    @Mock BuiltinHallAgentSupport builtinHallAgentSupport;
    @Mock ChatConversationService chatConversationService;
    @Mock AgentService agentService;

    private ChatConversationEventBroker broker;

    @BeforeEach
    void setUp() {
        EsContext context = new EsContext();
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);
        broker = new ChatConversationEventBroker();
        when(chatConversationService.isLiveGeneration(
                "tester", "web-client", "1001", 1L)).thenReturn(true);
        when(chatConversationService.appendOwnedMessage(
                eq("tester"), eq("web-client"), any(ChatMessageEntity.class), eq(1L)))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @AfterEach
    void tearDown() {
        EsContextHolder.clearContext();
    }

    @Test
    void legalSingleAgentRelayPersistsWithGenerationAndStreamsFinalEvent() throws Exception {
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null,
                        List.of("agent-wuyong")));
        when(agentWebSocketHandler.isAgentConnected(
                "tester", "web-client", "agent-wuyong")).thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class)))
                .thenReturn(true);

        JuyitingAgentRelayResult result = service().relay(
                request("public", null, List.of("agent-wuyong")), "1001", Flux::empty);
        CompletableFuture<List<String>> eventsFuture = result.stream()
                .take(2).collectList().toFuture();
        assertTrue(broker.publishIfLive("1001", 1L, () -> true, Map.of(
                "type", "agent_message", "conversationId", "1001",
                "agentId", "agent-wuyong", "content", "ok")));
        List<String> events = eventsFuture.get(2, TimeUnit.SECONDS);

        assertTrue(result.attempted());
        assertTrue(result.delivered());
        assertTrue(events.stream().anyMatch(value -> value.contains("agentDelivery")));
        assertTrue(events.stream().anyMatch(value -> value.contains("\"content\":\"ok\"")));
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), any(ChatMessageEntity.class), eq(1L));
        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(0, broker.watcherCount("1001"));
    }

    @Test
    void participantMetadataCannotForgeTaskAuthorization() {
        when(agentService.listTaskMemberAgentIds("tester", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong"));
        ChatMessageDTO request = request("bounty", "task-7", List.of("agent-linchong"));
        request.setMetadata(Map.of("participantAgentIds", List.of("agent-linchong")));

        JuyitingAgentRelayResult result = service().relay(request, "1001", Flux::empty);

        assertTrue(result.attempted());
        assertTrue(result.stream().blockFirst().contains("task conversation scope unavailable"));
        verify(chatConversationService, never()).appendOwnedMessage(
                any(), any(), any(ChatMessageEntity.class), org.mockito.ArgumentMatchers.anyLong());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                any(), any(), any(), any(Map.class));
    }

    @Test
    void taskQueryExceptionAndEmptyTaskFailClosed() {
        ChatMessageDTO request = request("bounty", "task-7", List.of("agent-wuyong"));
        when(agentService.listTaskMemberAgentIds("tester", "web-client", "task-7"))
                .thenThrow(new IllegalStateException("query"));
        assertTrue(service().relay(request, "1001", Flux::empty)
                .stream().blockFirst().contains("scope unavailable"));

        org.mockito.Mockito.doReturn(List.of()).when(agentService)
                .listTaskMemberAgentIds("tester", "web-client", "task-7");
        assertTrue(service().relay(request, "1001", Flux::empty)
                .stream().blockFirst().contains("scope unavailable"));
    }

    @Test
    void bountyRelaysEveryPersistedAuthoritativeTarget() {
        List<String> members = List.of("agent-wuyong", "agent-linchong");
        when(agentService.listTaskMemberAgentIds("tester", "web-client", "task-7"))
                .thenReturn(members);
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7", members));
        for (String member : members) {
            when(agentWebSocketHandler.isAgentConnected("tester", "web-client", member))
                    .thenReturn(true);
            when(agentWebSocketHandler.sendDirectMessageToAgent(
                    eq("tester"), eq("web-client"), eq(member), any(Map.class)))
                    .thenReturn(true);
        }

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", members), "1001", Flux::empty);
        List<String> events = result.stream().collectList().block();

        assertTrue(result.delivered());
        assertEquals(2, events.size());
        for (String member : members) {
            verify(agentWebSocketHandler).sendDirectMessageToAgent(
                    eq("tester"), eq("web-client"), eq(member), any(Map.class));
        }
        ArgumentCaptor<ChatMessageEntity> message = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), message.capture(), eq(1L));
        assertTrue(message.getValue().getMetadata().contains(
                "\"participantAgentIds\":[\"agent-wuyong\",\"agent-linchong\"]"));
    }

    @Test
    void requestedTargetOutsidePersistedSetIsRejected() {
        when(agentService.listTaskMemberAgentIds("tester", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong", "agent-linchong"));
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7",
                        List.of("agent-wuyong")));

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", List.of("agent-linchong")),
                "1001", Flux::empty);

        assertTrue(result.stream().blockFirst().contains("conversation scope mismatch"));
        verify(chatConversationService, never()).appendOwnedMessage(
                any(), any(), any(ChatMessageEntity.class), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void clientDisposalReleasesRelayEventAndDeletionSubscriptions() {
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null,
                        List.of("agent-wuyong")));
        when(agentWebSocketHandler.isAgentConnected(
                "tester", "web-client", "agent-wuyong")).thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class)))
                .thenReturn(true);

        var subscription = service().relay(
                        request("public", null, List.of("agent-wuyong")),
                        "1001", Flux::empty)
                .stream().subscribe();
        assertEquals(1, broker.subscriberCount("1001"));
        assertEquals(1, broker.watcherCount("1001"));

        subscription.dispose();

        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(0, broker.watcherCount("1001"));
    }

    @Test
    void deletionCompletesExistingRelaySubscriptionAndReleasesBrokerSubscribers() throws Exception {
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null,
                        List.of("agent-wuyong")));
        when(agentWebSocketHandler.isAgentConnected(
                "tester", "web-client", "agent-wuyong")).thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class)))
                .thenReturn(true);
        CountDownLatch complete = new CountDownLatch(1);

        var subscription = service().relay(
                        request("public", null, List.of("agent-wuyong")),
                        "1001", Flux::empty)
                .stream().subscribe(ignored -> { }, ignored -> { }, complete::countDown);
        assertEquals(1, broker.subscriberCount("1001"));

        try (ChatConversationEventBroker.DeletionFence fence = broker.beginDeletion("1001")) {
            fence.commitDeleted(1L);
        }

        assertTrue(complete.await(2, TimeUnit.SECONDS));
        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(0, broker.watcherCount("1001"));
        assertFalse(broker.publishIfLive("1001", 1L, () -> true,
                Map.of("type", "agent_message")));
        subscription.dispose();
    }

    private JuyitingAgentRelayService service() {
        return new JuyitingAgentRelayService(
                agentWebSocketHandler, broker, builtinHallAgentSupport,
                chatConversationService, agentService,
                new JuyitingConversationScopeService(builtinHallAgentSupport, agentService));
    }

    private ChatConversationEntity conversation(
            String scopeType, String scopeKey, String taskId, List<String> targets) {
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(1001L).setJiacn("tester").setConversationType("juyiting")
                .setConversationScopeType(scopeType).setConversationScopeKey(scopeKey)
                .setTaskId(taskId).setTargetAgentIds(
                        new JuyitingConversationScopeService(
                                builtinHallAgentSupport, agentService)
                                .serializeTargetAgentIds(targets))
                .setLifecycleGeneration(1L);
        conversation.setTenantId("0");
        conversation.setClientId("web-client");
        return conversation;
    }

    private ChatMessageDTO request(String scopeType, String taskId, List<String> targets) {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setContent("请回报当前进度");
        request.setConversationType("juyiting");
        request.setConversationScopeType(scopeType);
        request.setConversationScopeKey(taskId == null ? "public" : "task:" + taskId);
        request.setTaskId(taskId);
        request.setTargetAgentIds(targets);
        request.setSenderType("user");
        request.setSenderName("测试用户");
        return request;
    }
}
