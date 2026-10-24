package cn.jia.tenantcard;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDeliberationStates;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatDeliberationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardC07bTest {
    private static final String TENANT = "1";
    private static final String OTHER_TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String OTHER_OWNER = "owner-b";
    private static final String CLIENT = "client-a";
    private static final String OTHER_CLIENT = "client-b";
    private static final String CONVERSATION = "41";
    private static final String REQUEST = "request-1";
    private static final String TURN = "turn-1";
    private static final long GENERATION = 3L;

    private ChatDeliberationDao deliberationDao;
    private ChatConversationDao conversationDao;
    private ChatDeliberationService service;

    @BeforeEach
    void setUp() {
        deliberationDao = mock(ChatDeliberationDao.class);
        conversationDao = mock(ChatConversationDao.class);
        service = new ChatDeliberationService(
                deliberationDao, conversationDao, mock(ChatMessageDao.class), mock(AgentService.class));
    }

    @Test
    void tenantOnePublicRequestAndTurnReadsReturnRealResultsThroughCompleteScopes() {
        ChatRequestEntity request = request();
        ChatTurnEntity turn = turn();
        when(deliberationDao.findRequest(TENANT, OWNER, CLIENT, REQUEST)).thenReturn(request);
        when(deliberationDao.findTurnsByRequest(TENANT, OWNER, CLIENT, REQUEST)).thenReturn(List.of(turn));
        when(deliberationDao.findTurn(TENANT, OWNER, CLIENT, TURN)).thenReturn(turn);
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(conversation(TENANT, GENERATION));

        ChatDeliberationService.RequestView requestView =
                service.getRequest(TENANT, OWNER, CLIENT, REQUEST);
        ChatDeliberationService.TurnView turnView = service.getTurn(TENANT, OWNER, CLIENT, TURN);

        assertEquals(REQUEST, requestView.requestId());
        assertEquals(TURN, requestView.turns().getFirst().turnId());
        assertEquals(TURN, turnView.turnId());
        ArgumentCaptor<ConversationDataScope> scopes =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao, org.mockito.Mockito.times(2))
                .findScopedById(scopes.capture(), eq(CONVERSATION));
        scopes.getAllValues().forEach(scope -> assertCompleteScope(scope, TENANT, OWNER, CLIENT));
        verify(conversationDao, never()).lockScopedById(any(ConversationDataScope.class), anyString());
        verifyLegacyFindUnused();
    }

    @Test
    void tenantZeroCannotSeeTenantOneIdsEvenWhenUpstreamRowsReuseTheIds() {
        when(deliberationDao.findRequest(OTHER_TENANT, OWNER, CLIENT, REQUEST)).thenReturn(request());
        when(deliberationDao.findTurn(OTHER_TENANT, OWNER, CLIENT, TURN)).thenReturn(turn());
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(null);

        assertDenied(() -> service.getRequest(OTHER_TENANT, OWNER, CLIENT, REQUEST));
        assertDenied(() -> service.getTurn(OTHER_TENANT, OWNER, CLIENT, TURN));

        ArgumentCaptor<ConversationDataScope> scopes =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao, org.mockito.Mockito.times(2))
                .findScopedById(scopes.capture(), eq(CONVERSATION));
        scopes.getAllValues().forEach(scope -> assertCompleteScope(scope, OTHER_TENANT, OWNER, CLIENT));
        verifyNoMutationSideEffects();
        verifyLegacyFindUnused();
    }

    @Test
    void sameTenantOtherOwnerOrClientIsDeniedBeforeTurnsOrWrites() {
        when(deliberationDao.findRequest(TENANT, OWNER, OTHER_CLIENT, REQUEST)).thenReturn(request());
        when(deliberationDao.findTurn(TENANT, OTHER_OWNER, CLIENT, TURN)).thenReturn(turn());
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(null);

        assertDenied(() -> service.getRequest(TENANT, OWNER, OTHER_CLIENT, REQUEST));
        assertDenied(() -> service.getTurn(TENANT, OTHER_OWNER, CLIENT, TURN));

        ArgumentCaptor<ConversationDataScope> scopes =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao, org.mockito.Mockito.times(2))
                .findScopedById(scopes.capture(), eq(CONVERSATION));
        assertCompleteScope(scopes.getAllValues().get(0), TENANT, OWNER, OTHER_CLIENT);
        assertCompleteScope(scopes.getAllValues().get(1), TENANT, OTHER_OWNER, CLIENT);
        verify(deliberationDao, never()).findTurnsByRequest(anyString(), anyString(), anyString(), anyString());
        verifyNoMutationSideEffects();
        verifyLegacyFindUnused();
    }

    @Test
    void staleGenerationAndDeletedConversationCannotBeReadOrReachMutationDaos() {
        when(deliberationDao.findTurn(TENANT, OWNER, CLIENT, TURN)).thenReturn(turn());
        when(deliberationDao.findRequest(TENANT, OWNER, CLIENT, REQUEST)).thenReturn(request());
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(conversation(TENANT, GENERATION + 1),
                        conversation(TENANT, GENERATION).setDeletedAt(9L),
                        conversation(TENANT, GENERATION + 1),
                        conversation(TENANT, GENERATION).setDeletedAt(9L));

        assertDenied(() -> service.getTurn(TENANT, OWNER, CLIENT, TURN));
        assertDenied(() -> service.getTurn(TENANT, OWNER, CLIENT, TURN));
        assertDenied(() -> service.getRequest(TENANT, OWNER, CLIENT, REQUEST));
        assertDenied(() -> service.getRequest(TENANT, OWNER, CLIENT, REQUEST));

        verify(deliberationDao, never()).findTurnsByRequest(anyString(), anyString(), anyString(), anyString());
        verifyNoMutationSideEffects();
        verifyLegacyFindUnused();
    }

    @Test
    void tenantOneCancelTurnUsesReadScopeThenExistingConversationLockAndCancels() {
        ChatTurnEntity turn = turn();
        ChatRequestEntity request = request();
        when(deliberationDao.findTurn(TENANT, OWNER, CLIENT, TURN)).thenReturn(turn);
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(conversation(TENANT, GENERATION));
        when(conversationDao.lockScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(conversation(TENANT, GENERATION));
        when(deliberationDao.lockTurn(TENANT, OWNER, CLIENT, TURN)).thenReturn(turn);
        prepareCancellationPersistence(request, turn);

        ChatDeliberationService.TurnView result =
                service.cancelTurn(TENANT, OWNER, CLIENT, TURN, 0L, "user cancel");

        assertEquals(ChatDeliberationStates.CANCELLED, result.state());
        assertEquals("user cancel", result.terminalReason());
        verify(deliberationDao).updateTurnState(eq(turn), eq(ChatDeliberationStates.CANCELLED),
                eq("user cancel"), anyLong());
        verify(deliberationDao).insertEvent(any(ChatConversationEventEntity.class));
        ArgumentCaptor<ConversationDataScope> readScope =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        ArgumentCaptor<ConversationDataScope> lockScope =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao).findScopedById(readScope.capture(), eq(CONVERSATION));
        verify(conversationDao).lockScopedById(lockScope.capture(), eq(CONVERSATION));
        assertCompleteScope(readScope.getValue(), TENANT, OWNER, CLIENT);
        assertCompleteScope(lockScope.getValue(), TENANT, OWNER, CLIENT);
        verifyLegacyFindUnused();
    }

    @Test
    void tenantOneCancelPendingKeepsExistingLockPathAndExactDaoParameters() {
        ChatTurnEntity turn = turn();
        ChatRequestEntity request = request();
        when(deliberationDao.findRequest(TENANT, OWNER, CLIENT, REQUEST)).thenReturn(request);
        when(deliberationDao.findTurnsByRequest(TENANT, OWNER, CLIENT, REQUEST)).thenReturn(List.of(turn));
        when(conversationDao.lockScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(conversation(TENANT, GENERATION));
        when(deliberationDao.lockTurn(TENANT, OWNER, CLIENT, TURN)).thenReturn(turn);
        prepareCancellationPersistence(request, turn);

        ChatDeliberationService.RequestView result = service.cancelPending(
                TENANT, OWNER, CLIENT, REQUEST, List.of(TURN), "pending cancel");

        assertEquals(ChatDeliberationStates.CANCELLED, result.turns().getFirst().state());
        verify(deliberationDao).lockTurn(TENANT, OWNER, CLIENT, TURN);
        verify(deliberationDao).updateTurnState(eq(turn), eq(ChatDeliberationStates.CANCELLED),
                eq("pending cancel"), anyLong());
        ArgumentCaptor<ConversationDataScope> scope =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao).lockScopedById(scope.capture(), eq(CONVERSATION));
        assertCompleteScope(scope.getValue(), TENANT, OWNER, CLIENT);
        verify(conversationDao, never()).findScopedById(any(ConversationDataScope.class), anyString());
        verifyLegacyFindUnused();
    }

    @Test
    void staleTurnAndDeletedRequestCancellationCannotWriteEventsOrResurrectState() {
        when(deliberationDao.findTurn(TENANT, OWNER, CLIENT, TURN)).thenReturn(turn());
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(conversation(TENANT, GENERATION + 1));
        assertDenied(() -> service.cancelTurn(TENANT, OWNER, CLIENT, TURN, null, "cancel"));

        clearInvocations(deliberationDao, conversationDao);
        when(deliberationDao.findRequest(TENANT, OWNER, CLIENT, REQUEST)).thenReturn(request());
        when(conversationDao.lockScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(conversation(TENANT, GENERATION).setDeletedAt(9L));
        assertDenied(() -> service.cancelPending(TENANT, OWNER, CLIENT, REQUEST, List.of(), "cancel"));

        verify(deliberationDao, never()).lockTurn(anyString(), anyString(), anyString(), anyString());
        verifyNoMutationSideEffects();
        verifyLegacyFindUnused();
    }

    @Test
    void replayRemainsReadOnlyAndUsesTheSameCompleteScope() {
        ChatConversationEventEntity event = new ChatConversationEventEntity().setEventId("event-1");
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(conversation(TENANT, GENERATION));
        when(deliberationDao.replayEvents(TENANT, OWNER, CLIENT, CONVERSATION,
                GENERATION, 0L, 7L, 10)).thenReturn(List.of(event));

        assertEquals(List.of(event), service.replayEventsThrough(
                TENANT, OWNER, CLIENT, CONVERSATION, GENERATION, 0L, 7L, 10));

        ArgumentCaptor<ConversationDataScope> scope =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao).findScopedById(scope.capture(), eq(CONVERSATION));
        assertCompleteScope(scope.getValue(), TENANT, OWNER, CLIENT);
        verify(conversationDao, never()).lockScopedById(any(ConversationDataScope.class), anyString());
        verifyNoMutationSideEffects();
        verifyLegacyFindUnused();
    }

    private void prepareCancellationPersistence(ChatRequestEntity request, ChatTurnEntity turn) {
        when(deliberationDao.updateTurnState(eq(turn), eq(ChatDeliberationStates.CANCELLED),
                anyString(), anyLong())).thenReturn(1);
        doAnswer(invocation -> {
            ChatConversationEventEntity event = invocation.getArgument(0);
            event.setEventSequence(17L);
            return 1;
        }).when(deliberationDao).insertEvent(any(ChatConversationEventEntity.class));
        when(deliberationDao.assignEventVersion(17L)).thenReturn(1);
        when(deliberationDao.insertOutbox(any())).thenReturn(1);
        when(deliberationDao.findRequest(TENANT, OWNER, CLIENT, REQUEST)).thenReturn(request);
        when(deliberationDao.findTurnsByRequest(TENANT, OWNER, CLIENT, REQUEST)).thenReturn(List.of(turn));
        when(deliberationDao.updateRequestState(eq(request), anyString(), anyLong())).thenReturn(1);
    }

    private ChatRequestEntity request() {
        return new ChatRequestEntity()
                .setTenantId(TENANT).setOwnerJiacn(OWNER).setClientId(CLIENT)
                .setRequestId(REQUEST).setRequestRevision(1L)
                .setConversationId(CONVERSATION).setConversationGeneration(GENERATION)
                .setUserMessageId(51L).setAggregateState(ChatDeliberationStates.RUNNING)
                .setStateVersion(0L).setCreatedAt(10L).setUpdatedAt(11L);
    }

    private ChatTurnEntity turn() {
        return new ChatTurnEntity()
                .setTurnId(TURN).setTenantId(TENANT).setOwnerJiacn(OWNER).setClientId(CLIENT)
                .setRequestId(REQUEST).setRequestRevision(1L)
                .setConversationId(CONVERSATION).setConversationGeneration(GENERATION)
                .setTargetAgentId("agent-a").setSnapshotId("snapshot-1")
                .setContextDigest("digest-1").setDispatchId("dispatch-1")
                .setRoute(InteractionRoute.CHAT.name()).setState(ChatDeliberationStates.RECEIVED)
                .setStateVersion(0L).setLastDeltaSeq(0L).setCreatedAt(10L).setUpdatedAt(11L);
    }

    private ChatConversationEntity conversation(String tenant, long generation) {
        ChatConversationEntity row = new ChatConversationEntity()
                .setId(Long.valueOf(CONVERSATION)).setJiacn(OWNER)
                .setLifecycleGeneration(generation);
        row.setTenantId(tenant);
        row.setClientId(CLIENT);
        return row;
    }

    private void assertCompleteScope(
            ConversationDataScope scope, String tenant, String owner, String client) {
        assertEquals(tenant, scope.tenantId());
        assertEquals("cyf", scope.dataDomainId());
        assertEquals(owner, scope.ownerJiacn());
        assertEquals(client, scope.callerClientId());
        assertEquals(ConversationDataScope.Mode.LEGACY_CLIENT, scope.mode());
    }

    private void verifyNoMutationSideEffects() {
        verify(deliberationDao, never()).updateTurnState(any(), anyString(), anyString(), anyLong());
        verify(deliberationDao, never()).insertEvent(any());
        verify(deliberationDao, never()).insertOutbox(any());
        verify(deliberationDao, never()).updateRequestState(any(), anyString(), anyLong());
    }

    private void verifyLegacyFindUnused() {
        verify(conversationDao, never()).findScopedById(anyString(), anyString(), anyString());
    }

    private void assertDenied(ThrowingAction action) {
        ChatDeliberationException failure =
                assertThrows(ChatDeliberationException.class, action::run);
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, failure.reason());
        assertEquals("Chat request is unavailable", failure.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
