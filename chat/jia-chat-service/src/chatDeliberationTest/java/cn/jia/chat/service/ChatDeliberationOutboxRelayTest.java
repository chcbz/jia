package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDeliberationStates;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatDeliberationOutboxRelayTest {
    private ChatDeliberationOutboxService outbox;
    private ChatDeliberationService deliberation;
    private AgentWebSocketHandler sockets;
    private BuiltinHallAgentSupport builtin;
    private ChatConversationEventBroker broker;
    private ChatConversationService conversations;
    private ChatClient chatClient;
    private ChatDeliberationOutboxRelay relay;

    @BeforeEach
    void setUp() {
        outbox = mock(ChatDeliberationOutboxService.class);
        deliberation = mock(ChatDeliberationService.class);
        sockets = mock(AgentWebSocketHandler.class);
        builtin = mock(BuiltinHallAgentSupport.class);
        broker = new ChatConversationEventBroker();
        conversations = mock(ChatConversationService.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        relay = new ChatDeliberationOutboxRelay(outbox, deliberation, sockets, builtin,
                broker, conversations, chatClient);
        when(conversations.isLiveGeneration("owner-a", "client-a", "42", 3L)).thenReturn(true);
        when(outbox.renew(any(), anyLong(), anyLong())).thenReturn(true);
        lenient().when(deliberation.getTurn(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> turnView(invocation.getArgument(3), ChatDeliberationStates.DISPATCHED));
        lenient().when(sockets.sendNegotiatedChatMessageToAgent(anyString(), anyString(), anyString(),
                anyString(), any(cn.jia.chat.deliberation.InteractionRoute.class), anyMap()))
                .thenReturn(new AgentWebSocketHandler.CapabilityDispatchResult(
                        AgentWebSocketHandler.CapabilityDispatchStatus.READY, true,
                        Map.of("decision", "READY", "profile", "CHAT")));
    }

    @Test
    void builtinPromptConsumesPersistedAuthorizedSnapshotWithoutClaimingAvailableRefsWereRead() {
        Map<String, Object> facts = Map.of("authorizedContext", Map.of(
                "summary", Map.of("content", "旧议事"),
                "recentMessages", List.of(Map.of("messageId", "7", "content", "前情")),
                "availableRefs", List.of(Map.of("type", "taskMaterial", "fileId", "file-1")),
                "materializedRefs", List.of()));
        org.springframework.ai.chat.prompt.Prompt prompt = ChatDeliberationOutboxRelay.buildBuiltinPrompt(
                "请结合资料", facts);
        assertEquals("请结合资料", prompt.getUserMessage().getText());
        String system = prompt.getSystemMessage().getText();
        assertTrue(system.contains("exact conversation owner/client scope"));
        assertTrue(system.contains("旧议事"));
        assertTrue(system.contains("file-1"));
        assertTrue(system.contains("absent from materializedRefs"));
        assertTrue(system.contains("strict no-tools has not been provider-verified"));
        assertFalse(system.contains("downloadUrl"));
    }

    @Test
    void mixedBuiltinAndHostedChildrenAreRoutedIndependentlyAfterAdmission() {
        ChatDispatchOutboxEntity builtinRow = dispatch("evt-b", "turn-b", "dispatch-b", "builtin-songjiang");
        when(builtin.isBuiltinAgent("builtin-songjiang")).thenReturn(true);
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .messages().stream().content()).thenReturn(Flux.just("忠义"));
        when(deliberation.acceptDelta(anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                any(ServerResolvedAgentSender.class)))
                .thenReturn(new ChatDeliberationService.DeltaResult(
                        ChatDeliberationService.DeltaStatus.ACCEPTED, null, null));
        relay.deliverClaim(new ChatDeliberationOutboxService.Claim(builtinRow, false));
        verify(deliberation).persistFinal(eq("tenant-a"), eq("owner-a"), eq("client-a"), eq("42"), eq(3L),
                eq("builtin-songjiang"), eq("req-1"), eq("turn-b"), eq("dispatch-b"),
                eq("snapshot-1"), eq("sha256:ctx"), eq("忠义"),
                argThat(sender -> ServerResolvedAgentSender.AGENT_TYPE.equals(sender.type())
                        && BuiltinHallAgentSupport.SONGJIANG_NAME.equals(sender.displayName())
                        && "builtin-songjiang".equals(sender.agentId())));
        verify(outbox).sent(any(), anyLong());

        clearInvocations(outbox);
        ChatDispatchOutboxEntity hostedRow = dispatch("evt-h", "turn-h", "dispatch-h", "hosted-a");
        when(builtin.isBuiltinAgent("hosted-a")).thenReturn(false);
        relay.deliverClaim(new ChatDeliberationOutboxService.Claim(hostedRow, false));
        verify(sockets).sendNegotiatedChatMessageToAgent(eq("tenant-a"), eq("owner-a"), eq("client-a"),
                eq("hosted-a"), eq(cn.jia.chat.deliberation.InteractionRoute.CHAT), anyMap());
        verify(deliberation).markDispatch("tenant-a", "owner-a", "client-a", "turn-h", true);
        verify(outbox).awaitingAck(any(), anyLong(), anyLong());
        verify(outbox, never()).sent(any(), anyLong());
    }

    @Test
    void builtinFailureBecomesExplicitTerminalRecoveryAndNeverRetriesModel() {
        ChatDispatchOutboxEntity row = dispatch("evt-b", "turn-b", "dispatch-b", "builtin-songjiang");
        when(builtin.isBuiltinAgent("builtin-songjiang")).thenReturn(true);
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .messages().stream().content()).thenReturn(Flux.error(new IllegalStateException("model down")));
        when(deliberation.getTurn("tenant-a", "owner-a", "client-a", "turn-b"))
                .thenReturn(turnView("turn-b", ChatDeliberationStates.DISPATCHED));

        relay.deliverClaim(new ChatDeliberationOutboxService.Claim(row, false));

        verify(deliberation).failBuiltinRecovery("tenant-a", "owner-a", "client-a", "turn-b");
        verify(outbox).dead(any(), eq("BUILTIN_GENERATION_FAILED"), anyLong());
        verify(outbox, never()).retry(any(), anyString(), anyLong());
    }

    @Test
    void staleBuiltinLeaseAfterCommittedFinalSettlesWithoutCallingModelAgain() {
        ChatDispatchOutboxEntity row = dispatch("evt-b", "turn-b", "dispatch-b", "builtin-songjiang");
        when(builtin.isBuiltinAgent("builtin-songjiang")).thenReturn(true);
        when(deliberation.getTurn("tenant-a", "owner-a", "client-a", "turn-b"))
                .thenReturn(turnView("turn-b", ChatDeliberationStates.FINAL_PERSISTED));

        relay.deliverClaim(new ChatDeliberationOutboxService.Claim(row, true));

        verify(outbox).sent(any(), anyLong());
        verify(deliberation, never()).failBuiltinRecovery(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(chatClient);
    }

    @Test
    void finalWithoutSubscriberRemainsRetryableThenCanBeDeliveredWithSameEventId() {
        ChatDispatchOutboxEntity row = finalEvent();
        ChatDeliberationOutboxService.Claim claim = new ChatDeliberationOutboxService.Claim(row, false);
        relay.deliverClaim(claim);
        verify(outbox).retry(eq(claim), eq("NO_ACTIVE_SSE_SUBSCRIBER"), anyLong());
        verify(outbox, never()).sent(any(), anyLong());
        verify(deliberation, never()).markPublished(anyString(), anyString(), anyString(), anyString());

        Disposable subscriber = broker.stream("42", 3L, () -> true).subscribe(event ->
                assertTrue(event.contains("evt-final")));
        clearInvocations(outbox);
        relay.deliverClaim(claim);
        verify(deliberation).markPublished("tenant-a", "owner-a", "client-a", "turn-b");
        verify(outbox).sent(eq(claim), anyLong());
        subscriber.dispose();
    }


    @Test
    void heartbeatKeepsLegitimateBuiltinCallAliveBeyondOriginalLease() {
        ChatDispatchOutboxEntity row = dispatch("evt-b", "turn-b", "dispatch-b", "builtin-songjiang");
        when(builtin.isBuiltinAgent("builtin-songjiang")).thenReturn(true);
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .messages().stream().content()).thenReturn(Flux.interval(Duration.ofMillis(30)).take(5).map(i -> "忠"));
        when(deliberation.acceptDelta(anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
                any(ServerResolvedAgentSender.class)))
                .thenReturn(new ChatDeliberationService.DeltaResult(
                        ChatDeliberationService.DeltaStatus.ACCEPTED, null, null));
        relay = new ChatDeliberationOutboxRelay(outbox, deliberation, sockets, builtin, broker,
                conversations, chatClient, 120L, 20L);
        assertTimeout(Duration.ofSeconds(2), () -> relay.deliverClaim(
                new ChatDeliberationOutboxService.Claim(row, false)));
        verify(outbox, atLeast(2)).renew(any(), anyLong(), eq(120L));
        verify(deliberation).persistFinal(anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq("忠忠忠忠忠"),
                any(ServerResolvedAgentSender.class));
        verify(outbox).sent(any(), anyLong());
        relay.close();
    }

    @Test
    void failedHeartbeatActivelyCancelsBlockedBuiltinFlowWithoutWrongWorkerSettlement() {
        ChatDispatchOutboxEntity row = dispatch("evt-b", "turn-b", "dispatch-b", "builtin-songjiang");
        when(builtin.isBuiltinAgent("builtin-songjiang")).thenReturn(true);
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .messages().stream().content()).thenReturn(Flux.never());
        when(outbox.renew(any(), anyLong(), anyLong())).thenReturn(false);
        relay = new ChatDeliberationOutboxRelay(outbox, deliberation, sockets, builtin, broker,
                conversations, chatClient, 120L, 20L);
        assertTimeout(Duration.ofSeconds(2), () -> relay.deliverClaim(
                new ChatDeliberationOutboxService.Claim(row, false)));
        verify(deliberation, never()).persistFinal(anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(ServerResolvedAgentSender.class));
        verify(outbox, never()).sent(any(), anyLong());
        verify(outbox, never()).dead(any(), anyString(), anyLong());
        relay.close();
    }


    @Test
    void userCancellationStopsNeverEndingBuiltinAndBoundedRelayStillProcessesHostedWork() throws Exception {
        ChatDispatchOutboxEntity builtinRow = dispatch("evt-b", "turn-b", "dispatch-b", "builtin-songjiang");
        ChatDispatchOutboxEntity hostedRow = dispatch("evt-h", "turn-h", "dispatch-h", "hosted-a");
        var builtinClaim = new ChatDeliberationOutboxService.Claim(builtinRow, false);
        var hostedClaim = new ChatDeliberationOutboxService.Claim(hostedRow, false);
        AtomicReference<String> builtinState = new AtomicReference<>(ChatDeliberationStates.DISPATCHED);
        CountDownLatch modelSubscribed = new CountDownLatch(1);
        CountDownLatch modelCancelled = new CountDownLatch(1);

        when(builtin.isBuiltinAgent("builtin-songjiang")).thenReturn(true);
        when(builtin.isBuiltinAgent("hosted-a")).thenReturn(false);
        when(deliberation.getTurn("tenant-a", "owner-a", "client-a", "turn-b"))
                .thenAnswer(ignored -> turnView("turn-b", builtinState.get()));
        when(deliberation.cancelTurn("tenant-a", "owner-a", "client-a", "turn-b", null, "USER_REQUESTED"))
                .thenAnswer(ignored -> {
                    builtinState.set(ChatDeliberationStates.CANCELLED);
                    return turnView("turn-b", ChatDeliberationStates.CANCELLED);
                });
        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))
                .messages().stream().content()).thenReturn(Flux.<String>never()
                        .doOnSubscribe(ignored -> modelSubscribed.countDown())
                        .doOnCancel(modelCancelled::countDown));
        when(outbox.discover(anyLong(), anyInt())).thenReturn(List.of(builtinRow, hostedRow), List.of());
        when(outbox.claim(same(builtinRow), anyString(), anyLong(), eq(120L))).thenReturn(builtinClaim);
        when(outbox.claim(same(hostedRow), anyString(), anyLong(), eq(120L))).thenReturn(hostedClaim);

        relay = new ChatDeliberationOutboxRelay(outbox, deliberation, sockets, builtin, broker,
                conversations, chatClient, 120L, 20L, 2);
        relay.start();
        assertTrue(modelSubscribed.await(1, TimeUnit.SECONDS));
        verify(outbox, timeout(1000)).awaitingAck(same(hostedClaim), anyLong(), anyLong());

        Thread cancellingRequest = new Thread(() -> deliberation.cancelTurn(
                "tenant-a", "owner-a", "client-a", "turn-b", null, "USER_REQUESTED"));
        cancellingRequest.start();
        cancellingRequest.join();
        assertTrue(modelCancelled.await(1, TimeUnit.SECONDS));
        verify(outbox, timeout(1000)).sent(same(builtinClaim), anyLong());
        verify(deliberation).cancelTurn("tenant-a", "owner-a", "client-a", "turn-b", null, "USER_REQUESTED");
        assertEquals(ChatDeliberationStates.CANCELLED,
                deliberation.getTurn("tenant-a", "owner-a", "client-a", "turn-b").state());
        verify(deliberation, never()).persistFinal(anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(ServerResolvedAgentSender.class));
        verify(deliberation, never()).failBuiltinRecovery(anyString(), anyString(), anyString(), anyString());
        verify(outbox, never()).dead(same(builtinClaim), anyString(), anyLong());
        relay.close();
    }

    @Test
    void relayRejectsUnboundedOrInvalidDeliveryConcurrency() {
        assertThrows(IllegalArgumentException.class, () -> new ChatDeliberationOutboxRelay(
                outbox, deliberation, sockets, builtin, broker, conversations, chatClient, 120L, 20L, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChatDeliberationOutboxRelay(
                outbox, deliberation, sockets, builtin, broker, conversations, chatClient, 120L, 20L, 33));
    }

    @Test
    void disabledModernTargetRemainsRetryableWithoutLegacySend() {
        ChatDispatchOutboxEntity row = dispatch("evt-h", "turn-h", "dispatch-h", "hosted-a");
        when(builtin.isBuiltinAgent("hosted-a")).thenReturn(false);
        when(sockets.sendNegotiatedChatMessageToAgent(anyString(), anyString(), anyString(), anyString(),
                any(cn.jia.chat.deliberation.InteractionRoute.class), anyMap()))
                .thenReturn(new AgentWebSocketHandler.CapabilityDispatchResult(
                        AgentWebSocketHandler.CapabilityDispatchStatus.WAITING_DISABLED, false,
                        Map.of("decision", "WAITING_DISABLED", "profile", "CHAT")));
        var claim = new ChatDeliberationOutboxService.Claim(row, false);
        relay.deliverClaim(claim);
        verify(outbox).retry(eq(claim), eq("TARGET_CHAT_PROFILE_DISABLED"), anyLong());
        verify(deliberation, never()).failTargetCapability(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyMap());
    }

    @Test
    void unsupportedModernTargetFailsDurablyAndNeverFallsBack() {
        ChatDispatchOutboxEntity row = dispatch("evt-h", "turn-h", "dispatch-h", "hosted-a");
        when(builtin.isBuiltinAgent("hosted-a")).thenReturn(false);
        Map<String, Object> profile = Map.of("decision", "UNSUPPORTED", "profile", "CHAT");
        when(sockets.sendNegotiatedChatMessageToAgent(anyString(), anyString(), anyString(), anyString(),
                any(cn.jia.chat.deliberation.InteractionRoute.class), anyMap()))
                .thenReturn(new AgentWebSocketHandler.CapabilityDispatchResult(
                        AgentWebSocketHandler.CapabilityDispatchStatus.UNSUPPORTED, false, profile));
        var claim = new ChatDeliberationOutboxService.Claim(row, false);
        relay.deliverClaim(claim);
        verify(deliberation).failTargetCapability("tenant-a", "owner-a", "client-a", "turn-h",
                "TARGET_PROFILE_UNSUPPORTED", profile);
        verify(outbox).dead(eq(claim), eq("TARGET_PROFILE_UNSUPPORTED"), anyLong());
        verify(outbox, never()).retry(eq(claim), anyString(), anyLong());
    }

    @Test
    void inspectWithoutMaterializedBytesFailsBeforeAnySocketSend() {
        ChatDispatchOutboxEntity row = row("evt-i", "turn-i", "dispatch-i", "DISPATCH", Map.ofEntries(
                Map.entry("conversationId", "42"), Map.entry("conversationGeneration", "3"),
                Map.entry("requestId", "req-1"), Map.entry("requestRevision", "1"),
                Map.entry("turnId", "turn-i"), Map.entry("dispatchId", "dispatch-i"),
                Map.entry("targetAgentId", "hosted-a"), Map.entry("contextSnapshotId", "snapshot-1"),
                Map.entry("contextHash", "sha256:ctx"), Map.entry("route", "INSPECT"),
                Map.entry("content", "inspect"), Map.entry("sourceVector", Map.of()),
                Map.entry("factsManifest", Map.of("authorizedContext", Map.of(
                        "availableRefs", List.of(Map.of("type", "message", "id", "7")),
                        "materializedRefs", List.of())))));
        when(builtin.isBuiltinAgent("hosted-a")).thenReturn(false);
        var claim = new ChatDeliberationOutboxService.Claim(row, false);
        relay.deliverClaim(claim);
        verifyNoInteractions(sockets);
        verify(deliberation).failTargetCapability("tenant-a", "owner-a", "client-a", "turn-i",
                "TARGET_INSPECT_INPUT_NOT_MATERIALIZED",
                Map.of("decision", "UNSUPPORTED", "profile", "INSPECT"));
        verify(outbox).dead(eq(claim), eq("TARGET_INSPECT_INPUT_NOT_MATERIALIZED"), anyLong());
    }

    @Test
    void deletedConversationIsFencedBeforeHostedExternalSend() {
        ChatDispatchOutboxEntity row = dispatch("evt-h", "turn-h", "dispatch-h", "hosted-a");
        when(builtin.isBuiltinAgent("hosted-a")).thenReturn(false);
        when(conversations.isLiveGeneration("owner-a", "client-a", "42", 3L)).thenReturn(false);
        var claim = new ChatDeliberationOutboxService.Claim(row, false);
        relay.deliverClaim(claim);
        verifyNoInteractions(sockets);
        verify(outbox).dead(eq(claim), eq("CONVERSATION_DELETED_OR_GENERATION_STALE"), anyLong());
    }

    @Test
    void crashAfterHostedSendRetriesSameStableMessageAndDispatchIdentity() {
        ChatDispatchOutboxEntity row = dispatch("evt-h", "turn-h", "dispatch-h", "hosted-a");
        when(builtin.isBuiltinAgent("hosted-a")).thenReturn(false);
        relay.deliverClaim(new ChatDeliberationOutboxService.Claim(row, false));
        ChatDispatchOutboxEntity retry = dispatch("evt-h", "turn-h", "dispatch-h", "hosted-a");
        relay.deliverClaim(new ChatDeliberationOutboxService.Claim(retry, true));
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<Map<String,Object>> payloads =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sockets, times(2)).sendNegotiatedChatMessageToAgent(
                eq("tenant-a"), eq("owner-a"), eq("client-a"), eq("hosted-a"),
                eq(cn.jia.chat.deliberation.InteractionRoute.CHAT), payloads.capture());
        assertEquals("evt-h", payloads.getAllValues().get(0).get("messageId"));
        assertEquals("evt-h", payloads.getAllValues().get(1).get("messageId"));
        assertEquals("dispatch-h", payloads.getAllValues().get(0).get("dispatchId"));
        assertEquals(true, payloads.getAllValues().get(0).get("ackRequired"));
    }
    private ChatDispatchOutboxEntity dispatch(String eventId, String turnId, String dispatchId, String agentId) {
        return row(eventId, turnId, dispatchId, "DISPATCH", Map.ofEntries(
                Map.entry("conversationId", "42"), Map.entry("conversationGeneration", "3"),
                Map.entry("requestId", "req-1"), Map.entry("requestRevision", "1"),
                Map.entry("turnId", turnId), Map.entry("dispatchId", dispatchId),
                Map.entry("targetAgentId", agentId), Map.entry("contextSnapshotId", "snapshot-1"),
                Map.entry("contextHash", "sha256:ctx"), Map.entry("route", "CHAT"),
                Map.entry("content", "议事"), Map.entry("sourceVector", Map.of()),
                Map.entry("factsManifest", Map.of())));
    }

    private ChatDispatchOutboxEntity finalEvent() {
        return row("evt-final", "turn-b", "dispatch-b", "FINAL_PERSISTED", Map.ofEntries(
                Map.entry("conversationId", "42"), Map.entry("conversationGeneration", "3"),
                Map.entry("requestId", "req-1"), Map.entry("turnId", "turn-b"),
                Map.entry("dispatchId", "dispatch-b"), Map.entry("targetAgentId", "hosted-a"),
                Map.entry("contextSnapshotId", "snapshot-1"), Map.entry("messageId", "9007199254740993"),
                Map.entry("content", "完成"), Map.entry("senderName", "Agent A"),
                Map.entry("eventSequence", "9223372036854775806"),
                Map.entry("eventVersion", "9223372036854775806")));
    }

    private ChatDispatchOutboxEntity row(String eventId, String turnId, String dispatchId,
            String eventType, Map<String, Object> payload) {
        return new ChatDispatchOutboxEntity().setEventId(eventId).setTenantId("tenant-a")
                .setOwnerJiacn("owner-a").setClientId("client-a").setTurnId(turnId)
                .setDispatchId(dispatchId).setEventType(eventType).setStatus("CLAIMED")
                .setPayloadJson(cn.jia.core.util.JsonUtil.toJson(payload)).setVersion(1L)
                .setAvailableAt(1L).setAttemptCount(1).setFencingToken(1L)
                .setLeaseOwner("worker").setLeaseUntil(100L).setCreatedAt(1L).setUpdatedAt(1L);
    }

    private ChatDeliberationService.TurnView turnView(String turnId, String state) {
        return new ChatDeliberationService.TurnView(turnId, "req-1", "1", "42", "3",
                "builtin-songjiang", "snapshot-1", "dispatch-b", "CHAT", state,
                "1", "0", null, null, "1", "1");
    }
}
