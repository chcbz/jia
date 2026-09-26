package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private ChatConversationEventBroker broker;

    @BeforeEach
    void setUp() {
        EsContext context = new EsContext();
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
    void legalSingleAgentRelayPersistsWithGenerationAndStreamsFinalEvent() throws Exception {
        stubLiveConversation();
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null,
                        List.of("agent-wuyong")));
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class)))
                .thenReturn(true);

        JuyitingAgentRelayResult result = service().relay(
                request("public", null, List.of("agent-wuyong")), "1001", sender(), ignored -> Flux.empty());
        CompletableFuture<List<String>> eventsFuture = result.stream()
                .take(2).collectList().toFuture();
        assertTrue(broker.publishIfLive("1001", 1L, () -> true, Map.of(
                "type", "agent_message", "conversationId", "1001",
                "agentId", "agent-wuyong", "content", "ok")));
        List<String> events = eventsFuture.get(2, TimeUnit.SECONDS);

        assertTrue(result.attempted());
        assertTrue(result.delivered().block());
        assertTrue(events.stream().anyMatch(value -> value.contains("agentDelivery")));
        assertTrue(events.stream().anyMatch(value -> value.contains("\"content\":\"ok\"")));
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), any(ChatMessageEntity.class), eq(1L));
        verify(taskLinkService, never()).list(any(), any(), any());
        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(0, broker.watcherCount("1001"));
    }

    @Test
    void builtinRelayPersistsAuthoritativeHumanSenderAndIgnoresSpoofedFields() {
        stubLiveConversation();
        when(builtinHallAgentSupport.isBuiltinAgent("songjiang")).thenReturn(true);
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null, List.of("songjiang")));
        ChatMessageDTO request = request("public", null, List.of("songjiang"));
        request.setSenderType("agent");
        request.setSenderName("宋江");
        request.setMetadata(Map.of("senderType", "agent", "senderName", "宋江"));

        JuyitingAgentRelayResult result = service().relay(
                request, "1001", sender(), ignored -> Flux.just("builtin"));

        assertEquals(List.of("builtin"), result.stream().collectList().block());
        ArgumentCaptor<ChatMessageEntity> message = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), message.capture(), eq(1L));
        assertEquals("user", message.getValue().getSenderType());
        assertEquals("权威用户", message.getValue().getSenderName());
        assertTrue(message.getValue().getMetadata().contains("\"senderType\":\"user\""));
        assertTrue(message.getValue().getMetadata().contains("\"senderName\":\"权威用户\""));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                any(), any(), any(), any(Map.class));
    }

    @Test
    void builtinTaskRelayHandsOnlyTrustedMaterialReferencesToPromptFactory() {
        stubLiveConversation();
        when(builtinHallAgentSupport.isBuiltinAgent("songjiang")).thenReturn(true);
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(List.of("songjiang"));
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7", List.of("songjiang")));
        when(taskLinkService.list(
                new PersonalWorkspaceTaskLinkService.Scope("0", "web-client", "tester"),
                "task-7", null)).thenReturn(new PersonalWorkspaceTaskLinkService.LinkListView(
                List.of(
                        link("rel-input", "file-input", 3, "INPUT", "ACTIVE"),
                        link("rel-reference", "file-reference", 2, "REFERENCE", "ACTIVE"),
                        link("rel-output", "file-output", 1, "OUTPUT", "ACTIVE")),
                null));
        ChatMessageDTO request = request("bounty", "task-7", List.of("songjiang"));
        request.setMetadata(Map.of("taskMaterials", Map.of(
                "status", "AVAILABLE",
                "complete", true,
                "items", List.of(Map.of(
                        "fileId", "forged-file", "version", 99, "role", "INPUT")))));
        AtomicReference<Map<String, Object>> promptMaterials = new AtomicReference<>();

        JuyitingAgentRelayResult result = service().relay(
                request, "1001", sender(), materials -> {
                    promptMaterials.set(materials);
                    return Flux.just("builtin");
                });

        assertEquals(List.of("builtin"), result.stream().collectList().block());
        assertEquals(Map.of(
                "status", "AVAILABLE",
                "complete", true,
                "items", List.of(
                        Map.of("fileId", "file-input", "version", 3, "role", "INPUT"),
                        Map.of("fileId", "file-reference", "version", 2, "role", "REFERENCE"))),
                promptMaterials.get());
        assertFalse(promptMaterials.get().toString().contains("forged-file"));
        assertFalse(promptMaterials.get().toString().contains("rel-input"));
        assertFalse(promptMaterials.get().toString().contains("file-output"));
        assertFalse(promptMaterials.get().toString().contains("content"));
        assertFalse(promptMaterials.get().toString().contains("download"));
        verify(taskLinkService, times(1)).list(
                new PersonalWorkspaceTaskLinkService.Scope("0", "web-client", "tester"),
                "task-7", null);
    }

    @Test
    void builtinTaskRelayContinuesWithUnavailableMarkerWhenMaterialLookupFails() {
        stubLiveConversation();
        when(builtinHallAgentSupport.isBuiltinAgent("songjiang")).thenReturn(true);
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(List.of("songjiang"));
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7", List.of("songjiang")));
        when(taskLinkService.list(
                new PersonalWorkspaceTaskLinkService.Scope("0", "web-client", "tester"),
                "task-7", null)).thenThrow(new IllegalStateException("lookup failed"));
        AtomicReference<Map<String, Object>> promptMaterials = new AtomicReference<>();

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", List.of("songjiang")),
                "1001", sender(), materials -> {
                    promptMaterials.set(materials);
                    return Flux.just("builtin");
                });

        assertEquals(List.of("builtin"), result.stream().collectList().block());
        assertEquals(Map.of(
                "status", "UNAVAILABLE",
                "complete", false,
                "items", List.of()), promptMaterials.get());
    }

    @Test
    void participantMetadataCannotForgeTaskAuthorization() {
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong"));
        ChatMessageDTO request = request("bounty", "task-7", List.of("agent-linchong"));
        request.setMetadata(Map.of("participantAgentIds", List.of("agent-linchong")));

        JuyitingAgentRelayResult result = service().relay(request, "1001", sender(), ignored -> Flux.empty());

        assertTrue(result.attempted());
        assertTrue(result.stream().blockFirst().contains("task conversation scope unavailable"));
        verify(chatConversationService, never()).appendOwnedMessage(
                any(), any(), any(ChatMessageEntity.class), org.mockito.ArgumentMatchers.anyLong());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                any(), any(), any(), any(Map.class));
        verify(taskLinkService, never()).list(any(), any(), any());
    }

    @Test
    void taskQueryExceptionAndEmptyTaskFailClosed() {
        ChatMessageDTO request = request("bounty", "task-7", List.of("agent-wuyong"));
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenThrow(new IllegalStateException("query"));
        assertTrue(service().relay(request, "1001", sender(), ignored -> Flux.empty())
                .stream().blockFirst().contains("scope unavailable"));

        org.mockito.Mockito.doReturn(List.of()).when(agentService)
                .listTaskWritableMemberAgentIds("0", "web-client", "task-7");
        assertTrue(service().relay(request, "1001", sender(), ignored -> Flux.empty())
                .stream().blockFirst().contains("scope unavailable"));
    }

    @Test
    void bountyRelaysEveryPersistedAuthoritativeTarget() {
        stubLiveConversation();
        List<String> members = List.of("agent-wuyong", "agent-linchong");
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(members);
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7", members));
        for (String member : members) {
            when(agentWebSocketHandler.sendDirectMessageToAgent(
                    eq("tester"), eq("web-client"), eq(member), any(Map.class)))
                    .thenReturn(true);
        }

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", members), "1001", sender(), ignored -> Flux.empty());
        List<String> events = result.stream().collectList().block();

        assertTrue(result.delivered().block());
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
    void taskRelayAddsOnlyTrustedActiveMaterialMetadataOnceForAllTargets() {
        stubLiveConversation();
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
        for (String member : members) {
            when(agentWebSocketHandler.sendDirectMessageToAgent(
                    eq("tester"), eq("web-client"), eq(member), any(Map.class)))
                    .thenReturn(true);
        }
        ChatMessageDTO request = request("bounty", "task-7", members);
        request.setMetadata(Map.of(
                "taskMaterials", Map.of("status", "FORGED", "items", List.of(
                        Map.of("fileId", "foreign-file", "version", 99, "role", "INPUT"))),
                "selectedTaskId", "task-7"));

        JuyitingAgentRelayResult result = service().relay(
                request, "1001", sender(), ignored -> Flux.empty());

        assertEquals(2, result.stream().collectList().block().size());
        verify(taskLinkService, times(1)).list(
                new PersonalWorkspaceTaskLinkService.Scope("0", "web-client", "tester"),
                "task-7", null);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        for (String member : members) {
            verify(agentWebSocketHandler).sendDirectMessageToAgent(
                    eq("tester"), eq("web-client"), eq(member), payload.capture());
        }
        for (Map<String, Object> outbound : payload.getAllValues()) {
            Map<String, Object> outerMetadata = map(outbound.get("metadata"));
            Map<String, Object> nestedMetadata = map(map(outbound.get("payload")).get("metadata"));
            assertEquals(outerMetadata, nestedMetadata);
            Map<String, Object> materials = map(outerMetadata.get("taskMaterials"));
            assertEquals("AVAILABLE", materials.get("status"));
            assertEquals(false, materials.get("complete"));
            assertEquals(List.of(
                    Map.of("fileId", "file-input", "version", 3, "role", "INPUT"),
                    Map.of("fileId", "file-reference", "version", 2, "role", "REFERENCE")),
                    materials.get("items"));
            assertFalse(outbound.containsKey("taskMaterials"));
            assertFalse(materials.toString().contains("foreign-file"));
            assertFalse(materials.toString().contains("rel-input"));
            assertFalse(materials.toString().contains("download"));
            assertFalse(materials.toString().contains("content"));
        }
        ArgumentCaptor<ChatMessageEntity> saved = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), saved.capture(), eq(1L));
        assertTrue(saved.getValue().getMetadata().contains("\"taskMaterials\""));
        assertTrue(saved.getValue().getMetadata().contains("\"file-input\""));
        assertFalse(saved.getValue().getMetadata().contains("foreign-file"));
        assertFalse(saved.getValue().getMetadata().contains("rel-input"));
    }

    @Test
    void taskMaterialLookupFailureDoesNotInterruptOrdinaryRelayOrLeakForeignData() {
        stubLiveConversation();
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong"));
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation(
                        "bounty", "task:task-7", "task-7", List.of("agent-wuyong")));
        when(taskLinkService.list(
                new PersonalWorkspaceTaskLinkService.Scope("0", "web-client", "tester"),
                "task-7", null)).thenThrow(new PersonalWorkspaceTaskLinkService.Failure(
                PersonalWorkspaceTaskLinkService.Reason.NOT_FOUND));
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class)))
                .thenReturn(false);
        ChatMessageDTO request = request(
                "bounty", "task-7", List.of("agent-wuyong"));
        request.setMetadata(Map.of("taskMaterials", Map.of(
                "status", "AVAILABLE",
                "complete", true,
                "items", List.of(Map.of(
                        "fileId", "foreign-file", "version", 7, "role", "REFERENCE")))));

        JuyitingAgentRelayResult result = service().relay(
                request, "1001", sender(), ignored -> Flux.empty());

        assertTrue(result.attempted());
        assertEquals(1, result.stream().collectList().block().size());
        assertFalse(result.delivered().block());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(agentWebSocketHandler).sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), payload.capture());
        Map<String, Object> outerMetadata = map(payload.getValue().get("metadata"));
        Map<String, Object> materials = map(outerMetadata.get("taskMaterials"));
        assertEquals(Map.of(
                "status", "UNAVAILABLE",
                "complete", false,
                "items", List.of()), materials);
        assertEquals(outerMetadata, map(map(payload.getValue().get("payload")).get("metadata")));
        assertFalse(payload.getValue().toString().contains("foreign-file"));
        verify(chatConversationService).appendOwnedMessage(
                eq("tester"), eq("web-client"), any(ChatMessageEntity.class), eq(1L));
    }

    @Test
    void emptyPersistedTargetSnapshotFailsClosed() {
        when(agentService.listTaskWritableMemberAgentIds(
                "0", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong"));
        ChatConversationEntity legacy = conversation(
                "bounty", "task:task-7", "task-7", List.of("agent-wuyong"));
        legacy.setTargetAgentId(null);
        legacy.setTargetAgentIds(null);
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(legacy);

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", List.of("agent-wuyong")),
                "1001", sender(), ignored -> Flux.empty());

        assertTrue(result.stream().blockFirst().contains("conversation scope mismatch"));
        verify(chatConversationService, never()).appendOwnedMessage(
                any(), any(), any(ChatMessageEntity.class),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void allFailedMultiTargetSendsEnableFallback() {
        stubLiveConversation();
        List<String> members = List.of("agent-wuyong", "agent-linchong");
        when(agentService.listTaskWritableMemberAgentIds(
                "0", "web-client", "task-7")).thenReturn(members);
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7", members));
        for (String member : members) {
            when(agentWebSocketHandler.sendDirectMessageToAgent(
                    eq("tester"), eq("web-client"), eq(member), any(Map.class)))
                    .thenReturn(false);
        }

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", members), "1001", sender(), ignored -> Flux.empty());
        List<String> events = result.stream().collectList().block();

        assertFalse(result.delivered().block());
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(value -> value.contains("\"delivered\":false")));
    }

    @Test
    void deletedConversationBeforeRelaySubscriptionCompletesDeliveryOutcomeFalse() {
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null,
                        List.of("agent-wuyong")));
        when(chatConversationService.appendOwnedMessage(
                eq("tester"), eq("web-client"), any(ChatMessageEntity.class), eq(1L)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(chatConversationService.isLiveGeneration(
                "tester", "web-client", "1001", 1L)).thenReturn(false);

        JuyitingAgentRelayResult result = service().relay(
                request("public", null, List.of("agent-wuyong")),
                "1001", sender(), ignored -> Flux.empty());

        assertTrue(result.stream().collectList().block(Duration.ofSeconds(2)).isEmpty());
        assertFalse(result.delivered().block(Duration.ofSeconds(2)));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                any(), any(), any(), any(Map.class));
    }

    @Test
    void requestedTargetOutsidePersistedSetIsRejected() {
        when(agentService.listTaskWritableMemberAgentIds("0", "web-client", "task-7"))
                .thenReturn(List.of("agent-wuyong", "agent-linchong"));
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("bounty", "task:task-7", "task-7",
                        List.of("agent-wuyong")));

        JuyitingAgentRelayResult result = service().relay(
                request("bounty", "task-7", List.of("agent-linchong")),
                "1001", sender(), ignored -> Flux.empty());

        assertTrue(result.stream().blockFirst().contains("conversation scope mismatch"));
        verify(chatConversationService, never()).appendOwnedMessage(
                any(), any(), any(ChatMessageEntity.class), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void clientDisposalReleasesRelayEventAndDeletionSubscriptions() {
        stubLiveConversation();
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null,
                        List.of("agent-wuyong")));
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class)))
                .thenReturn(true);

        var subscription = service().relay(
                        request("public", null, List.of("agent-wuyong")),
                        "1001", sender(), ignored -> Flux.empty())
                .stream().subscribe();
        assertEquals(1, broker.subscriberCount("1001"));
        assertEquals(1, broker.watcherCount("1001"));

        subscription.dispose();

        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(0, broker.watcherCount("1001"));
    }

    @Test
    void deletionCompletesExistingRelaySubscriptionAndReleasesBrokerSubscribers() throws Exception {
        stubLiveConversation();
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null,
                        List.of("agent-wuyong")));
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("tester"), eq("web-client"), eq("agent-wuyong"), any(Map.class)))
                .thenReturn(true);
        CountDownLatch complete = new CountDownLatch(1);

        var subscription = service().relay(
                        request("public", null, List.of("agent-wuyong")),
                        "1001", sender(), ignored -> Flux.empty())
                .stream().subscribe(ignored -> { }, ignored -> { }, complete::countDown);
        assertEquals(1, broker.subscriberCount("1001"));

        try (ChatConversationEventBroker.DeletionFence fence = broker.beginDeletion("1001")) {
            fence.commitDeleted(1L);
        }

        assertTrue(complete.await(2, TimeUnit.SECONDS));
        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(0, broker.watcherCount("1001"));
        assertFalse(broker.publishIfLive("1001", 1L, () -> false,
                Map.of("type", "agent_message")));
        subscription.dispose();
    }

    private ServerResolvedSender sender() {
        return new ServerResolvedSender(
                ServerResolvedSender.USER_TYPE, "权威用户", "tester", "web-client",
                DisplayNameSource.NICKNAME);
    }

    private void stubLiveConversation() {
        when(chatConversationService.isLiveGeneration(
                "tester", "web-client", "1001", 1L)).thenReturn(true);
        when(chatConversationService.appendOwnedMessage(
                eq("tester"), eq("web-client"), any(ChatMessageEntity.class), eq(1L)))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    private JuyitingAgentRelayService service() {
        return new JuyitingAgentRelayService(
                agentWebSocketHandler, broker, builtinHallAgentSupport,
                chatConversationService, agentService,
                new JuyitingConversationScopeService(builtinHallAgentSupport, agentService),
                taskLinkService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
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
