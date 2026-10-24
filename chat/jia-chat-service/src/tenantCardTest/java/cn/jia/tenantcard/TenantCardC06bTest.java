package cn.jia.tenantcard;

import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import cn.jia.chat.service.impl.WorkspaceConversationAccessServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantCardC06bTest {
    private static final String TENANT = "1";
    private static final String OTHER_TENANT = "0";
    private static final String CLIENT = "client-a";
    private static final String OTHER_CLIENT = "client-b";
    private static final String OWNER = "owner-a";
    private static final String OTHER_OWNER = "owner-b";

    private ChatConversationDao conversationDao;
    private AgentTaskThreadDao taskThreadDao;
    private WorkspaceConversationAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        conversationDao = mock(ChatConversationDao.class);
        taskThreadDao = mock(AgentTaskThreadDao.class);
        service = new WorkspaceConversationAccessServiceImpl(conversationDao, taskThreadDao);
    }

    @Test
    void tenantOneReturnsAuthoritativeViewAndUsesCompleteLegacyClientScope() {
        ChatConversationEntity row = privateConversation(41L, TENANT, OWNER, CLIENT);
        when(conversationDao.findScopedById(expectedScope(TENANT, OWNER, CLIENT), "41"))
                .thenReturn(row);

        WorkspaceConversationAccessService.ConversationView view =
                service.requireAccessible(requestScope(TENANT, OWNER, CLIENT), "41");

        assertNotNull(view);
        assertEquals("41", view.conversationId());
        assertEquals("private", view.scopeType());
        assertEquals("agent:agent-a", view.scopeKey());
        assertNull(view.taskId());
        assertEquals(List.of("agent-a"), view.targetAgentIds());
        assertEquals(3L, view.lifecycleGeneration());
        assertEquals(17L, view.contextRevision());

        ArgumentCaptor<ConversationDataScope> scopeCaptor =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao).findScopedById(scopeCaptor.capture(), eq("41"));
        assertCompleteScope(scopeCaptor.getValue(), TENANT, OWNER, CLIENT);
        verify(taskThreadDao).findAnyByConversationId("41");
        verifyLegacyOverloadUnused();
    }

    @Test
    void tenantZeroRemainsAccessibleThroughTheCompleteScopePath() {
        ChatConversationEntity row = conversation(42L, OTHER_TENANT, OWNER, CLIENT)
                .setConversationScopeType("bounty")
                .setConversationScopeKey("task:task-7")
                .setTaskId("task-7")
                .setTargetAgentIds("[\"agent-a\",\"agent-b\"]");
        when(conversationDao.findScopedById(expectedScope(OTHER_TENANT, OWNER, CLIENT), "42"))
                .thenReturn(row);

        WorkspaceConversationAccessService.ConversationView view =
                service.requireAccessible(requestScope(OTHER_TENANT, OWNER, CLIENT), "42");

        assertNotNull(view);
        assertEquals("task-7", view.taskId());
        assertEquals(List.of("agent-a", "agent-b"), view.targetAgentIds());
        ArgumentCaptor<ConversationDataScope> scopeCaptor =
                ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversationDao).findScopedById(scopeCaptor.capture(), eq("42"));
        assertCompleteScope(scopeCaptor.getValue(), OTHER_TENANT, OWNER, CLIENT);
        verifyLegacyOverloadUnused();
    }

    @Test
    void malformedTenantNullScopeOwnerZeroAndInexactIdentitiesFailBeforeDataAccess() {
        assertDenied(() -> service.requireAccessible(null, "41"));
        for (WorkspaceConversationAccessService.Scope malformed : List.of(
                requestScope(null, OWNER, CLIENT),
                requestScope("01", OWNER, CLIENT),
                requestScope("tenant-a", OWNER, CLIENT),
                requestScope(TENANT, "0", CLIENT),
                requestScope(TENANT, " owner-a", CLIENT),
                requestScope(TENANT, OWNER, "client-a\0"))) {
            assertDenied(() -> service.requireAccessible(malformed, "41"));
        }

        verifyNoInteractions(conversationDao, taskThreadDao);
    }

    @Test
    void missingCrossTenantCrossOwnerCrossClientAndDeletedRowsHaveOneDenial() {
        ConversationDataScope scope = expectedScope(TENANT, OWNER, CLIENT);
        when(conversationDao.findScopedById(scope, "51")).thenReturn(null);
        when(conversationDao.findScopedById(scope, "52"))
                .thenReturn(conversation(52L, OTHER_TENANT, OWNER, CLIENT));
        when(conversationDao.findScopedById(scope, "53"))
                .thenReturn(conversation(53L, TENANT, OTHER_OWNER, CLIENT));
        when(conversationDao.findScopedById(scope, "54"))
                .thenReturn(conversation(54L, TENANT, OWNER, OTHER_CLIENT));
        when(conversationDao.findScopedById(scope, "55"))
                .thenReturn(conversation(55L, TENANT, OWNER, CLIENT).setDeletedAt(100L));

        for (String id : List.of("51", "52", "53", "54", "55")) {
            assertDenied(() -> service.requireAccessible(requestScope(TENANT, OWNER, CLIENT), id));
        }

        verifyNoInteractions(taskThreadDao);
        verifyLegacyOverloadUnused();
    }

    @Test
    void taskThreadMarkerAndDurableBindingBothFailClosed() {
        ConversationDataScope scope = expectedScope(TENANT, OWNER, CLIENT);
        ChatConversationEntity marked = conversation(61L, TENANT, OWNER, CLIENT)
                .setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);
        when(conversationDao.findScopedById(scope, "61")).thenReturn(marked);
        assertDenied(() -> service.requireAccessible(requestScope(TENANT, OWNER, CLIENT), "61"));

        ChatConversationEntity bound = privateConversation(62L, TENANT, OWNER, CLIENT);
        when(conversationDao.findScopedById(scope, "62")).thenReturn(bound);
        when(taskThreadDao.findAnyByConversationId("62"))
                .thenReturn(new AgentTaskThreadEntity().setConversationId("62"));
        assertDenied(() -> service.requireAccessible(requestScope(TENANT, OWNER, CLIENT), "62"));

        verify(taskThreadDao, never()).findAnyByConversationId("61");
        verify(taskThreadDao).findAnyByConversationId("62");
        verifyLegacyOverloadUnused();
    }

    @Test
    void malformedTargetsAndEveryPersistedScopeInvariantRemainDenied() {
        ConversationDataScope scope = expectedScope(TENANT, OWNER, CLIENT);
        List<Consumer<ChatConversationEntity>> corruptions = List.of(
                row -> row.setConversationScopeType("unsupported"),
                row -> row.setConversationScopeKey("task:wrong"),
                row -> row.setTaskId("task-7"),
                row -> row.setTargetAgentId("agent-b"),
                row -> row.setTargetAgentIds("not-json"),
                row -> row.setTargetAgentIds("[\"agent-a\",\"agent-a\"]"),
                row -> row.setLifecycleGeneration(0L),
                row -> row.setUpdateTime(null));

        for (int index = 0; index < corruptions.size(); index++) {
            long id = 70L + index;
            ChatConversationEntity row = privateConversation(id, TENANT, OWNER, CLIENT);
            corruptions.get(index).accept(row);
            String conversationId = Long.toString(id);
            when(conversationDao.findScopedById(scope, conversationId)).thenReturn(row);
            assertDenied(() -> service.requireAccessible(
                    requestScope(TENANT, OWNER, CLIENT), conversationId));
        }

        verifyLegacyOverloadUnused();
    }

    @Test
    void daoFailuresAreNormalizedWithoutLeakingTheirCause() {
        ConversationDataScope scope = expectedScope(TENANT, OWNER, CLIENT);
        when(conversationDao.findScopedById(scope, "81"))
                .thenThrow(new IllegalArgumentException("sensitive conversation detail"));
        assertDenied(() -> service.requireAccessible(requestScope(TENANT, OWNER, CLIENT), "81"));

        when(conversationDao.findScopedById(scope, "82"))
                .thenReturn(privateConversation(82L, TENANT, OWNER, CLIENT));
        when(taskThreadDao.findAnyByConversationId("82"))
                .thenThrow(new IllegalStateException("sensitive binding detail"));
        assertDenied(() -> service.requireAccessible(requestScope(TENANT, OWNER, CLIENT), "82"));

        verifyLegacyOverloadUnused();
    }

    private WorkspaceConversationAccessService.Scope requestScope(
            String tenant, String owner, String client) {
        return new WorkspaceConversationAccessService.Scope(tenant, client, owner);
    }

    private ConversationDataScope expectedScope(String tenant, String owner, String client) {
        return new ConversationDataScope(
                new cn.jia.core.tenant.BusinessScope(
                        cn.jia.core.tenant.TenantId.of(tenant),
                        cn.jia.core.tenant.DataDomainId.of("cyf"),
                        owner,
                        client),
                ConversationDataScope.Mode.LEGACY_CLIENT);
    }

    private ChatConversationEntity privateConversation(
            long id, String tenant, String owner, String client) {
        ChatConversationEntity row = conversation(id, tenant, owner, client)
                .setConversationScopeType("private")
                .setConversationScopeKey("agent:agent-a")
                .setTargetAgentId("agent-a")
                .setTargetAgentIds("[\"agent-a\"]")
                .setLifecycleGeneration(3L);
        row.setUpdateTime(17L);
        return row;
    }

    private ChatConversationEntity conversation(
            long id, String tenant, String owner, String client) {
        ChatConversationEntity row = new ChatConversationEntity()
                .setId(id)
                .setJiacn(owner)
                .setConversationType("juyiting")
                .setConversationScopeType("public")
                .setConversationScopeKey("public")
                .setTargetAgentIds("[\"agent-a\"]")
                .setStatus(0)
                .setLifecycleGeneration(1L);
        row.setTenantId(tenant);
        row.setClientId(client);
        row.setCreateTime(10L);
        row.setUpdateTime(11L);
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

    private void verifyLegacyOverloadUnused() {
        verify(conversationDao, never()).findScopedById(anyString(), anyString(), anyString());
    }

    private void assertDenied(ThrowingAction action) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, action::run);
        assertEquals("Workspace conversation is unavailable", failure.getMessage());
        assertNull(failure.getCause());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
