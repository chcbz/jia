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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private final CountDownLatch firstRead = new CountDownLatch(1);
    private final AtomicReference<ChatDeliberationService.RequestView> currentView = new AtomicReference<>();
    private final AtomicInteger reads = new AtomicInteger();

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
        assertTrue(firstRead.await(2, TimeUnit.SECONDS));
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

    @Test
    void completesPostAfterAllRequestTurnsTerminal() throws Exception {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        CompletableFuture<List<String>> result = stream.collectList().toFuture();
        assertTrue(firstRead.await(2, TimeUnit.SECONDS));
        currentView.set(view("COMPLETED", List.of("FINAL_PERSISTED"), List.of("agent-wuyong")));
        publish(Map.of("requestId", "req-1", "type", "agent_message", "content", "完整最后帧"));
        List<String> frames = result.get(2, TimeUnit.SECONDS);
        assertEquals(2, frames.size());
        assertTrue(frames.getLast().contains("完整最后帧"));
        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(2, reads.get());
    }

    @Test
    void doesNotCompleteOnPartialWithPendingTurn() throws Exception {
        List<String> targets = List.of("agent-wuyong", "agent-linchong");
        Flux<String> stream = preparedStream(targets);
        CountDownLatch partialForwarded = new CountDownLatch(1);
        CompletableFuture<List<String>> result = stream.doOnNext(frame -> {
            if (frame.contains("first-final")) partialForwarded.countDown();
        }).collectList().toFuture();
        assertTrue(firstRead.await(2, TimeUnit.SECONDS));
        currentView.set(view("PARTIAL", List.of("PUBLISHED", "STREAMING"), targets));
        publish(Map.of("requestId", "req-1", "content", "first-final"));
        assertTrue(partialForwarded.await(2, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        currentView.set(view("COMPLETED", List.of("PUBLISHED", "FINAL_PERSISTED"), targets));
        publish(Map.of("requestId", "req-1", "content", "second-final"));
        assertEquals(4, result.get(2, TimeUnit.SECONDS).size());
    }

    @Test
    void completesMixedTerminalPartial() throws Exception {
        List<String> targets = List.of("agent-wuyong", "agent-linchong");
        Flux<String> stream = preparedStream(targets);
        CompletableFuture<List<String>> result = stream.collectList().toFuture();
        assertTrue(firstRead.await(2, TimeUnit.SECONDS));
        currentView.set(view("PARTIAL", List.of("FINAL_PERSISTED", "FAILED"), targets));
        publish(Map.of("requestId", "req-1", "state", "PARTIAL"));
        assertEquals(3, result.get(2, TimeUnit.SECONDS).size());
    }

    @Test
    void handlesTerminalBeforeSubscriptionSnapshot() {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        currentView.set(view("COMPLETED", List.of("FINAL_PERSISTED"), List.of("agent-wuyong")));
        assertEquals(1, stream.collectList().block(Duration.ofSeconds(2)).size());
        assertEquals(0, broker.subscriberCount("1001"));
        assertEquals(1, reads.get());
    }

    @Test
    void buffersCompletionBetweenSubscribeAndRead() throws Exception {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        ChatDeliberationService.RequestView running = currentView.get();
        when(deliberationService.getRequest("0", "tester", "web-client", "req-1"))
                .thenAnswer(ignored -> {
                    assertEquals(1, broker.subscriberCount("1001"));
                    publish(Map.of("requestId", "req-1", "content", "buffered-final"));
                    return running;
                }).thenReturn(view("COMPLETED", List.of("FINAL_PERSISTED"), List.of("agent-wuyong")));
        List<String> result = stream.collectList().block(Duration.ofSeconds(2));
        assertEquals(2, result.size());
        assertTrue(result.getLast().contains("buffered-final"));
        assertEquals(0, broker.subscriberCount("1001"));
    }

    @Test
    void doesNotCompleteOnForeignRequestEventOrWrongScope() throws Exception {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        CompletableFuture<List<String>> result = stream.collectList().toFuture();
        assertTrue(firstRead.await(2, TimeUnit.SECONDS));
        currentView.set(view("COMPLETED", List.of("PUBLISHED"), List.of("agent-wuyong")));
        // Exact JSON lookup, not a requestId-shaped substring hidden in another request's content.
        publish(Map.of("requestId", "foreign", "content", "\"requestId\":\"req-1\""));
        publish(Map.of("requestId", "req-1", "conversationGeneration", "2"));
        publish(Map.of("requestId", "req-1", "requestRevision", "2"));
        publish(Map.of("requestId", "req-1", "tenantId", "foreign"));
        publish(Map.of("requestId", "req-1", "ownerJiacn", "foreign"));
        publish(Map.of("requestId", "req-1", "clientId", "foreign"));
        publish(Map.of("requestId", "req-1", "conversationId", "foreign"));
        publish(Map.of("requestId", "req-1", "content", "own-final"));
        List<String> resultFrames = result.get(2, TimeUnit.SECONDS);
        assertEquals(2, resultFrames.size());
        assertTrue(resultFrames.getLast().contains("own-final"));
        assertEquals(2, reads.get());
    }

    @Test
    void closingPostDoesNotCloseConversationSubscriber() throws Exception {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        try (ChatConversationEventBroker.LiveSubscription independent =
                     broker.subscribeBuffered("1001", 1L, () -> true)) {
            CompletableFuture<List<String>> result = stream.collectList().toFuture();
            assertTrue(firstRead.await(2, TimeUnit.SECONDS));
            assertEquals(2, broker.subscriberCount("1001"));
            currentView.set(view("COMPLETED", List.of("FINAL_PERSISTED"), List.of("agent-wuyong")));
            publish(Map.of("requestId", "req-1", "content", "last"));
            result.get(2, TimeUnit.SECONDS);
            assertEquals(1, broker.subscriberCount("1001"));
            publish(Map.of("requestId", "req-2", "content", "next-round"));
            assertEquals(2, independent.flux().take(2).collectList().block().size());
        }
        assertEquals(0, broker.subscriberCount("1001"));
    }

    @Test
    void disconnectDoesNotCancelDurableWork() throws Exception {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        CountDownLatch readFinished = new CountDownLatch(1);
        when(deliberationService.getRequest("0", "tester", "web-client", "req-1"))
                .thenAnswer(ignored -> { readFinished.countDown(); return currentView.get(); });
        Disposable subscription = stream.subscribe();
        assertTrue(readFinished.await(2, TimeUnit.SECONDS));
        subscription.dispose();
        assertEquals(0, broker.subscriberCount("1001"));
        verify(deliberationService, times(1)).admit(anyString(), any(), anyString(), anyLong(), any(), any(), any(), any());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(), any(), any(Map.class));
        verify(deliberationService).getRequest("0", "tester", "web-client", "req-1");
        org.mockito.Mockito.verifyNoMoreInteractions(deliberationService);
    }

    @Test
    void unknownOrIncompleteTurnsCannotComplete() throws Exception {
        List<String> targets = List.of("agent-wuyong", "agent-linchong");
        Flux<String> stream = preparedStream(targets);
        CountDownLatch forwarded = new CountDownLatch(8);
        CompletableFuture<List<String>> result = stream.doOnNext(frame -> {
            if (frame.contains("probe")) forwarded.countDown();
        }).collectList().toFuture();
        assertTrue(firstRead.await(2, TimeUnit.SECONDS));
        // Gate each read using its forwarded frame; no time-based race guesses.
        for (ChatDeliberationService.RequestView invalid : List.of(
                view("UNKNOWN", List.of("PUBLISHED", "FAILED"), targets),
                view("RECOVERY_REQUIRED", List.of("PUBLISHED", "FAILED"), targets),
                view("COMPLETED", List.of(), List.of()),
                view("COMPLETED", List.of("PUBLISHED"), targets.subList(0, 1)),
                view("PARTIAL", List.of("PUBLISHED", "RECOVERY_REQUIRED"), targets),
                view("COMPLETED", List.of("PUBLISHED", "QUEUED"), targets),
                view("COMPLETED", List.of("PUBLISHED", "DISPATCHED"), targets),
                view("COMPLETED", List.of("PUBLISHED", "UNKNOWN"), targets))) {
            CountDownLatch frameSeen = new CountDownLatch(1);
            when(deliberationService.getRequest("0", "tester", "web-client", "req-1"))
                    .thenAnswer(ignored -> { frameSeen.countDown(); return invalid; });
            publish(Map.of("requestId", "req-1", "content", "probe"));
            assertTrue(frameSeen.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());
        }
        assertTrue(forwarded.await(2, TimeUnit.SECONDS));
        currentView.set(view("CANCELLED", List.of("CANCELLED", "CANCELLED"), targets));
        when(deliberationService.getRequest("0", "tester", "web-client", "req-1")).thenAnswer(ignored -> currentView.get());
        publish(Map.of("requestId", "req-1", "content", "cancelled"));
        assertEquals(11, result.get(2, TimeUnit.SECONDS).size());
    }

    @Test
    void unavailableOrMismatchedSnapshotFailsInsteadOfNormalEof() {
        for (String mismatch : List.of("null", "request", "revision", "generation", "conversation", "turn")) {
            Flux<String> stream = preparedStream(List.of("agent-wuyong"));
            ChatDeliberationService.RequestView good = view("COMPLETED", List.of("PUBLISHED"), List.of("agent-wuyong"));
            currentView.set(mismatch.equals("null") ? null : new ChatDeliberationService.RequestView(
                    mismatch.equals("request") ? "foreign" : good.requestId(),
                    mismatch.equals("revision") ? "2" : good.requestRevision(),
                    mismatch.equals("conversation") ? "foreign" : good.conversationId(),
                    mismatch.equals("generation") ? "2" : good.conversationGeneration(),
                    good.userMessageId(), good.state(), good.stateVersion(),
                    mismatch.equals("turn") ? List.of(turn("agent-wuyong", "PUBLISHED", "foreign")) : good.turns(), List.of()));
            assertThrows(IllegalStateException.class, () -> stream.collectList().block(Duration.ofSeconds(2)));
            assertEquals(0, broker.subscriberCount("1001"));
        }
    }

    @Test
    void readFailureClosesOnlyPostAndDoesNotPretendSuccess() {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        when(deliberationService.getRequest("0", "tester", "web-client", "req-1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> stream.collectList().block(Duration.ofSeconds(2)));
        assertEquals(0, broker.subscriberCount("1001"));
    }

    @Test
    void readsCapturedIdentityOffPublisherThreadAndSerializesEventReads() throws Exception {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> readerThread = new AtomicReference<>();
        CountDownLatch initialRead = new CountDownLatch(1);
        when(deliberationService.getRequest("0", "tester", "web-client", "req-1"))
                .thenAnswer(ignored -> {
                    readerThread.set(Thread.currentThread().getName());
                    int call = calls.incrementAndGet();
                    if (call == 1) initialRead.countDown();
                    if (call == 2) { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); }
                    return view(call < 3 ? "RUNNING" : "COMPLETED",
                            List.of(call < 3 ? "STREAMING" : "FINAL_PERSISTED"), List.of("agent-wuyong"));
                });
        CompletableFuture<List<String>> result = stream.collectList().toFuture();
        assertTrue(initialRead.await(2, TimeUnit.SECONDS));
        publish(Map.of("requestId", "req-1", "content", "first"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        publish(Map.of("requestId", "req-1", "content", "last"));
        assertEquals(2, calls.get());
        release.countDown();
        assertEquals(3, result.get(2, TimeUnit.SECONDS).size());
        assertTrue(readerThread.get().startsWith("boundedElastic"));
    }

    @Test
    void failedAndCancelledRequestsWithAllTerminalTurnsCompleteWithoutSuccessFabrication() {
        for (String state : List.of("FAILED", "CANCELLED")) {
            Flux<String> stream = preparedStream(List.of("agent-wuyong"));
            currentView.set(view(state, List.of(state), List.of("agent-wuyong")));
            List<String> frames = stream.collectList().block(Duration.ofSeconds(2));
            assertEquals(1, frames.size()); // Only original acceptance; no fabricated successful answer.
            assertFalse(frames.getFirst().contains("COMPLETED"));
            assertEquals(0, broker.subscriberCount("1001"));
        }
    }

    @Test
    void capturesNondefaultTenantOwnerClientBeforeThreadLocalIsCleared() {
        EsContext context = new EsContext();
        context.setTenantId("tenant-alt"); context.setJiacn("owner-alt"); context.setClientId("client-alt");
        EsContextHolder.setContext(context);
        ChatConversationEntity alternate = conversation("public", "public", null, List.of("agent-wuyong"));
        alternate.setTenantId("tenant-alt"); alternate.setJiacn("owner-alt"); alternate.setClientId("client-alt");
        when(chatConversationService.getOwned("owner-alt", "client-alt", "1001")).thenReturn(alternate);
        when(chatConversationService.isLiveGeneration("owner-alt", "client-alt", "1001", 1L)).thenReturn(true);
        stubAdmission(List.of("agent-wuyong"), false);
        when(deliberationService.getRequest("tenant-alt", "owner-alt", "client-alt", "req-1"))
                .thenReturn(view("COMPLETED", List.of("FINAL_PERSISTED"), List.of("agent-wuyong")));
        ServerResolvedSender trusted = new ServerResolvedSender(ServerResolvedSender.USER_TYPE, "Alternate Owner",
                "owner-alt", "client-alt", DisplayNameSource.NICKNAME);
        Flux<String> stream = service().relay(request("public", null, List.of("agent-wuyong")), "1001", trusted, InteractionRoute.CHAT).stream();
        EsContextHolder.clearContext();
        assertEquals(1, stream.collectList().block(Duration.ofSeconds(2)).size());
        verify(deliberationService).getRequest("tenant-alt", "owner-alt", "client-alt", "req-1");
        verify(deliberationService, never()).getRequest("0", "tester", "web-client", "req-1");
    }

    @Test
    void liveReadFailureIsNotNormalCompletionAndReleasesPostBuffer() throws Exception {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        CompletableFuture<List<String>> result = stream.collectList().toFuture();
        assertTrue(firstRead.await(2, TimeUnit.SECONDS));
        when(deliberationService.getRequest("0", "tester", "web-client", "req-1"))
                .thenThrow(new IllegalStateException("live read unavailable"));
        publish(Map.of("requestId", "req-1", "content", "final signal without readable authority"));
        java.util.concurrent.ExecutionException error = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof IllegalStateException);
        assertEquals(0, broker.subscriberCount("1001"));
    }

    @Test
    void realFinalOutboxPublicationCompletesBeforeMarkPublishedTransaction() throws Exception {
        Flux<String> stream = preparedStream(List.of("agent-wuyong"));
        CompletableFuture<List<String>> result = stream.collectList().toFuture();
        assertTrue(firstRead.await(2, TimeUnit.SECONDS));
        // FINAL_PERSISTED and the final-delivery outbox are already committed together.
        currentView.set(view("COMPLETED", List.of("FINAL_PERSISTED"), List.of("agent-wuyong")));
        ChatDeliberationOutboxService outbox = org.mockito.Mockito.mock(ChatDeliberationOutboxService.class);
        org.springframework.ai.chat.client.ChatClient client = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class);
        cn.jia.chat.deliberation.ChatDispatchOutboxEntity row = new cn.jia.chat.deliberation.ChatDispatchOutboxEntity()
                .setEventId("committed-final-event").setTenantId("0").setOwnerJiacn("tester").setClientId("web-client")
                .setTurnId("turn-agent-wuyong").setEventType("FINAL_PERSISTED").setCreatedAt(1L)
                .setPayloadJson(cn.jia.core.util.JsonUtil.toJson(Map.of(
                        "requestId", "req-1", "conversationId", "1001", "conversationGeneration", "1", "content", "committed final")));
        org.mockito.Mockito.doAnswer(ignored -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            // Force the actual outbox publisher to wait at its subsequent state write.
            // The POST must have already forwarded the full frame and completed, without PUBLISHED.
            List<String> frames = result.get(2, TimeUnit.SECONDS);
            assertEquals(2, frames.size());
            assertTrue(frames.getLast().contains("committed-final-event"));
            assertTrue(frames.getLast().contains("committed final"));
            assertEquals(0, broker.subscriberCount("1001"));
            return null;
        }).when(deliberationService).markPublished("0", "tester", "web-client", "turn-agent-wuyong");
        try (ChatDeliberationOutboxRelay worker = new ChatDeliberationOutboxRelay(outbox, deliberationService,
                agentWebSocketHandler, builtinHallAgentSupport, broker, chatConversationService, client)) {
            ChatDeliberationOutboxService.Claim claim = new ChatDeliberationOutboxService.Claim(row, false);
            worker.deliverClaim(claim);
            verify(outbox).sent(eq(claim), anyLong());
            verify(outbox, never()).retry(any(), anyString(), anyLong());
        }
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void privateInspectAdmissionKeepsItsRouteAndCompletesFromAuthoritativeSnapshot() {
        stubLiveConversation();
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("private", "agent:agent-wuyong", null, List.of("agent-wuyong")));
        stubAdmission(List.of("agent-wuyong"), false);
        ChatDeliberationService.Dispatch dispatch = new ChatDeliberationService.Dispatch("req-1", "turn-agent-wuyong",
                "dispatch-agent-wuyong", "evt-agent-wuyong", "agent-wuyong", "INSPECT", "RECEIVED", "snapshot-agent-wuyong", "hash-agent-wuyong", Map.of(), Map.of());
        when(deliberationService.admit(eq("0"), any(), eq("1001"), eq(1L), any(), eq(InteractionRoute.INSPECT), any(), any()))
                .thenReturn(new ChatDeliberationService.Admission("req-1", 1L, "11", "1001", 1L, InteractionRoute.INSPECT, List.of(dispatch), false));
        ChatDeliberationService.TurnView turn = new ChatDeliberationService.TurnView("turn-agent-wuyong", "req-1", "1", "1001", "1", "agent-wuyong",
                "snapshot-agent-wuyong", "dispatch-agent-wuyong", "INSPECT", "FINAL_PERSISTED", "2", "0", null, "12", "1", "2");
        currentView.set(new ChatDeliberationService.RequestView("req-1", "1", "1001", "1", "11", "COMPLETED", "2", List.of(turn), List.of()));
        ChatMessageDTO request = request("private", null, List.of("agent-wuyong"));
        request.setConversationScopeKey("agent:agent-wuyong");
        assertEquals(1, service().relay(request, "1001", sender(), InteractionRoute.INSPECT)
                .stream().collectList().block(Duration.ofSeconds(2)).size());
        verify(deliberationService).admit(eq("0"), any(), eq("1001"), eq(1L), any(), eq(InteractionRoute.INSPECT), eq(request), any());
        assertEquals(0, broker.subscriberCount("1001"));
    }

    private Flux<String> preparedStream(List<String> targets) {
        stubLiveConversation();
        when(chatConversationService.getOwned("tester", "web-client", "1001"))
                .thenReturn(conversation("public", "public", null, targets));
        stubAdmission(targets, false);
        return service().relay(request("public", null, targets), "1001", sender(), InteractionRoute.CHAT).stream();
    }

    private void publish(Map<String, ?> event) {
        assertTrue(broker.publishIfLive("1001", 1L, () -> true, event));
    }

    private ChatDeliberationService.RequestView view(String state, List<String> states, List<String> targets) {
        return new ChatDeliberationService.RequestView("req-1", "1", "1001", "1", "11", state, "1",
                java.util.stream.IntStream.range(0, states.size())
                        .mapToObj(index -> turn(targets.get(index), states.get(index), "req-1")).toList(), List.of());
    }

    private ChatDeliberationService.TurnView turn(String target, String state, String requestId) {
        return new ChatDeliberationService.TurnView("turn-" + target, requestId, "1", "1001", "1", target,
                "snapshot-" + target, "dispatch-" + target, "CHAT", state, "1", "0", null,
                "PUBLISHED".equals(state) || "FINAL_PERSISTED".equals(state) ? "12" : null, "1", "1");
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
        currentView.set(view("RUNNING", targets.stream().map(ignored -> "DISPATCHED").toList(), targets));
        org.mockito.Mockito.lenient().when(deliberationService.getRequest("0", "tester", "web-client", "req-1"))
                .thenAnswer(ignored -> {
                    reads.incrementAndGet();
                    firstRead.countDown();
                    return currentView.get();
                });
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
