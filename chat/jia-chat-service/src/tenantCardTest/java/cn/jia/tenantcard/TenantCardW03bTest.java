package cn.jia.tenantcard;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.ChatDeliberationOutboxRelay;
import cn.jia.chat.service.ChatDeliberationOutboxService;
import cn.jia.chat.service.ChatDeliberationService;
import cn.jia.chat.service.ChatWorkerTenantPolicy;
import cn.jia.chat.service.impl.ChatConversationServiceImpl;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardW03bTest {
    @Test
    void trustedTenantOneProbeBuildsCompleteLegacyClientScope() {
        ChatConversationDao dao = mock(ChatConversationDao.class);
        ChatConversationServiceImpl service = service(dao);
        when(dao.isLiveGeneration(any(ConversationDataScope.class), eq("101"), eq(2L)))
                .thenAnswer(invocation -> {
                    ConversationDataScope scope = invocation.getArgument(0);
                    return "1".equals(scope.tenantId()) && "cyf".equals(scope.dataDomainId())
                            && "owner-a".equals(scope.ownerJiacn())
                            && "client-a".equals(scope.callerClientId())
                            && scope.mode() == ConversationDataScope.Mode.LEGACY_CLIENT;
                });

        assertTrue(service.isLiveGeneration("1", "owner-a", "client-a", "101", 2L));

        ArgumentCaptor<ConversationDataScope> scope = ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(dao).isLiveGeneration(scope.capture(), eq("101"), eq(2L));
        assertEquals("1", scope.getValue().tenantId());
        assertEquals("owner-a", scope.getValue().ownerJiacn());
        assertEquals("client-a", scope.getValue().callerClientId());
        assertEquals(ConversationDataScope.Mode.LEGACY_CLIENT, scope.getValue().mode());
    }

    @Test
    void tenantOwnerAndClientVariationsRemainIsolatedAtDaoBoundary() {
        ChatConversationDao dao = mock(ChatConversationDao.class);
        ChatConversationServiceImpl service = service(dao);
        when(dao.isLiveGeneration(any(ConversationDataScope.class), eq("101"), eq(1L)))
                .thenAnswer(invocation -> {
                    ConversationDataScope scope = invocation.getArgument(0);
                    return "1".equals(scope.tenantId()) && "owner-a".equals(scope.ownerJiacn())
                            && "client-a".equals(scope.callerClientId());
                });

        assertTrue(service.isLiveGeneration("1", "owner-a", "client-a", "101", 1L));
        assertFalse(service.isLiveGeneration("0", "owner-a", "client-a", "101", 1L));
        assertFalse(service.isLiveGeneration("1", "owner-b", "client-a", "101", 1L));
        assertFalse(service.isLiveGeneration("1", "owner-a", "client-b", "101", 1L));
    }

    @Test
    void invalidTenantConversationOrGenerationFailsWithoutDaoAccess() {
        ChatConversationDao dao = mock(ChatConversationDao.class);
        ChatConversationServiceImpl service = service(dao);

        assertFalse(service.isLiveGeneration("01", "owner-a", "client-a", "101", 1L));
        assertFalse(service.isLiveGeneration("1", "owner-a", "client-a", "event-101", 1L));
        assertFalse(service.isLiveGeneration("1", "owner-a", "client-a", "0", 1L));
        assertFalse(service.isLiveGeneration("1", "owner-a", "client-a", "101", 0L));

        verifyNoInteractions(dao);
    }

    @Test
    void legacyFourArgumentProbeExplicitlyUsesTenantZero() {
        ChatConversationDao dao = mock(ChatConversationDao.class);
        ChatConversationServiceImpl service = service(dao);
        when(dao.isLiveGeneration(any(ConversationDataScope.class), eq("101"), eq(1L)))
                .thenAnswer(invocation -> "0".equals(
                        ((ConversationDataScope) invocation.getArgument(0)).tenantId()));

        assertTrue(service.isLiveGeneration("owner-a", "client-a", "101", 1L));

        ArgumentCaptor<ConversationDataScope> scope = ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(dao).isLiveGeneration(scope.capture(), eq("101"), eq(1L));
        assertEquals("0", scope.getValue().tenantId());
    }

    @Test
    void hostedFinalAndPersistedRelayCallbacksPassTenantOneAndExecuteProbe() {
        RelayFixture fixture = relayFixture();
        when(fixture.builtin.isBuiltinAgent("agent-a")).thenReturn(false);
        when(fixture.conversations.isLiveGeneration("1", "owner-a", "client-a", "101", 1L))
                .thenReturn(true);
        when(fixture.sockets.sendNegotiatedChatMessageToAgent(eq("1"), eq("owner-a"), eq("client-a"),
                eq("agent-a"), eq(InteractionRoute.CHAT), any())).thenReturn(
                new AgentWebSocketHandler.CapabilityDispatchResult(
                        AgentWebSocketHandler.CapabilityDispatchStatus.READY, true, Map.of()));

        deliver(fixture.relay, row("DISPATCH", dispatchPayload()));
        verify(fixture.conversations).isLiveGeneration("1", "owner-a", "client-a", "101", 1L);

        clearInvocations(fixture.conversations);
        AtomicInteger finalEvents = new AtomicInteger();
        ChatConversationEventBroker.LiveSubscription finalSubscription = fixture.broker.subscribeBuffered(
                "101", 1L, () -> true);
        Disposable finalListener = finalSubscription.flux().subscribe(event -> finalEvents.incrementAndGet());
        try {
            deliver(fixture.relay, row("FINAL_PERSISTED", finalPayload()));
        } finally {
            finalListener.dispose();
            finalSubscription.close();
        }
        assertEquals(1, finalEvents.get());
        verify(fixture.conversations).isLiveGeneration("1", "owner-a", "client-a", "101", 1L);

        clearInvocations(fixture.conversations);
        AtomicInteger persistedEvents = new AtomicInteger();
        ChatConversationEventBroker.LiveSubscription persistedSubscription = fixture.broker.subscribeBuffered(
                "101", 1L, () -> true);
        Disposable persistedListener = persistedSubscription.flux().subscribe(event -> persistedEvents.incrementAndGet());
        try {
            ReflectionTestUtils.invokeMethod(fixture.relay, "publishPersistedEvent", persistedEvent());
        } finally {
            persistedListener.dispose();
            persistedSubscription.close();
        }
        assertEquals(1, persistedEvents.get());
        verify(fixture.conversations).isLiveGeneration("1", "owner-a", "client-a", "101", 1L);
        verify(fixture.conversations, never()).isLiveGeneration("owner-a", "client-a", "101", 1L);
    }

    private static ChatConversationServiceImpl service(ChatConversationDao dao) {
        return new ChatConversationServiceImpl(dao, mock(ChatMessageDao.class), mock(AgentTaskThreadDao.class),
                mock(ChatConversationEventBroker.class), mock(ChatBountyBindingStore.class), mock(AgentService.class));
    }

    private static RelayFixture relayFixture() {
        ChatDeliberationOutboxService outbox = mock(ChatDeliberationOutboxService.class);
        ChatDeliberationService deliberation = mock(ChatDeliberationService.class);
        AgentWebSocketHandler sockets = mock(AgentWebSocketHandler.class);
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        ChatConversationService conversations = mock(ChatConversationService.class);
        ChatDeliberationOutboxRelay relay = new ChatDeliberationOutboxRelay(outbox, deliberation, sockets, builtin,
                broker, conversations, mock(ChatClient.class), new ChatWorkerTenantPolicy(Set.of("1")));
        return new RelayFixture(sockets, builtin, broker, conversations, relay);
    }

    private static void deliver(ChatDeliberationOutboxRelay relay, ChatDispatchOutboxEntity row) {
        ReflectionTestUtils.invokeMethod(relay, "deliverClaim", new ChatDeliberationOutboxService.Claim(row, false));
    }

    private static ChatDispatchOutboxEntity row(String eventType, Map<String, Object> payload) {
        return new ChatDispatchOutboxEntity().setEventId("event-" + eventType).setTenantId("1")
                .setOwnerJiacn("owner-a").setClientId("client-a").setTurnId("turn-a")
                .setDispatchId("dispatch-a").setEventType(eventType).setStatus("CLAIMED")
                .setPayloadJson(JsonUtil.toJson(payload)).setCreatedAt(1L);
    }

    private static Map<String, Object> dispatchPayload() {
        return Map.of("conversationId", "101", "conversationGeneration", "1", "targetAgentId", "agent-a",
                "route", "CHAT", "contextSnapshotId", "snapshot-a", "contextHash", "hash-a");
    }

    private static Map<String, Object> finalPayload() {
        return Map.of("conversationId", "101", "conversationGeneration", "1");
    }

    private static ChatConversationEventEntity persistedEvent() {
        return new ChatConversationEventEntity().setEventId("event-persisted").setEventSequence(1L)
                .setEventVersion(1L).setOccurredAt(1L).setTenantId("1").setOwnerJiacn("owner-a")
                .setClientId("client-a").setConversationId("101").setConversationGeneration(1L)
                .setPayloadJson(JsonUtil.toJson(Map.of("type", "agent_message")));
    }

    private record RelayFixture(AgentWebSocketHandler sockets, BuiltinHallAgentSupport builtin,
            ChatConversationEventBroker broker, ChatConversationService conversations,
            ChatDeliberationOutboxRelay relay) { }
}
