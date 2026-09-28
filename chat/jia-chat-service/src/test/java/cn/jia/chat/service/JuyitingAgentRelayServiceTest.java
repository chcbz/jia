package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JuyitingAgentRelayServiceTest extends BaseMockTest {
    @Mock AgentWebSocketHandler agentWebSocketHandler;
    @Mock BuiltinHallAgentSupport builtinHallAgentSupport;
    @Mock ChatConversationService chatConversationService;
    @Mock AgentService agentService;
    @Mock PersonalWorkspaceTaskLinkService taskLinkService;
    @Mock ChatDeliberationService deliberationService;

    private ChatConversationEventBroker broker;

    @BeforeEach
    void setUp() {
        EsContext context = new EsContext();
        context.setTenantId("0");
        context.setJiacn("tester");
        context.setClientId("web-client");
        EsContextHolder.setContext(context);
        broker = new ChatConversationEventBroker();
    }

    @AfterEach
    void tearDown() {
        EsContextHolder.clearContext();
    }

    @Test
    void legalSingleAgentRelayUsesDurableAdmissionAndStreamsRequestEvents() throws Exception {
        stubLiveConversation();
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null, List.of("agent-wuyong")));
        stubAdmission(List.of("agent-wuyong"), false);
        ChatMessageDTO request = request("public", null, List.of("agent-wuyong"));
        ServerResolvedSender sender = sender();

        JuyitingAgentRelayResult result = service().relay(
                request, "1001", sender, InteractionRoute.CHAT);
        CompletableFuture<List<String>> eventsFuture = result.stream()
                .take(2).collectList().toFuture();
        assertTrue(broker.publishIfLive("1001", 1L, () -> true, Map.of(
                "type", "agent_message", "conversationId", "1001",
                "requestId", "req-1", "agentId", "agent-wuyong", "content", "ok")));
        List<String> events = eventsFuture.get(2, TimeUnit.SECONDS);

        assertTrue(result.attempted());
        assertTrue(result.delivered().block());
        assertTrue(events.stream().anyMatch(value -> value.contains("agentDelivery")));
        assertTrue(events.stream().anyMatch(value -> value.contains("\"content\":\"ok\"")));
        verify(deliberationService).admit(eq("0"), eq(sender), eq("1001"), eq(1L),
                any(JuyitingConversationScope.class), eq(InteractionRoute.CHAT), eq(request), any());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                any(), any(), any(), any(Map.class));
        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(0, broker.watcherCount("1001"));
    }

    @Test
    void clientSenderSpoofCannotReplaceTrustedHumanAtDurableAdmission() {
        when(builtinHallAgentSupport.isBuiltinAgent("songjiang")).thenReturn(true);
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null, List.of("songjiang")));
        stubAdmission(List.of("songjiang"), false);
        ChatMessageDTO request = request("public", null, List.of("songjiang"));
        request.setSenderType("agent");
        request.setSenderName("宋江");
        request.setMetadata(Map.of("senderType", "agent", "senderName", "宋江"));

        service().relay(request, "1001", sender(), InteractionRoute.CHAT)
                .stream().take(1).collectList().block();

        ArgumentCaptor<ServerResolvedSender> trusted = ArgumentCaptor.forClass(ServerResolvedSender.class);
        verify(deliberationService).admit(eq("0"), trusted.capture(), eq("1001"), eq(1L),
                any(JuyitingConversationScope.class), eq(InteractionRoute.CHAT), eq(request), any());
        assertEquals(ServerResolvedSender.USER_TYPE, trusted.getValue().type());
        assertEquals("权威用户", trusted.getValue().displayName());
        assertEquals("tester", trusted.getValue().jiacn());
        assertEquals("web-client", trusted.getValue().clientId());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                any(), any(), any(), any(Map.class));
    }

    @Test
    void taskRelayPassesOnlyServerResolvedActiveOpaqueMaterialReferencesToAdmission() {
        List<String> members = List.of("agent-wuyong", "agent-linchong");
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(members);
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7", members));
        when(taskLinkService.list(
                new PersonalWorkspaceTaskLinkService.Scope("0", "web-client", "tester"),
                "task-7", null)).thenReturn(new PersonalWorkspaceTaskLinkService.LinkListView(
                List.of(
                        link("rel-input", "file-input", 3, "INPUT", "ACTIVE"),
                        link("rel-reference", "file-reference", 2, "REFERENCE", "ACTIVE"),
                        link("rel-detached", "file-detached", 1, "INPUT", "DETACHED"),
                        link("rel-output", "file-output", 1, "OUTPUT", "ACTIVE"),
                        new PersonalWorkspaceTaskLinkService.LinkView(
                                "rel-foreign", "foreign-task", "foreign-file", 1,
                                "INPUT", "ACTIVE", 1L, 1L)),
                "next-page"));
        stubAdmission(members, false);
        ChatMessageDTO request = request("bounty", "task-7", members);
        request.setMetadata(Map.of("taskMaterials", Map.of(
                "status", "AVAILABLE", "complete", true,
                "items", List.of(Map.of("fileId", "forged-file", "version", 99, "role", "INPUT")))));

        JuyitingAgentRelayResult result = service().relay(
                request, "1001", sender(), InteractionRoute.CHAT);

        assertEquals(2, result.stream().take(2).collectList().block().size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> materials = ArgumentCaptor.forClass(Map.class);
        verify(deliberationService).admit(eq("0"), any(ServerResolvedSender.class), eq("1001"), eq(1L),
                any(JuyitingConversationScope.class), eq(InteractionRoute.CHAT), eq(request), materials.capture());
        assertEquals(Map.of(
                "status", "AVAILABLE",
                "complete", false,
                "items", List.of(
                        Map.of("fileId", "file-input", "version", 3, "role", "INPUT"),
                        Map.of("fileId", "file-reference", "version", 2, "role", "REFERENCE"))),
                materials.getValue());
        assertFalse(materials.getValue().toString().contains("forged-file"));
        assertFalse(materials.getValue().toString().contains("foreign-file"));
        assertFalse(materials.getValue().toString().contains("rel-input"));
        assertFalse(materials.getValue().toString().contains("file-output"));
        assertFalse(materials.getValue().toString().contains("content"));
        assertFalse(materials.getValue().toString().contains("download"));
        verify(taskLinkService, times(1)).list(
                new PersonalWorkspaceTaskLinkService.Scope("0", "web-client", "tester"),
                "task-7", null);
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                any(), any(), any(), any(Map.class));
    }

    @Test
    void taskMaterialLookupFailureAdmitsOnlyUnavailableMarker() {
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong"));
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7", List.of("agent-wuyong")));
        when(taskLinkService.list(
                new PersonalWorkspaceTaskLinkService.Scope("0", "web-client", "tester"),
                "task-7", null)).thenThrow(new IllegalStateException("lookup failed"));
        stubAdmission(List.of("agent-wuyong"), false);

        service().relay(request("bounty", "task-7", List.of("agent-wuyong")),
                "1001", sender(), InteractionRoute.CHAT).stream().take(1).collectList().block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> materials = ArgumentCaptor.forClass(Map.class);
        verify(deliberationService).admit(eq("0"), any(ServerResolvedSender.class), eq("1001"), eq(1L),
                any(JuyitingConversationScope.class), eq(InteractionRoute.CHAT), any(ChatMessageDTO.class),
                materials.capture());
        assertEquals(Map.of("status", "UNAVAILABLE", "complete", false, "items", List.of()),
                materials.getValue());
    }

    @Test
    void participantMetadataCannotForgeTaskAuthorization() {
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong"));
        ChatMessageDTO request = request("bounty", "task-7", List.of("agent-linchong"));
        request.setMetadata(Map.of("participantAgentIds", List.of("agent-linchong")));

        JuyitingAgentRelayResult result = service().relay(
                request, "1001", sender(), InteractionRoute.CHAT);

        assertTrue(result.attempted());
        assertTrue(result.stream().blockFirst().contains("task conversation scope unavailable"));
        verify(deliberationService, never()).admit(anyString(), any(), anyString(), anyLong(),
                any(), any(), any(), any());
        verify(taskLinkService, never()).list(any(), any(), any());
    }

    @Test
    void taskQueryExceptionAndEmptyTaskFailClosedBeforeAdmission() {
        ChatMessageDTO request = request("bounty", "task-7", List.of("agent-wuyong"));
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenThrow(new IllegalStateException("query"));
        assertTrue(service().relay(request, "1001", sender(), InteractionRoute.CHAT)
                .stream().blockFirst().contains("scope unavailable"));

        org.mockito.Mockito.doReturn(List.of()).when(agentService)
                .listTaskWritableMemberAgentIds("0", "web-client", "task-7");
        assertTrue(service().relay(request, "1001", sender(), InteractionRoute.CHAT)
                .stream().blockFirst().contains("scope unavailable"));
        verify(deliberationService, never()).admit(anyString(), any(), anyString(), anyLong(),
                any(), any(), any(), any());
    }

    @Test
    void bountyAdmissionUsesEveryPersistedAuthoritativeTargetWithoutSynchronousWebSocketSend() {
        List<String> members = List.of("agent-wuyong", "agent-linchong");
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(members);
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7", members));
        when(taskLinkService.list(any(), eq("task-7"), eq(null)))
                .thenReturn(new PersonalWorkspaceTaskLinkService.LinkListView(List.of(), null));
        stubAdmission(members, false);

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", members), "1001", sender(), InteractionRoute.CHAT);
        List<String> events = result.stream().take(2).collectList().block();

        assertTrue(result.delivered().block());
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(value -> value.contains("\"accepted\":true")));
        ArgumentCaptor<JuyitingConversationScope> scope = ArgumentCaptor.forClass(JuyitingConversationScope.class);
        verify(deliberationService).admit(eq("0"), any(ServerResolvedSender.class), eq("1001"), eq(1L),
                scope.capture(), eq(InteractionRoute.CHAT), any(ChatMessageDTO.class), any());
        assertEquals(members, scope.getValue().targetAgentIds());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                any(), any(), any(), any(Map.class));
    }

    @Test
    void emptyPersistedTargetSnapshotFailsClosedBeforeAdmission() {
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong"));
        ChatConversationEntity legacy = conversation(
                "bounty", "task:task-7", "task-7", List.of("agent-wuyong"));
        legacy.setTargetAgentId(null);
        legacy.setTargetAgentIds(null);
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(legacy);

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", List.of("agent-wuyong")),
                "1001", sender(), InteractionRoute.CHAT);

        assertTrue(result.stream().blockFirst().contains("conversation scope mismatch"));
        verify(deliberationService, never()).admit(anyString(), any(), anyString(), anyLong(),
                any(), any(), any(), any());
    }

    @Test
    void requestedTargetOutsidePersistedSetIsRejectedBeforeAdmission() {
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong", "agent-linchong"));
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7",
                        List.of("agent-wuyong")));

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", List.of("agent-linchong")),
                "1001", sender(), InteractionRoute.CHAT);

        assertTrue(result.stream().blockFirst().contains("conversation scope mismatch"));
        verify(deliberationService, never()).admit(anyString(), any(), anyString(), anyLong(),
                any(), any(), any(), any());
    }

    @Test
    void replayAdmissionReturnsStableDurableIdentifiersWithoutOpeningBrokerSubscription() {
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null, List.of("agent-wuyong")));
        stubAdmission(List.of("agent-wuyong"), true);

        JuyitingAgentRelayResult result = service().relay(
                request("public", null, List.of("agent-wuyong")),
                "1001", sender(), InteractionRoute.CHAT);
        List<String> events = result.stream().collectList().block();

        assertEquals(1, events.size());
        assertTrue(events.getFirst().contains("chat_request_replay"));
        assertTrue(events.getFirst().contains("req-1"));
        assertTrue(events.getFirst().contains("dispatch-agent-wuyong"));
        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(0, broker.watcherCount("1001"));
    }

    private ServerResolvedSender sender() {
        return new ServerResolvedSender(
                ServerResolvedSender.USER_TYPE, "权威用户", "tester", "web-client",
                DisplayNameSource.NICKNAME);
    }

    private void stubLiveConversation() {
        when(chatConversationService.isLiveGeneration(
                "tester", "web-client", "1001", 1L)).thenReturn(true);
    }

    private void stubAdmission(List<String> targets, boolean replay) {
        List<ChatDeliberationService.Dispatch> dispatches = targets.stream()
                .map(agentId -> new ChatDeliberationService.Dispatch(
                        "req-1", "turn-" + agentId, "dispatch-" + agentId, "evt-" + agentId,
                        agentId, "CHAT", "RECEIVED", "snapshot-" + agentId, "hash-" + agentId,
                        Map.of(), Map.of()))
                .toList();
        ChatDeliberationService.Admission admission = new ChatDeliberationService.Admission(
                "req-1", 1L, "11", "1001", 1L, InteractionRoute.CHAT, dispatches, replay);
        when(deliberationService.admit(anyString(), any(ServerResolvedSender.class), anyString(),
                anyLong(), any(JuyitingConversationScope.class), any(InteractionRoute.class),
                any(ChatMessageDTO.class), any())).thenReturn(admission);
    }

    private JuyitingAgentRelayService service() {
        return new JuyitingAgentRelayService(
                agentWebSocketHandler, broker, builtinHallAgentSupport,
                chatConversationService, agentService,
                new JuyitingConversationScopeService(builtinHallAgentSupport, agentService),
                taskLinkService, deliberationService);
    }

    private PersonalWorkspaceTaskLinkService.LinkView link(
            String relationId, String fileId, int version, String role, String state) {
        return new PersonalWorkspaceTaskLinkService.LinkView(
                relationId, "task-7", fileId, version, role, state, 1L, 1L);
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
