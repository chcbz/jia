package cn.jia.chat.service;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.AgentTaskThreadMessageCreateDTO;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.impl.AgentTaskThreadCreationTransaction;
import cn.jia.chat.service.impl.AgentTaskThreadServiceImpl;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskThreadServiceImplTest extends BaseMockTest {
    private static final String TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock
    AgentTaskThreadDao taskThreadDao;
    @Mock
    ChatConversationDao conversationDao;
    @Mock
    ChatMessageDao messageDao;
    @Mock
    AgentService agentService;
    @Mock
    AgentTaskCollaborationAccessService accessService;
    @Mock
    AgentTaskThreadCreationTransaction creationTransaction;

    @Test
    void activeMemberGetsIdempotentStableTeamBinding() {
        AgentTaskThreadEntity thread = thread("101", AGENT);
        when(accessService.resolveMemberAccess(TENANT, CLIENT, OWNER, TASK, AGENT))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        when(taskThreadDao.findByTaskThread(
                TENANT, CLIENT, TASK, "team", "team"))
                .thenReturn(null, thread);
        when(creationTransaction.createTeamThread(
                eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(AGENT), any(), eq("task-thread:" + TASK)))
                .thenReturn(thread);
        when(conversationDao.findScopedById(OWNER, CLIENT, "101"))
                .thenReturn(conversation(101L));

        AgentTaskThreadServiceImpl service = service();
        assertEquals("101", service.getOrCreateTeamThread(
                TENANT, CLIENT, OWNER, TASK, AGENT, null).getConversationId());
        assertEquals("101", service.getOrCreateTeamThread(
                TENANT, CLIENT, OWNER, TASK, AGENT, "ignored on repeat").getConversationId());

        verify(agentService, times(2)).requireApiKeyOwnedAgent(CLIENT, OWNER, AGENT);
        verify(conversationDao, times(2)).findScopedById(OWNER, CLIENT, "101");
        verify(conversationDao, never()).findScopedById(eq(TENANT), any(), any());
        verify(creationTransaction, times(1)).createTeamThread(
                eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(AGENT), any(), any());
    }

    @Test
    void nonMemberAndCrossScopeFailBeforeThreadLookup() {
        AgentTaskThreadServiceImpl service = service();
        when(accessService.resolveMemberAccess(TENANT, CLIENT, OWNER, TASK, AGENT))
                .thenReturn(AgentTaskAccessLevel.NONE);

        AgentTaskThreadException denied = assertThrows(AgentTaskThreadException.class,
                () -> service.getTeamThread(TENANT, CLIENT, OWNER, TASK, AGENT));
        assertEquals(AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN, denied.getReason());
        verify(taskThreadDao, never()).findByTaskThread(any(), any(), any(), any(), any());

        when(accessService.resolveMemberAccess(TENANT, CLIENT, "owner-b", TASK, AGENT))
                .thenReturn(AgentTaskAccessLevel.NONE);
        AgentTaskThreadException crossOwner = assertThrows(AgentTaskThreadException.class,
                () -> service.getTeamThread(TENANT, CLIENT, "owner-b", TASK, AGENT));
        assertEquals(denied.getMessage(), crossOwner.getMessage());

        AgentTaskThreadException crossTenant = assertThrows(AgentTaskThreadException.class,
                () -> service.getTeamThread("tenant-b", CLIENT, OWNER, TASK, AGENT));
        assertEquals(AgentTaskThreadException.Reason.INVALID_REQUEST, crossTenant.getReason());
    }


    @Test
    void actorOwnershipFailuresAreNonLeakingAndStopBeforeMembershipOrThreadLookup() {
        AgentTaskThreadServiceImpl service = service();
        String[] deniedActors = {
                "agt_non_owned_aaaaaaaaaaaaaaaaaaaa",
                "builtin-songjiang",
                "agt_inactive_aaaaaaaaaaaaaaaaaaaaa"
        };
        String expectedMessage = null;
        for (String actor : deniedActors) {
            reset(agentService, accessService, taskThreadDao);
            when(agentService.requireApiKeyOwnedAgent(CLIENT, OWNER, actor))
                    .thenThrow(new IllegalStateException("sensitive ownership detail for " + actor));

            AgentTaskThreadException denied = assertThrows(AgentTaskThreadException.class,
                    () -> service.getTeamThread(TENANT, CLIENT, OWNER, TASK, actor));
            assertEquals(AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN, denied.getReason());
            if (expectedMessage == null) {
                expectedMessage = denied.getMessage();
            } else {
                assertEquals(expectedMessage, denied.getMessage());
            }
            verify(accessService, never()).resolveMemberAccess(any(), any(), any(), any(), any());
            verify(taskThreadDao, never()).findByTaskThread(any(), any(), any(), any(), any());
        }
        assertEquals("Task thread is not available in the requested scope", expectedMessage);
    }

    @Test
    void actorOwnershipIsRequiredForBothReadAndWriteBeforeMemberAcl() {
        when(accessService.resolveMemberAccess(TENANT, CLIENT, OWNER, TASK, AGENT))
                .thenReturn(AgentTaskAccessLevel.NONE);
        AgentTaskThreadServiceImpl service = service();

        assertThrows(AgentTaskThreadException.class,
                () -> service.getTeamThread(TENANT, CLIENT, OWNER, TASK, AGENT));
        AgentTaskThreadMessageCreateDTO request = new AgentTaskThreadMessageCreateDTO();
        request.setActorAgentId(AGENT);
        request.setContent("write attempt");
        assertThrows(AgentTaskThreadException.class,
                () -> service.appendTeamMessage(TENANT, CLIENT, OWNER, TASK, request));

        verify(agentService, times(2)).requireApiKeyOwnedAgent(CLIENT, OWNER, AGENT);
        verify(accessService, times(2)).resolveMemberAccess(TENANT, CLIENT, OWNER, TASK, AGENT);
        verify(taskThreadDao, never()).findByTaskThread(any(), any(), any(), any(), any());
        verify(messageDao, never()).insertScoped(any(), any(), any());
    }

    @Test
    void historicalDoneMemberCanReadButCannotWrite() {
        AgentTaskThreadEntity thread = thread("202", AGENT);
        when(accessService.resolveMemberAccess(TENANT, CLIENT, OWNER, TASK, AGENT))
                .thenReturn(AgentTaskAccessLevel.READ_ONLY);
        when(taskThreadDao.findByTaskThread(TENANT, CLIENT, TASK, "team", "team"))
                .thenReturn(thread);
        when(conversationDao.findScopedById(OWNER, CLIENT, "202"))
                .thenReturn(conversation(202L));
        when(messageDao.findByConversationIdScoped(TENANT, CLIENT, "202", 10))
                .thenReturn(List.of(message(1L, "202", "history")));

        AgentTaskThreadServiceImpl service = service();
        assertEquals("history", service.listTeamMessages(
                TENANT, CLIENT, OWNER, TASK, AGENT, 10).getFirst().getContent());

        AgentTaskThreadMessageCreateDTO request = new AgentTaskThreadMessageCreateDTO();
        request.setActorAgentId(AGENT);
        request.setContent("late write");
        assertThrows(AgentTaskThreadException.class,
                () -> service.appendTeamMessage(TENANT, CLIENT, OWNER, TASK, request));
        verify(messageDao, never()).insertScoped(any(), any(), any());
    }

    @Test
    void messageWriteForcesScopedAgentIdentityAndReservedMetadata() {
        when(accessService.resolveMemberAccess(TENANT, CLIENT, OWNER, TASK, AGENT))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        when(creationTransaction.appendTeamMessage(
                eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(AGENT), eq("Agent A"),
                any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> {
                    ChatMessageEntity entity = invocation.getArgument(6);
                    entity.setConversationId("303");
                    entity.setSenderName("Agent A");
                    entity.setId(9L);
                    entity.init4Creation();
                    return entity;
                });

        AgentTaskThreadMessageCreateDTO request = new AgentTaskThreadMessageCreateDTO();
        request.setActorAgentId(AGENT);
        request.setContent("进度 50%");
        request.setSenderName("Agent A");
        request.setMetadata(Map.of(
                "taskId", "spoofed", "actorAgentId", "spoofed", "progress", 50));

        var result = service().appendTeamMessage(TENANT, CLIENT, OWNER, TASK, request);
        assertEquals(9L, result.getMessageId());

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(creationTransaction).appendTeamMessage(
                eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(AGENT),
                eq("Agent A"), captor.capture());
        ChatMessageEntity saved = captor.getValue();
        assertEquals("303", saved.getConversationId());
        assertEquals("ASSISTANT", saved.getMessageType());
        assertEquals("agent", saved.getSenderType());
        assertEquals(AgentTaskThreadConstants.MEMORY_SYNC_EXCLUDED, saved.getSyncStatus());
        assertTrue(saved.getMetadata().contains("\"taskId\":\"task-1\""));
        assertTrue(saved.getMetadata().contains("\"actorAgentId\":\"" + AGENT + "\""));
    }

    @Test
    void invalidContentAndLimitFailBeforeDao() {
        AgentTaskThreadMessageCreateDTO request = new AgentTaskThreadMessageCreateDTO();
        request.setActorAgentId(AGENT);
        request.setContent(" ");
        assertThrows(AgentTaskThreadException.class,
                () -> service().appendTeamMessage(TENANT, CLIENT, OWNER, TASK, request));
        assertThrows(AgentTaskThreadException.class,
                () -> service().listTeamMessages(TENANT, CLIENT, OWNER, TASK, AGENT, 501));
        verify(messageDao, never()).insertScoped(any(), any(), any());
    }

    private AgentTaskThreadServiceImpl service() {
        return new AgentTaskThreadServiceImpl(
                taskThreadDao, conversationDao, messageDao, agentService, accessService, creationTransaction);
    }

    private AgentTaskThreadEntity thread(String conversationId, String creator) {
        AgentTaskThreadEntity entity = new AgentTaskThreadEntity()
                .setTaskId(TASK)
                .setThreadType(AgentTaskThreadConstants.THREAD_TYPE_TEAM)
                .setThreadKey(AgentTaskThreadConstants.THREAD_KEY_TEAM)
                .setConversationId(conversationId)
                .setCreatedByAgentId(creator)
                .setStatus(AgentTaskThreadConstants.THREAD_STATUS_ACTIVE);
        entity.setTenantId(TENANT);
        entity.setClientId(CLIENT);
        entity.setCreateTime(10L);
        return entity;
    }

    private ChatConversationEntity conversation(long id) {
        ChatConversationEntity entity = new ChatConversationEntity()
                .setId(id)
                .setJiacn(OWNER)
                .setConversationType(AgentTaskThreadConstants.CONVERSATION_TYPE)
                .setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE)
                .setConversationScopeKey("task-thread:" + TASK)
                .setTaskId(TASK)
                .setStatus(0);
        entity.setTenantId(TENANT);
        entity.setClientId(CLIENT);
        return entity;
    }

    private ChatMessageEntity message(long id, String conversationId, String content) {
        ChatMessageEntity entity = new ChatMessageEntity()
                .setId(id)
                .setConversationId(conversationId)
                .setContent(content);
        entity.setCreateTime(id);
        return entity;
    }
}
