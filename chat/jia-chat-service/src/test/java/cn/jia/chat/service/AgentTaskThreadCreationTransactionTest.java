package cn.jia.chat.service;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock AgentTaskMutationTransaction mutationTransaction;
    @Mock AgentTaskEventWriter eventWriter;


    @Test
    void createLocksTaskRootBeforeMemberIdentityAndThreadAndAppendsBoundedEvent() {
        List<String> order = new ArrayList<>();
        stubRootLock(order);
        when(accessService.resolveMemberAccessForUpdate(TENANT, CLIENT, TASK, AGENT))
                .thenAnswer(invocation -> { order.add("member"); return AgentTaskAccessLevel.READ_WRITE; });
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId(AGENT);
        runtime.setName("Agent A");
        when(agentService.requireApiKeyOwnedAgentForUpdate(CLIENT, TENANT, AGENT))
                .thenAnswer(invocation -> { order.add("identity"); return runtime; });
        when(taskThreadDao.findByTaskThreadForUpdate(TENANT, CLIENT, TASK, "team", "team"))
                .thenAnswer(invocation -> { order.add("thread"); return null; });
        when(conversationDao.insert(any(ChatConversationEntity.class))).thenAnswer(invocation -> {
            ChatConversationEntity conversation = invocation.getArgument(0);
            conversation.setId(77L);
            conversation.setCreateTime(100L);
            return 1;
        });
        when(taskThreadDao.insert(eq(TENANT), eq(CLIENT), any(AgentTaskThreadEntity.class)))
                .thenAnswer(invocation -> {
                    AgentTaskThreadEntity thread = invocation.getArgument(2);
                    thread.setId(8L);
                    thread.setCreateTime(101L);
                    return 1;
                });

        AgentTaskThreadEntity created = transaction().createTeamThread(
                TENANT, CLIENT, TASK, AGENT, "Team", "task-thread:" + TASK);

        assertEquals(List.of("root", "member", "identity", "thread"), order);
        assertEquals(8L, created.getId());
        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals(TaskEventType.THREAD_CREATED, event.getValue().getEventType());
        assertEquals(TaskEventType.Aggregate.THREAD, event.getValue().getAggregateType());
        assertEquals("8", event.getValue().getAggregateId());
        assertTrue(event.getValue().getEventJson().contains("\"conversationId\":\"77\""));
        assertFalse(event.getValue().getEventJson().contains("Team"));
    }

    @Test
    void firstMessageCreatesThreadThenPostsRedactedMessageInOneRootLockedMutation() {
        stubRootLock(new ArrayList<>());
        stubWriter(AgentTaskAccessLevel.READ_WRITE);
        when(taskThreadDao.findByTaskThreadForUpdate(TENANT, CLIENT, TASK, "team", "team"))
                .thenReturn(null);
        when(conversationDao.insert(any(ChatConversationEntity.class))).thenAnswer(invocation -> {
            ChatConversationEntity conversation = invocation.getArgument(0);
            conversation.setId(77L);
            conversation.setCreateTime(100L);
            return 1;
        });
        when(taskThreadDao.insert(eq(TENANT), eq(CLIENT), any(AgentTaskThreadEntity.class)))
                .thenAnswer(invocation -> {
                    AgentTaskThreadEntity thread = invocation.getArgument(2);
                    thread.setId(8L);
                    thread.setCreateTime(101L);
                    return 1;
                });
        when(conversationDao.findScopedById(TENANT, CLIENT, "77")).thenReturn(conversation());
        when(messageDao.insertScoped(eq(TENANT), eq(CLIENT), any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> {
                    ChatMessageEntity message = invocation.getArgument(2);
                    message.setId(9L);
                    message.setCreateTime(102L);
                    return 1;
                });

        String body = "secret progress 正文";
        transaction().appendTeamMessage(
                TENANT, CLIENT, TASK, AGENT, null, new ChatMessageEntity()
                        .setMessageType("ASSISTANT").setContent(body));

        ArgumentCaptor<AgentTaskEventWriteCommand> events =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter, org.mockito.Mockito.times(2)).append(events.capture());
        assertEquals(List.of(TaskEventType.THREAD_CREATED, TaskEventType.MESSAGE_POSTED),
                events.getAllValues().stream().map(AgentTaskEventWriteCommand::getEventType).toList());
        String messageJson = events.getAllValues().get(1).getEventJson();
        assertFalse(messageJson.contains(body));
        assertFalse(messageJson.contains("secret progress"));
        assertTrue(messageJson.contains("contentSha256"));
        assertTrue(messageJson.contains("contentByteLength"));
        assertEquals(TaskEventType.ActorType.AGENT, events.getAllValues().get(1).getActorType());
        assertEquals(AGENT, events.getAllValues().get(1).getActorId());
    }

    @Test
    void appendDerivesTrustedSenderAndRevalidatesOwnershipAndMembershipUnderLock() {
        stubRootLock(new ArrayList<>());
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

        ChatMessageEntity message = new ChatMessageEntity()
                .setMessageType("ASSISTANT").setContent("progress");
        ChatMessageEntity saved = transaction().appendTeamMessage(
                TENANT, CLIENT, TASK, AGENT, null, message);

        assertEquals("Agent A", saved.getSenderName());
        assertEquals("77", saved.getConversationId());
        verify(agentService).requireApiKeyOwnedAgentForUpdate(CLIENT, TENANT, AGENT);
        verify(accessService).resolveMemberAccessForUpdate(TENANT, CLIENT, TASK, AGENT);
    }

    @Test
    void senderSpoofIsRejectedAndRevokedMemberCannotWriteAfterOuterChecks() {
        stubRootLock(new ArrayList<>());
        stubWriter(AgentTaskAccessLevel.READ_WRITE);
        when(taskThreadDao.findByTaskThreadForUpdate(TENANT, CLIENT, TASK, "team", "team"))
                .thenReturn(thread());
        when(conversationDao.findScopedById(TENANT, CLIENT, "77"))
                .thenReturn(conversation());
        assertThrows(AgentTaskThreadException.class, () -> transaction().appendTeamMessage(
                TENANT, CLIENT, TASK, AGENT, "Other Agent",
                new ChatMessageEntity().setMessageType("ASSISTANT").setContent("body")));
        verify(messageDao, never()).insertScoped(any(), any(), any());

        org.mockito.Mockito.reset(taskThreadDao, conversationDao, messageDao, accessService);
        stubWriter(AgentTaskAccessLevel.NONE);
        AgentTaskThreadException revoked = assertThrows(AgentTaskThreadException.class,
                () -> transaction().appendTeamMessage(
                        TENANT, CLIENT, TASK, AGENT, null,
                        new ChatMessageEntity().setMessageType("ASSISTANT").setContent("body")));
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

    private void stubRootLock(List<String> order) {
        when(mutationTransaction.executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any()))
                .thenAnswer(invocation -> {
                    order.add("root");
                    AgentTaskMetaEntity root = new AgentTaskMetaEntity()
                            .setTaskId(TASK).setTaskVersion(3L).setCurrentEventVersion(0L);
                    root.setTenantId(TENANT);
                    root.setClientId(CLIENT);
                    AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(3);
                    return mutation.apply(root);
                });
    }

    private AgentTaskThreadCreationTransaction transaction() {
        return new AgentTaskThreadCreationTransaction(
                taskThreadDao, conversationDao, messageDao, agentService, accessService,
                mutationTransaction, eventWriter);
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
