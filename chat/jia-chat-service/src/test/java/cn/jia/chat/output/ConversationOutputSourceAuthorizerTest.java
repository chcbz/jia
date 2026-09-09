package cn.jia.chat.output;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.output.OutputSourceAccessMode;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationOutputSourceAuthorizerTest extends BaseMockTest {
    @Mock ChatConversationDao conversationDao;
    @Mock AgentTaskCollaborationAccessService taskAccessService;

    @Test
    void taskBackedConversationLocksTaskMemberBeforeConversationOwnerRow() {
        ChatConversationEntity preview = conversation("owner", "client", 101L,
                "agent-1", "task-1");
        ChatConversationEntity locked = conversation("owner", "client", 101L,
                "agent-1", "task-1");
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "101", false)).thenReturn(preview);
        when(taskAccessService.resolveMemberAccessForUpdate(
                "owner", "client", "task-1", "agent-1"))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "101", true)).thenReturn(locked);
        ConversationOutputSourceAuthorizer authorizer =
                new ConversationOutputSourceAuthorizer(conversationDao, taskAccessService);

        OutputSourceAuthorization result = authorizer.lockAndAuthorize(
                "owner", "client", "101", "agent-1");

        assertEquals("owner", result.ownerJiacn());
        InOrder order = inOrder(conversationDao, taskAccessService);
        order.verify(conversationDao).findExactOwnedById(
                "owner", "client", "owner", "101", false);
        order.verify(taskAccessService).resolveMemberAccessForUpdate(
                "owner", "client", "task-1", "agent-1");
        order.verify(conversationDao).findExactOwnedById(
                "owner", "client", "owner", "101", true);
    }

    @Test
    void plainConversationLocksOwnerWithoutTaskAcl() {
        ChatConversationEntity conversation = conversation(
                "owner", "client", 101L, "agent-1", null);
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "101", false)).thenReturn(conversation);
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "101", true)).thenReturn(conversation);
        ConversationOutputSourceAuthorizer authorizer =
                new ConversationOutputSourceAuthorizer(conversationDao, taskAccessService);

        authorizer.lockAndAuthorize("owner", "client", "101", "agent-1");

        verify(taskAccessService, never()).resolveMemberAccessForUpdate(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsTenantZeroCaseVariantsTargetMismatchAndChangedTask() {
        ConversationOutputSourceAuthorizer authorizer =
                new ConversationOutputSourceAuthorizer(conversationDao, taskAccessService);
        ChatConversationEntity tenantZero = conversation(
                "0", "client", 101L, "agent-1", null);
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "101", false)).thenReturn(tenantZero);
        assertThrows(OutputAuthorizationException.class,
                () -> authorizer.lockAndAuthorize(
                        "owner", "client", "101", "agent-1"));

        ChatConversationEntity wrongCase = conversation(
                "Owner", "client", 101L, "agent-1", null);
        when(conversationDao.findExactOwnedById(
                "Owner", "client", "Owner", "101", false)).thenReturn(wrongCase);
        when(conversationDao.findExactOwnedById(
                "Owner", "client", "Owner", "101", true)).thenReturn(wrongCase);
        OutputSourceAuthorization caseIsolated = authorizer.lockAndAuthorize(
                "Owner", "client", "101", "agent-1");
        assertEquals("Owner", caseIsolated.ownerJiacn());

        ChatConversationEntity target = conversation(
                "owner", "client", 102L, "agent-2", null);
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "102", false)).thenReturn(target);
        assertThrows(OutputAuthorizationException.class,
                () -> authorizer.lockAndAuthorize(
                        "owner", "client", "102", "agent-1"));

        ChatConversationEntity before = conversation(
                "owner", "client", 103L, "agent-1", "task-1");
        ChatConversationEntity after = conversation(
                "owner", "client", 103L, "agent-1", "task-2");
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "103", false)).thenReturn(before);
        when(taskAccessService.resolveMemberAccessForUpdate(
                "owner", "client", "task-1", "agent-1"))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "103", true)).thenReturn(after);
        assertThrows(OutputAuthorizationException.class,
                () -> authorizer.lockAndAuthorize(
                        "owner", "client", "103", "agent-1"));
    }

    @Test
    void taskBackedReceiptReadAllowsHistoricalMemberButRejectsNoAccess() {
        ChatConversationEntity conversation = conversation(
                "owner", "client", 104L, "agent-1", "task-1");
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "104", false)).thenReturn(conversation);
        when(conversationDao.findExactOwnedById(
                "owner", "client", "owner", "104", true)).thenReturn(conversation);
        when(taskAccessService.resolveMemberAccessForUpdate(
                "owner", "client", "task-1", "agent-1"))
                .thenReturn(AgentTaskAccessLevel.READ_ONLY, AgentTaskAccessLevel.NONE);
        ConversationOutputSourceAuthorizer authorizer =
                new ConversationOutputSourceAuthorizer(conversationDao, taskAccessService);

        OutputSourceAuthorization historical = authorizer.lockAndAuthorize(
                "owner", "client", "104", "agent-1",
                OutputSourceAccessMode.RECEIPT_READ);
        assertEquals(false, historical.writable());
        assertThrows(OutputAuthorizationException.class,
                () -> authorizer.lockAndAuthorize(
                        "owner", "client", "104", "agent-1",
                        OutputSourceAccessMode.RECEIPT_READ));
    }

    private ChatConversationEntity conversation(
            String owner, String client, Long id, String targetAgentId, String taskId) {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setId(id);
        entity.setTenantId(owner);
        entity.setClientId(client);
        entity.setJiacn(owner);
        entity.setTargetAgentId(targetAgentId);
        entity.setTaskId(taskId);
        return entity;
    }
}
