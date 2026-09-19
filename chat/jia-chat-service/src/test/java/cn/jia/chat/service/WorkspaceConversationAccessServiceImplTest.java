package cn.jia.chat.service;

import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.service.impl.WorkspaceConversationAccessServiceImpl;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceConversationAccessServiceImplTest extends BaseMockTest {
    private static final String TENANT = "0";
    private static final String CLIENT = "client-a";
    private static final String OWNER = "owner-a";
    private static final WorkspaceConversationAccessService.Scope SCOPE =
            new WorkspaceConversationAccessService.Scope(TENANT, CLIENT, OWNER);

    @Mock ChatConversationDao conversationDao;
    @Mock AgentTaskThreadDao taskThreadDao;

    @Test
    void returnsOnlyExactAuthoritativePrivateConversationFacts() {
        ChatConversationEntity conversation = conversation(41L)
                .setConversationScopeType("private")
                .setConversationScopeKey("agent:agent-a")
                .setTargetAgentId("agent-a")
                .setTargetAgentIds("[\"agent-a\"]")
                .setLifecycleGeneration(3L);
        conversation.setUpdateTime(17L);
        when(conversationDao.findScopedById(OWNER, CLIENT, "41")).thenReturn(conversation);

        WorkspaceConversationAccessService.ConversationView view =
                service().requireAccessible(SCOPE, "41");

        assertEquals("41", view.conversationId());
        assertEquals("private", view.scopeType());
        assertEquals("agent:agent-a", view.scopeKey());
        assertNull(view.taskId());
        assertEquals(List.of("agent-a"), view.targetAgentIds());
        assertEquals(3L, view.lifecycleGeneration());
        assertEquals(17L, view.contextRevision());
        assertThrows(UnsupportedOperationException.class,
                () -> view.targetAgentIds().add("agent-b"));
        assertEquals(List.of(
                        "conversationId", "scopeType", "scopeKey", "taskId", "targetAgentIds",
                        "lifecycleGeneration", "contextRevision"),
                Arrays.stream(WorkspaceConversationAccessService.ConversationView.class
                                .getRecordComponents())
                        .map(component -> component.getName()).toList());
        verify(conversationDao).findScopedById(OWNER, CLIENT, "41");
        verify(taskThreadDao).findAnyByConversationId("41");
    }

    @Test
    void returnsCanonicalBountyFactsWithoutUsingBrowserMetadata() {
        ChatConversationEntity conversation = conversation(42L)
                .setConversationScopeType("bounty")
                .setConversationScopeKey("task:task-7")
                .setTaskId("task-7")
                .setTargetAgentIds("[\"agent-a\",\"agent-b\"]");
        when(conversationDao.findScopedById(OWNER, CLIENT, "42")).thenReturn(conversation);

        WorkspaceConversationAccessService.ConversationView view =
                service().requireAccessible(SCOPE, "42");

        assertEquals("task-7", view.taskId());
        assertEquals(List.of("agent-a", "agent-b"), view.targetAgentIds());
        assertEquals(11L, view.contextRevision());
    }

    @Test
    void malformedScopeOrConversationIdFailsBeforeDao() {
        List<WorkspaceConversationAccessService.Scope> malformedScopes = List.of(
                new WorkspaceConversationAccessService.Scope("tenant-a", CLIENT, OWNER),
                new WorkspaceConversationAccessService.Scope(TENANT, " client-a", OWNER),
                new WorkspaceConversationAccessService.Scope(TENANT, CLIENT, "owner-a\0"),
                new WorkspaceConversationAccessService.Scope(TENANT, CLIENT, TENANT));
        for (WorkspaceConversationAccessService.Scope malformed : malformedScopes) {
            assertDenied(() -> service().requireAccessible(malformed, "41"));
        }
        for (String malformedId : List.of("0", "01", " 41", "41\0", "not-a-number")) {
            assertDenied(() -> service().requireAccessible(SCOPE, malformedId));
        }
        verify(conversationDao, never()).findScopedById(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(taskThreadDao, never()).findAnyByConversationId(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingForeignDeletedAndNonJuyitingRowsAreIndistinguishable() {
        when(conversationDao.findScopedById(OWNER, CLIENT, "51")).thenReturn(null);
        assertDenied(() -> service().requireAccessible(SCOPE, "51"));

        ChatConversationEntity foreignOwner = conversation(52L).setJiacn("owner-b");
        when(conversationDao.findScopedById(OWNER, CLIENT, "52")).thenReturn(foreignOwner);
        assertDenied(() -> service().requireAccessible(SCOPE, "52"));

        ChatConversationEntity foreignClient = conversation(53L);
        foreignClient.setClientId("client-b");
        when(conversationDao.findScopedById(OWNER, CLIENT, "53")).thenReturn(foreignClient);
        assertDenied(() -> service().requireAccessible(SCOPE, "53"));

        ChatConversationEntity foreignTenant = conversation(54L);
        foreignTenant.setTenantId("tenant-b");
        when(conversationDao.findScopedById(OWNER, CLIENT, "54")).thenReturn(foreignTenant);
        assertDenied(() -> service().requireAccessible(SCOPE, "54"));

        ChatConversationEntity wrongId = conversation(999L);
        when(conversationDao.findScopedById(OWNER, CLIENT, "55")).thenReturn(wrongId);
        assertDenied(() -> service().requireAccessible(SCOPE, "55"));

        ChatConversationEntity deleted = conversation(56L).setDeletedAt(100L);
        when(conversationDao.findScopedById(OWNER, CLIENT, "56")).thenReturn(deleted);
        assertDenied(() -> service().requireAccessible(SCOPE, "56"));

        ChatConversationEntity normal = conversation(57L).setConversationType("normal");
        when(conversationDao.findScopedById(OWNER, CLIENT, "57")).thenReturn(normal);
        assertDenied(() -> service().requireAccessible(SCOPE, "57"));

        verify(taskThreadDao, never()).findAnyByConversationId(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void taskThreadMarkerOrBindingAlwaysFailsClosed() {
        ChatConversationEntity marked = conversation(61L)
                .setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);
        when(conversationDao.findScopedById(OWNER, CLIENT, "61")).thenReturn(marked);
        assertDenied(() -> service().requireAccessible(SCOPE, "61"));

        ChatConversationEntity corruptedMarker = conversation(62L)
                .setConversationScopeType(" TASK_THREAD\0 ");
        when(conversationDao.findScopedById(OWNER, CLIENT, "62")).thenReturn(corruptedMarker);
        assertDenied(() -> service().requireAccessible(SCOPE, "62"));

        ChatConversationEntity bound = privateConversation(63L);
        when(conversationDao.findScopedById(OWNER, CLIENT, "63")).thenReturn(bound);
        when(taskThreadDao.findAnyByConversationId("63"))
                .thenReturn(new AgentTaskThreadEntity().setConversationId("63"));
        assertDenied(() -> service().requireAccessible(SCOPE, "63"));
    }

    @Test
    void malformedPersistedFactsFailClosed() {
        ChatConversationEntity badTargets = privateConversation(71L)
                .setTargetAgentIds("[\"agent-a\",\"agent-a\"]");
        when(conversationDao.findScopedById(OWNER, CLIENT, "71")).thenReturn(badTargets);
        assertDenied(() -> service().requireAccessible(SCOPE, "71"));

        ChatConversationEntity mismatchedKey = conversation(72L)
                .setConversationScopeType("bounty")
                .setConversationScopeKey("task:other")
                .setTaskId("task-7")
                .setTargetAgentIds("[\"agent-a\"]");
        when(conversationDao.findScopedById(OWNER, CLIENT, "72")).thenReturn(mismatchedKey);
        assertDenied(() -> service().requireAccessible(SCOPE, "72"));

        ChatConversationEntity missingGeneration = privateConversation(73L)
                .setLifecycleGeneration(null);
        when(conversationDao.findScopedById(OWNER, CLIENT, "73"))
                .thenReturn(missingGeneration);
        assertDenied(() -> service().requireAccessible(SCOPE, "73"));

        ChatConversationEntity missingRevision = privateConversation(74L);
        missingRevision.setUpdateTime(null);
        when(conversationDao.findScopedById(OWNER, CLIENT, "74"))
                .thenReturn(missingRevision);
        assertDenied(() -> service().requireAccessible(SCOPE, "74"));
    }

    @Test
    void daoFailuresFailClosedWithoutLeakingCause() {
        when(conversationDao.findScopedById(OWNER, CLIENT, "81"))
                .thenThrow(new IllegalArgumentException("sensitive database detail"));
        IllegalStateException conversationFailure = assertThrows(IllegalStateException.class,
                () -> service().requireAccessible(SCOPE, "81"));
        assertEquals("Workspace conversation is unavailable", conversationFailure.getMessage());

        ChatConversationEntity conversation = privateConversation(82L);
        when(conversationDao.findScopedById(OWNER, CLIENT, "82")).thenReturn(conversation);
        when(taskThreadDao.findAnyByConversationId("82"))
                .thenThrow(new IllegalStateException("sensitive binding detail"));
        IllegalStateException bindingFailure = assertThrows(IllegalStateException.class,
                () -> service().requireAccessible(SCOPE, "82"));
        assertEquals("Workspace conversation is unavailable", bindingFailure.getMessage());
    }

    private WorkspaceConversationAccessServiceImpl service() {
        return new WorkspaceConversationAccessServiceImpl(conversationDao, taskThreadDao);
    }

    private ChatConversationEntity privateConversation(long id) {
        return conversation(id)
                .setConversationScopeType("private")
                .setConversationScopeKey("agent:agent-a")
                .setTargetAgentId("agent-a")
                .setTargetAgentIds("[\"agent-a\"]");
    }

    private ChatConversationEntity conversation(long id) {
        ChatConversationEntity entity = new ChatConversationEntity()
                .setId(id)
                .setJiacn(OWNER)
                .setConversationType("juyiting")
                .setConversationScopeType("public")
                .setConversationScopeKey("public")
                .setTargetAgentIds("[\"agent-a\"]")
                .setStatus(0)
                .setLifecycleGeneration(1L);
        entity.setTenantId(TENANT);
        entity.setClientId(CLIENT);
        entity.setCreateTime(10L);
        entity.setUpdateTime(11L);
        return entity;
    }

    private void assertDenied(ThrowingAction action) {
        IllegalStateException denied = assertThrows(IllegalStateException.class, action::run);
        assertEquals("Workspace conversation is unavailable", denied.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
