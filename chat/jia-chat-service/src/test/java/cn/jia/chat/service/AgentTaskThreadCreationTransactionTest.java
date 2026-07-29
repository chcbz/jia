package cn.jia.chat.service;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.impl.AgentTaskThreadCreationTransaction;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskThreadCreationTransactionTest extends BaseMockTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock AgentTaskThreadDao taskThreadDao;
    @Mock ChatConversationDao conversationDao;
    @Mock ChatMessageDao messageDao;
    @Mock AgentService agentService;
    @Mock AgentTaskCollaborationAccessService accessService;

    @Test
    void appendDerivesTrustedSenderAndRevalidatesOwnershipAndMembershipUnderLock() {
        stubWriter(AgentTaskAccessLevel.READ_WRITE);
        when(taskThreadDao.findByTaskThreadForUpdate(TENANT, CLIENT, TASK, "team", "team"))
                .thenReturn(thread());
        when(conversationDao.findScopedById(TENANT, CLIENT, "77"))
                .thenReturn(conversation());
        when(messageDao.insertScoped(eq(TENANT), eq(CLIENT), any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> {
                    ChatMessageEntity message = invocation.getArgument(2);
                    message.setId(9L);
                    return 1;
                });

        ChatMessageEntity message = new ChatMessageEntity().setContent("progress");
        ChatMessageEntity saved = transaction().appendTeamMessage(
                TENANT, CLIENT, TASK, AGENT, null, message);

        assertEquals("Agent A", saved.getSenderName());
        assertEquals("77", saved.getConversationId());
        verify(agentService).requireApiKeyOwnedAgentForUpdate(CLIENT, TENANT, AGENT);
        verify(accessService).resolveMemberAccessForUpdate(TENANT, CLIENT, TASK, AGENT);
    }

    @Test
    void senderSpoofIsRejectedAndRevokedMemberCannotWriteAfterOuterChecks() {
        stubWriter(AgentTaskAccessLevel.READ_WRITE);
        when(taskThreadDao.findByTaskThreadForUpdate(TENANT, CLIENT, TASK, "team", "team"))
                .thenReturn(thread());
        when(conversationDao.findScopedById(TENANT, CLIENT, "77"))
                .thenReturn(conversation());
        assertThrows(AgentTaskThreadException.class, () -> transaction().appendTeamMessage(
                TENANT, CLIENT, TASK, AGENT, "Other Agent", new ChatMessageEntity()));
        verify(messageDao, never()).insertScoped(any(), any(), any());

        org.mockito.Mockito.reset(taskThreadDao, conversationDao, messageDao, accessService);
        stubWriter(AgentTaskAccessLevel.NONE);
        AgentTaskThreadException revoked = assertThrows(AgentTaskThreadException.class,
                () -> transaction().appendTeamMessage(
                        TENANT, CLIENT, TASK, AGENT, null, new ChatMessageEntity()));
        assertEquals(AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN, revoked.getReason());
        verify(taskThreadDao, never()).findByTaskThreadForUpdate(any(), any(), any(), any(), any());
        verify(messageDao, never()).insertScoped(any(), any(), any());
    }

    private void stubWriter(AgentTaskAccessLevel access) {
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId(AGENT);
        runtime.setName("Agent A");
        if (access.canWrite()) {
            when(agentService.requireApiKeyOwnedAgentForUpdate(CLIENT, TENANT, AGENT)).thenReturn(runtime);
        }
        when(accessService.resolveMemberAccessForUpdate(TENANT, CLIENT, TASK, AGENT)).thenReturn(access);
    }

    private AgentTaskThreadCreationTransaction transaction() {
        return new AgentTaskThreadCreationTransaction(
                taskThreadDao, conversationDao, messageDao, agentService, accessService);
    }

    private AgentTaskThreadEntity thread() {
        AgentTaskThreadEntity thread = new AgentTaskThreadEntity()
                .setTaskId(TASK).setThreadType("team").setThreadKey("team")
                .setConversationId("77").setCreatedByAgentId(AGENT).setStatus("active");
        thread.setTenantId(TENANT);
        thread.setClientId(CLIENT);
        return thread;
    }

    private ChatConversationEntity conversation() {
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(77L).setJiacn(TENANT).setConversationType("juyiting")
                .setConversationScopeType("task_thread")
                .setConversationScopeKey("task-thread:" + TASK)
                .setTaskId(TASK).setStatus(0);
        conversation.setTenantId(TENANT);
        conversation.setClientId(CLIENT);
        return conversation;
    }
}
