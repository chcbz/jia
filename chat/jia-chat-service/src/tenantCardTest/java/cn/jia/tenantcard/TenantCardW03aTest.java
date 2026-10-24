package cn.jia.tenantcard;

import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.ChatDeliberationOutboxRelay;
import cn.jia.chat.service.ChatDeliberationOutboxService;
import cn.jia.chat.service.ChatDeliberationService;
import cn.jia.chat.service.ChatWorkerTenantPolicy;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardW03aTest {
    @AfterEach
    void clearTrustedScope() {
        TrustedTenantContext.clear();
    }

    @Test
    void policyOneSkipsTenantZeroCandidatesBeforeClaiming() {
        Fixture fixture = fixture(Set.of("1"));
        when(fixture.outbox.discover(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(row("DISPATCH", "0", dispatchPayload())));
        ((AtomicBoolean) ReflectionTestUtils.getField(fixture.relay, "running")).set(true);

        ReflectionTestUtils.invokeMethod(fixture.relay, "pollOnce");

        verify(fixture.outbox).discover(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(fixture.outbox, org.mockito.Mockito.never()).claim(
                org.mockito.ArgumentMatchers.any(), anyString(), anyLong(), anyLong());
        fixture.relay.close();
    }

    @Test
    void disallowedAndMutatedClaimsPerformNoDeliveryOrSettlement() {
        for (String eventType : List.of("DISPATCH", "CANCEL_REQUESTED", "FINAL_PERSISTED", "ACTION_FINAL", "UNKNOWN")) {
            assertRejectedWithoutEffects(new ChatDeliberationOutboxService.Claim(
                    row(eventType, "0", payloadFor(eventType)), false));

            ChatDispatchOutboxEntity mutated = row(eventType, "1", payloadFor(eventType));
            ChatDeliberationOutboxService.Claim claim = new ChatDeliberationOutboxService.Claim(mutated, false);
            mutated.setTenantId("0");
            assertRejectedWithoutEffects(claim);
        }
    }

    @Test
    void rejectedSubmissionReturnsTheAlreadyAcquiredDeliveryPermit() {
        Fixture fixture = fixture(Set.of("1"));
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(fixture.relay, "deliverySlots");
        slots.acquireUninterruptibly();

        ReflectionTestUtils.invokeMethod(fixture.relay, "submitClaim",
                new ChatDeliberationOutboxService.Claim(row("DISPATCH", "0", dispatchPayload()), false));

        assertEquals(4, slots.availablePermits());
        verifyNoInteractions(fixture.outbox, fixture.deliberation, fixture.sockets, fixture.builtin,
                fixture.conversations, fixture.chatClient);
    }

    @Test
    void claimMutationInsideHostedBrokerPredicatePreventsSocketAndSettlement() {
        Fixture fixture = fixture(Set.of("1"));
        ChatDispatchOutboxEntity row = row("DISPATCH", "1", dispatchPayload());
        ChatDeliberationOutboxService.Claim claim = new ChatDeliberationOutboxService.Claim(row, false);
        when(fixture.builtin.isBuiltinAgent("agent-a")).thenReturn(false);
        when(fixture.conversations.isLiveGeneration("1", "owner-a", "client-a", "conversation-a", 1L))
                .thenAnswer(ignored -> {
                    row.setTenantId("0");
                    return true;
                });

        deliver(fixture.relay, claim);

        verifyNoInteractions(fixture.outbox, fixture.deliberation, fixture.sockets);
    }

    @Test
    void finalClaimMutationInsideGenerationProbePublishesNoSseEventOrSettlement() {
        Fixture fixture = fixture(Set.of("1"));
        ChatDispatchOutboxEntity row = row("FINAL_PERSISTED", "1", finalPayload());
        ChatDeliberationOutboxService.Claim claim = new ChatDeliberationOutboxService.Claim(row, false);
        AtomicInteger received = new AtomicInteger();
        ChatConversationEventBroker.LiveSubscription subscription = fixture.broker.subscribeBuffered(
                "conversation-a", 1L, () -> true);
        Disposable listener = subscription.flux().subscribe(ignored -> received.incrementAndGet());
        when(fixture.conversations.isLiveGeneration("1", "owner-a", "client-a", "conversation-a", 1L))
                .thenAnswer(ignored -> {
                    row.setTenantId("0");
                    return true;
                });
        try {
            deliver(fixture.relay, claim);
        } finally {
            listener.dispose();
            subscription.close();
        }

        assertEquals(0, received.get());
        verifyNoInteractions(fixture.outbox, fixture.deliberation, fixture.sockets);
    }

    @Test
    void policyOneHostedDispatchCancelAndFinalStillUseTheirOriginalMockedEffects() {
        Fixture fixture = fixture(Set.of("1"));
        when(fixture.builtin.isBuiltinAgent("agent-a")).thenReturn(false);
        when(fixture.conversations.isLiveGeneration("1", "owner-a", "client-a", "conversation-a", 1L))
                .thenReturn(true);
        when(fixture.sockets.sendNegotiatedChatMessageToAgent(eq("1"), eq("owner-a"), eq("client-a"),
                eq("agent-a"), eq(InteractionRoute.CHAT), anyMap()))
                .thenReturn(new AgentWebSocketHandler.CapabilityDispatchResult(
                        AgentWebSocketHandler.CapabilityDispatchStatus.READY, true, Map.of()));
        when(fixture.sockets.sendDirectMessageToAgent(eq("1"), eq("owner-a"), eq("client-a"),
                eq("agent-a"), anyMap())).thenReturn(true);

        deliver(fixture.relay, new ChatDeliberationOutboxService.Claim(
                row("DISPATCH", "1", dispatchPayload()), false));
        deliver(fixture.relay, new ChatDeliberationOutboxService.Claim(
                row("CANCEL_REQUESTED", "1", cancelPayload()), false));
        ChatConversationEventBroker.LiveSubscription subscription = fixture.broker.subscribeBuffered(
                "conversation-a", 1L, () -> true);
        AtomicInteger finalEvents = new AtomicInteger();
        Disposable listener = subscription.flux().subscribe(ignored -> finalEvents.incrementAndGet());
        try {
            deliver(fixture.relay, new ChatDeliberationOutboxService.Claim(
                    row("FINAL_PERSISTED", "1", finalPayload()), false));
        } finally {
            listener.dispose();
            subscription.close();
        }

        verify(fixture.sockets).sendNegotiatedChatMessageToAgent(eq("1"), eq("owner-a"), eq("client-a"),
                eq("agent-a"), eq(InteractionRoute.CHAT), anyMap());
        verify(fixture.sockets).sendDirectMessageToAgent(eq("1"), eq("owner-a"), eq("client-a"),
                eq("agent-a"), anyMap());
        verify(fixture.outbox).awaitingAck(org.mockito.ArgumentMatchers.any(), anyLong(), anyLong());
        verify(fixture.outbox, times(2)).sent(org.mockito.ArgumentMatchers.any(), anyLong());
        verify(fixture.deliberation).markDispatch("1", "owner-a", "client-a", "turn-a", true);
        verify(fixture.deliberation).markPublished("1", "owner-a", "client-a", "turn-a");
        assertEquals(1, finalEvents.get());
    }

    @Test
    void pollUsesCandidateCanonicalScopeAndClearsAmbientContextOnSuccess() {
        Fixture fixture = fixture(Set.of("1"));
        ChatDispatchOutboxEntity candidate = row("DISPATCH", "1", dispatchPayload());
        when(fixture.outbox.discover(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(candidate));
        when(fixture.outbox.claim(org.mockito.ArgumentMatchers.same(candidate), anyString(), anyLong(), anyLong()))
                .thenAnswer(ignored -> {
                    BusinessScope trusted = TrustedTenantContext.requireScope();
                    assertEquals("1", trusted.tenantId().value());
                    assertEquals("owner-a", trusted.ownerJiacn());
                    assertEquals("client-a", trusted.callerClientId());
                    return null;
                });
        ((AtomicBoolean) ReflectionTestUtils.getField(fixture.relay, "running")).set(true);

        TrustedTenantContext.runWithScope(scope("0", "stale-owner", "stale-client"),
                () -> ReflectionTestUtils.invokeMethod(fixture.relay, "pollOnce"));

        assertFalse(TrustedTenantContext.currentScope().isPresent());
        fixture.relay.close();
    }

    @Test
    void deliveryUsesClaimScopeAndClearsItAfterSuccessAndFailure() {
        Fixture success = fixture(Set.of("1"));
        ChatDeliberationOutboxService.Claim unsupported = new ChatDeliberationOutboxService.Claim(
                row("UNKNOWN", "1", Map.of()), false);
        when(success.outbox.dead(org.mockito.ArgumentMatchers.same(unsupported), anyString(), anyLong()))
                .thenAnswer(ignored -> {
                    assertEquals(scope("1", "owner-a", "client-a"),
                            TrustedTenantContext.requireScope());
                    return true;
                });
        deliver(success.relay, unsupported);
        assertFalse(TrustedTenantContext.currentScope().isPresent());

        Fixture failure = fixture(Set.of("1"));
        ChatDeliberationOutboxService.Claim claim = new ChatDeliberationOutboxService.Claim(
                row("DISPATCH", "1", dispatchPayload()), false);
        when(failure.builtin.isBuiltinAgent("agent-a")).thenAnswer(ignored -> {
            assertEquals(scope("1", "owner-a", "client-a"), TrustedTenantContext.requireScope());
            throw new IllegalStateException("forced delivery failure");
        });
        assertThrows(RuntimeException.class, () -> deliver(failure.relay, claim));
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void asyncBuiltinCallbacksClearForeignPooledScopeOnSuccessFailureAndCancellation() throws Exception {
        for (boolean fail : List.of(false, true)) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            AtomicReference<String> deltaScope = new AtomicReference<>();
            AtomicReference<String> callbackAfter = new AtomicReference<>();
            AtomicReference<String> cancellationScope = new AtomicReference<>("NOT_CALLED");
            ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
            Fixture fixture = fixture(Set.of("1"), chatClient);
            try {
                configureBuiltin(fixture);
                Flux<String> chunks = Flux.<String>create(sink -> pool.execute(() -> {
                    seedCurrentScope(scope("0", "owner-a", "client-a"));
                    try {
                        sink.next("async-delta");
                    } finally {
                        callbackAfter.set(currentTenant());
                    }
                    sink.complete();
                })).doOnCancel(() -> cancellationScope.set(currentTenant()));
                when(chatClient.prompt(any(Prompt.class)).messages().stream().content()).thenReturn(chunks);
                when(fixture.deliberation.acceptDelta(anyString(), anyString(), anyString(), anyString(), anyLong(),
                        anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                        anyString(), any())).thenAnswer(ignored -> {
                            deltaScope.set(currentTenant());
                            if (fail) throw new IllegalStateException("isolated callback failure");
                            return new ChatDeliberationService.DeltaResult(
                                    ChatDeliberationService.DeltaStatus.ACCEPTED, null, null);
                        });
                when(fixture.outbox.dead(any(), anyString(), anyLong())).thenAnswer(ignored -> {
                    assertEquals("1", currentTenant());
                    return true;
                });

                deliver(fixture.relay, new ChatDeliberationOutboxService.Claim(
                        row("DISPATCH", "1", builtinDispatchPayload()), false));

                String poolNext = pool.submit(() -> {
                    String current = currentTenant();
                    TrustedTenantContext.clear();
                    return current;
                }).get(5, TimeUnit.SECONDS);
                assertEquals("1", deltaScope.get());
                assertEquals("EMPTY", callbackAfter.get());
                assertEquals(fail ? "EMPTY" : "NOT_CALLED", cancellationScope.get());
                assertEquals("EMPTY", poolNext);
                assertFalse(TrustedTenantContext.currentScope().isPresent());
            } finally {
                fixture.relay.close();
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void synchronousBuiltinCallbacksRetainDeliveryScopeThroughFinalAndSettlement() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        Fixture fixture = fixture(Set.of("1"), chatClient);
        configureBuiltin(fixture);
        when(chatClient.prompt(any(Prompt.class)).messages().stream().content())
                .thenReturn(Flux.just("sync-delta"));
        when(fixture.deliberation.acceptDelta(anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), any())).thenAnswer(ignored -> {
                    assertEquals("1", currentTenant());
                    return new ChatDeliberationService.DeltaResult(
                            ChatDeliberationService.DeltaStatus.ACCEPTED, null, null);
                });
        when(fixture.deliberation.persistFinal(anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any())).thenAnswer(ignored -> {
                    assertEquals("1", currentTenant());
                    return null;
                });
        when(fixture.outbox.sent(any(), anyLong())).thenAnswer(ignored -> {
            assertEquals("1", currentTenant());
            return true;
        });

        deliver(fixture.relay, new ChatDeliberationOutboxService.Claim(
                row("DISPATCH", "1", builtinDispatchPayload()), false));

        verify(fixture.deliberation).persistFinal(anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(fixture.outbox).sent(any(), anyLong());
        assertFalse(TrustedTenantContext.currentScope().isPresent());
        fixture.relay.close();
    }

    private static void assertRejectedWithoutEffects(ChatDeliberationOutboxService.Claim claim) {
        Fixture fixture = fixture(Set.of("1"));
        deliver(fixture.relay, claim);

        verifyNoInteractions(fixture.outbox, fixture.deliberation, fixture.sockets, fixture.builtin,
                fixture.conversations, fixture.chatClient);
    }

    private static void deliver(ChatDeliberationOutboxRelay relay, ChatDeliberationOutboxService.Claim claim) {
        ReflectionTestUtils.invokeMethod(relay, "deliverClaim", claim);
    }

    private static Fixture fixture(Set<String> tenants) {
        return fixture(tenants, mock(ChatClient.class));
    }

    private static Fixture fixture(Set<String> tenants, ChatClient chatClient) {
        ChatDeliberationOutboxService outbox = mock(ChatDeliberationOutboxService.class);
        ChatDeliberationService deliberation = mock(ChatDeliberationService.class);
        AgentWebSocketHandler sockets = mock(AgentWebSocketHandler.class);
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        ChatConversationService conversations = mock(ChatConversationService.class);
        return new Fixture(outbox, deliberation, sockets, builtin, broker, conversations, chatClient,
                new ChatDeliberationOutboxRelay(outbox, deliberation, sockets, builtin, broker, conversations,
                        chatClient, new ChatWorkerTenantPolicy(tenants)));
    }

    private static void configureBuiltin(Fixture fixture) {
        when(fixture.builtin.isBuiltinAgent("builtin-songjiang")).thenReturn(true);
        when(fixture.outbox.renew(any(), anyLong(), anyLong())).thenReturn(true);
        when(fixture.deliberation.getTurn(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChatDeliberationService.TurnView(
                        "turn-a", "request-a", "1", "conversation-a", "1", "builtin-songjiang",
                        "snapshot-a", "dispatch-a", "CHAT", "DISPATCHED", "1", "0",
                        null, null, "1", "1"));
    }

    @SuppressWarnings("unchecked")
    private static void seedCurrentScope(BusinessScope scope) {
        try {
            java.lang.reflect.Field field = TrustedTenantContext.class.getDeclaredField("CURRENT");
            field.setAccessible(true);
            ((ThreadLocal<BusinessScope>) field.get(null)).set(scope);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String currentTenant() {
        return TrustedTenantContext.currentScope()
                .map(current -> current.tenantId().value())
                .orElse("EMPTY");
    }

    private static BusinessScope scope(String tenantId, String owner, String client) {
        return new BusinessScope(TenantId.of(tenantId), DataDomainId.of("cyf"), owner, client);
    }

    private static ChatDispatchOutboxEntity row(String eventType, String tenantId, Map<String, Object> payload) {
        return new ChatDispatchOutboxEntity()
                .setEventId("event-" + eventType)
                .setTenantId(tenantId)
                .setOwnerJiacn("owner-a")
                .setClientId("client-a")
                .setTurnId("turn-a")
                .setDispatchId("dispatch-a")
                .setEventType(eventType)
                .setStatus("CLAIMED")
                .setPayloadJson(JsonUtil.toJson(payload))
                .setCreatedAt(1L);
    }

    private static Map<String, Object> payloadFor(String eventType) {
        return switch (eventType) {
            case "DISPATCH" -> dispatchPayload();
            case "CANCEL_REQUESTED" -> cancelPayload();
            case "FINAL_PERSISTED" -> finalPayload();
            default -> Map.of();
        };
    }

    private static Map<String, Object> dispatchPayload() {
        return Map.of("conversationId", "conversation-a", "conversationGeneration", "1",
                "targetAgentId", "agent-a", "route", "CHAT", "contextSnapshotId", "snapshot-a",
                "contextHash", "hash-a");
    }

    private static Map<String, Object> builtinDispatchPayload() {
        return Map.ofEntries(
                Map.entry("targetAgentId", "builtin-songjiang"), Map.entry("route", "CHAT"),
                Map.entry("conversationId", "conversation-a"), Map.entry("conversationGeneration", "1"),
                Map.entry("requestId", "request-a"), Map.entry("turnId", "turn-a"),
                Map.entry("dispatchId", "dispatch-a"), Map.entry("contextSnapshotId", "snapshot-a"),
                Map.entry("contextHash", "hash-a"), Map.entry("content", "isolated data"));
    }

    private static Map<String, Object> cancelPayload() {
        return Map.of("targetAgentId", "agent-a", "requestId", "request-a");
    }

    private static Map<String, Object> finalPayload() {
        return Map.of("conversationId", "conversation-a", "conversationGeneration", "1");
    }

    private record Fixture(ChatDeliberationOutboxService outbox, ChatDeliberationService deliberation,
            AgentWebSocketHandler sockets, BuiltinHallAgentSupport builtin, ChatConversationEventBroker broker,
            ChatConversationService conversations, ChatClient chatClient, ChatDeliberationOutboxRelay relay) { }
}
