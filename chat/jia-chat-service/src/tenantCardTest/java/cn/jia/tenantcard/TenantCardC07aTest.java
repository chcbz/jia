package cn.jia.tenantcard;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatDeliberationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardC07aTest {
    private static final String TENANT = "1";
    private static final String OTHER_TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String OTHER_OWNER = "owner-b";
    private static final String CLIENT = "client-a";
    private static final String OTHER_CLIENT = "client-b";
    private static final String CONVERSATION = "41";
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
    void tenantOneWatermarkAndReplayUseExactScopeWithoutTakingMutationLock() {
        ChatConversationEntity row = conversation(TENANT, GENERATION);
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(row);
        when(deliberationDao.eventHighWatermark(TENANT, OWNER, CLIENT, CONVERSATION, GENERATION))
                .thenReturn(7L);
        List<ChatConversationEventEntity> replay = List.of(new ChatConversationEventEntity());
        when(deliberationDao.replayEvents(TENANT, OWNER, CLIENT, CONVERSATION, GENERATION, 0, 7, 10))
                .thenReturn(replay);

        assertEquals(7L, service.eventHighWatermark(TENANT, OWNER, CLIENT, CONVERSATION, GENERATION));
        assertEquals(replay, service.replayEventsThrough(
                TENANT, OWNER, CLIENT, CONVERSATION, GENERATION, 0, 7, 10));

        ArgumentCaptor<ConversationDataScope> scopes =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao, org.mockito.Mockito.times(2))
                .findScopedById(scopes.capture(), eq(CONVERSATION));
        for (ConversationDataScope scope : scopes.getAllValues()) {
            assertCompleteScope(scope, TENANT, OWNER, CLIENT);
        }
        verify(conversationDao, never()).lockScopedById(any(ConversationDataScope.class), anyString());
        verifyLegacyOverloadsUnused();
    }

    @Test
    void lockedMutationPathUsesExactTenantOneScopeAndNeverFallsBackToFind() {
        ChatConversationEntity row = conversation(TENANT, GENERATION);
        when(conversationDao.lockScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(row);

        Object result = ReflectionTestUtils.invokeMethod(service, "requireLockedConversation",
                TENANT, OWNER, CLIENT, CONVERSATION, GENERATION);

        assertEquals(row, result);
        ArgumentCaptor<ConversationDataScope> scope =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao).lockScopedById(scope.capture(), eq(CONVERSATION));
        assertCompleteScope(scope.getValue(), TENANT, OWNER, CLIENT);
        verify(conversationDao, never()).findScopedById(any(ConversationDataScope.class), anyString());
        verifyLegacyOverloadsUnused();
    }

    @Test
    void readablePathRejectsMissingCrossTenantDeletedAndStaleGenerationRows() {
        assertReadableDenied(null);
        assertReadableDenied(conversation(OTHER_TENANT, GENERATION));
        assertReadableDenied(conversation(TENANT, GENERATION).setDeletedAt(9L));
        assertReadableDenied(conversation(TENANT, GENERATION + 1));

        verifyNoInteractions(deliberationDao);
        verify(conversationDao, never()).lockScopedById(any(ConversationDataScope.class), anyString());
        verifyLegacyOverloadsUnused();
    }

    @Test
    void lockedPathRejectsMissingCrossTenantDeletedAndStaleGenerationRows() {
        assertLockedDenied(null);
        assertLockedDenied(conversation(OTHER_TENANT, GENERATION));
        assertLockedDenied(conversation(TENANT, GENERATION).setDeletedAt(9L));
        assertLockedDenied(conversation(TENANT, GENERATION + 1));

        verify(conversationDao, never()).findScopedById(any(ConversationDataScope.class), anyString());
        verifyLegacyOverloadsUnused();
    }

    @Test
    void tenantZeroCompatibilityStillUsesCompleteScope() {
        ChatConversationEntity row = conversation(OTHER_TENANT, GENERATION);
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(row);
        when(deliberationDao.eventHighWatermark(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, GENERATION)).thenReturn(5L);

        assertEquals(5L, service.eventHighWatermark(
                OTHER_TENANT, OWNER, CLIENT, CONVERSATION, GENERATION));

        ArgumentCaptor<ConversationDataScope> scope =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao).findScopedById(scope.capture(), eq(CONVERSATION));
        assertCompleteScope(scope.getValue(), OTHER_TENANT, OWNER, CLIENT);
        verifyLegacyOverloadsUnused();
    }

    @Test
    void sameTenantOtherOwnerAndClientAreDeniedThroughTheirExactReadAndLockScopes() {
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(null);

        assertDenied(() -> service.eventHighWatermark(
                TENANT, OTHER_OWNER, CLIENT, CONVERSATION, GENERATION));
        assertDenied(() -> service.replayEventsThrough(
                TENANT, OWNER, OTHER_CLIENT, CONVERSATION, GENERATION, 0, 7, 10));

        ArgumentCaptor<ConversationDataScope> readScopes =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao, org.mockito.Mockito.times(2))
                .findScopedById(readScopes.capture(), eq(CONVERSATION));
        assertCompleteScope(readScopes.getAllValues().get(0), TENANT, OTHER_OWNER, CLIENT);
        assertCompleteScope(readScopes.getAllValues().get(1), TENANT, OWNER, OTHER_CLIENT);

        when(conversationDao.lockScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(null);
        assertDenied(() -> ReflectionTestUtils.invokeMethod(service, "requireLockedConversation",
                TENANT, OTHER_OWNER, CLIENT, CONVERSATION, GENERATION));
        assertDenied(() -> ReflectionTestUtils.invokeMethod(service, "requireLockedConversation",
                TENANT, OWNER, OTHER_CLIENT, CONVERSATION, GENERATION));

        ArgumentCaptor<ConversationDataScope> lockScopes =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao, org.mockito.Mockito.times(2))
                .lockScopedById(lockScopes.capture(), eq(CONVERSATION));
        assertCompleteScope(lockScopes.getAllValues().get(0), TENANT, OTHER_OWNER, CLIENT);
        assertCompleteScope(lockScopes.getAllValues().get(1), TENANT, OWNER, OTHER_CLIENT);
        verifyNoInteractions(deliberationDao);
        verifyLegacyOverloadsUnused();
    }

    @Test
    void malformedCanonicalTenantFailsClosedBeforeAnyDaoCall() {
        assertDenied(() -> service.eventHighWatermark("01", OWNER, CLIENT, CONVERSATION, GENERATION));
        assertDenied(() -> ReflectionTestUtils.invokeMethod(service, "requireLockedConversation",
                "tenant-a", OWNER, CLIENT, CONVERSATION, GENERATION));

        verifyNoInteractions(conversationDao, deliberationDao);
    }

    @Test
    void publicReplayEntrypointsRetainReadOnlyTransactionDeclarations() throws Exception {
        assertReadOnly("eventHighWatermark", String.class, String.class, String.class,
                String.class, long.class);
        assertReadOnly("replayEvents", String.class, String.class, String.class,
                String.class, long.class, long.class, int.class);
        assertReadOnly("replayEventsThrough", String.class, String.class, String.class,
                String.class, long.class, long.class, long.class, int.class);
    }

    private void assertReadableDenied(ChatConversationEntity row) {
        clearInvocations(conversationDao);
        when(conversationDao.findScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(row);
        assertDenied(() -> service.eventHighWatermark(
                TENANT, OWNER, CLIENT, CONVERSATION, GENERATION));
        ArgumentCaptor<ConversationDataScope> scope =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao).findScopedById(scope.capture(), eq(CONVERSATION));
        assertCompleteScope(scope.getValue(), TENANT, OWNER, CLIENT);
    }

    private void assertLockedDenied(ChatConversationEntity row) {
        clearInvocations(conversationDao);
        when(conversationDao.lockScopedById(any(ConversationDataScope.class), eq(CONVERSATION)))
                .thenReturn(row);
        assertDenied(() -> ReflectionTestUtils.invokeMethod(service, "requireLockedConversation",
                TENANT, OWNER, CLIENT, CONVERSATION, GENERATION));
        ArgumentCaptor<ConversationDataScope> scope =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao).lockScopedById(scope.capture(), eq(CONVERSATION));
        assertCompleteScope(scope.getValue(), TENANT, OWNER, CLIENT);
    }

    private void assertReadOnly(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ChatDeliberationService.class.getMethod(methodName, parameterTypes);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertFalse(annotation.value().length() > 0);
        assertEquals(true, annotation.readOnly());
    }

    private ChatConversationEntity conversation(String tenant, long generation) {
        ChatConversationEntity row = new ChatConversationEntity()
                .setId(Long.valueOf(CONVERSATION))
                .setJiacn(OWNER)
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

    private void verifyLegacyOverloadsUnused() {
        verify(conversationDao, never()).findScopedById(anyString(), anyString(), anyString());
        verify(conversationDao, never()).lockScopedById(anyString(), anyString(), anyString());
    }

    private void assertDenied(ThrowingAction action) {
        ChatDeliberationException denied =
                assertThrows(ChatDeliberationException.class, action::run);
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, denied.reason());
        assertEquals("Chat request is unavailable", denied.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
