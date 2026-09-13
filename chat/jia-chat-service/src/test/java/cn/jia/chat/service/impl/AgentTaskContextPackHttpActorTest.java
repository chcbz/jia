package cn.jia.chat.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.api.AgentTaskContextPackController;
import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.AgentTaskContextPackDao;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskArtifactOutcomeService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.agent.service.impl.AgentTaskContextPackServiceImpl;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP -> generator -> conversation adapter -> B07 thread service, without DB or AI. */
class AgentTaskContextPackHttpActorTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "same-task";
    private static final String REQUESTER = "requester-actor";
    private static final String COORDINATOR = "other-coordinator";
    private static final String PRODUCER = "other-private-producer";
    private static final String REVIEWER = "other-reviewer";
    private static final String CONVERSATION = "101";

    @Test
    void sameTaskAndThreadMembershipNeverAllowsQueryActorImpersonation() throws Exception {
        AgentTaskWorkspaceService workspace = mock(AgentTaskWorkspaceService.class);
        AgentTaskArtifactOutcomeService outcomes = mock(AgentTaskArtifactOutcomeService.class);
        AgentTaskContextPackDao source = mock(AgentTaskContextPackDao.class);
        AgentTaskThreadDao threads = mock(AgentTaskThreadDao.class);
        ChatConversationDao conversations = mock(ChatConversationDao.class);
        ChatMessageDao messages = mock(ChatMessageDao.class);
        AgentService agents = mock(AgentService.class);
        AgentTaskCollaborationAccessService access = mock(AgentTaskCollaborationAccessService.class);
        AgentTaskThreadCreationTransaction creation = mock(AgentTaskThreadCreationTransaction.class);
        AgentTaskEventsGate gate = mock(AgentTaskEventsGate.class);
        AgentTaskThreadServiceImpl threadService = spy(new AgentTaskThreadServiceImpl(
                threads, conversations, messages, agents, access, creation));
        AgentTaskContextPackConversationSourceImpl adapter =
                spy(new AgentTaskContextPackConversationSourceImpl(threadService));
        AgentTaskContextPackServiceImpl generator = spy(new AgentTaskContextPackServiceImpl(
                workspace, outcomes, source, Optional.of(adapter)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AgentTaskContextPackController(generator, gate)).build();

        when(gate.allows(TENANT, CLIENT)).thenReturn(true);
        when(workspace.snapshot(TENANT, CLIENT, TASK, REQUESTER)).thenReturn(workspace());
        when(outcomes.listAuthoritativeAccepted(TENANT, CLIENT, TASK, REQUESTER, null, 51))
                .thenReturn(List.of());
        when(access.resolveMemberAccess(TENANT, CLIENT, TASK, REQUESTER))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        // The other coordinator created this very same task's thread. That is NOT HTTP authority.
        when(threads.findByTaskThread(TENANT, CLIENT, TASK, "team", "team"))
                .thenReturn(thread());
        when(conversations.findScopedById(TENANT, CLIENT, CONVERSATION))
                .thenReturn(conversation());
        ChatMessageEntity message = new ChatMessageEntity().setId(201L)
                .setConversationId(CONVERSATION)
                .setContent("Authorization: Bearer raw-chat-secret")
                .setMetadata("{\"password\":\"raw-metadata-secret\"}");
        message.setCreateTime(1000L);
        when(messages.findByConversationIdScoped(TENANT, CLIENT, CONVERSATION, 101))
                .thenReturn(List.of(message));

        JwtAuthenticationToken requester = requester();
        for (String other : List.of(COORDINATOR, PRODUCER, REVIEWER, REQUESTER)) {
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .queryParam("actorAgentId", other).principal(requester))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        // A real generator is wired: rejection cannot be a mock returning a chosen actor's 404.
        verifyNoInteractions(gate, generator, workspace, outcomes, source, adapter, threadService,
                threads, conversations, messages, agents, access, creation);

        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("expectedVersion", "1").principal(requester))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance.tenantId").value(TENANT))
                .andExpect(jsonPath("$.provenance.clientId").value(CLIENT))
                .andExpect(jsonPath("$.provenance.taskId").value(TASK))
                .andExpect(jsonPath("$.provenance.actorAgentId").value(REQUESTER))
                .andExpect(jsonPath("$.provenance.currentEventVersion").value("1"))
                .andExpect(jsonPath("$.conversation.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.conversation.conversationId").value(CONVERSATION))
                .andExpect(jsonPath("$.conversation.recentMessageCount").value(1))
                .andExpect(jsonPath("$.conversation.latestMessageAt").value("1000"))
                .andExpect(jsonPath("$.conversation.contentOmitted").value(true))
                .andExpect(jsonPath("$.taskDescription.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.authoritativeArtifacts.items").isEmpty())
                .andExpect(content().string(not(containsString("raw-chat-secret"))))
                .andExpect(content().string(not(containsString("raw-metadata-secret"))));

        verify(generator).generate(TENANT, CLIENT, TASK, REQUESTER, "1");
        verify(workspace).snapshot(TENANT, CLIENT, TASK, REQUESTER);
        verify(outcomes).listAuthoritativeAccepted(TENANT, CLIENT, TASK, REQUESTER, null, 51);
        verify(source).findTaskDescription(TENANT, CLIENT, TASK);
        verify(adapter).findReference(TENANT, CLIENT, TASK, REQUESTER);
        verify(threadService).getTeamThread(TENANT, CLIENT, TASK, REQUESTER);
        verify(threadService).listTeamMessages(TENANT, CLIENT, TASK, REQUESTER, 101);
        // Both real B07 reads recheck the requester, never the thread creator or other producer.
        verify(agents, times(2)).requireApiKeyOwnedAgent(CLIENT, TENANT, REQUESTER);
        verify(access, times(2)).resolveMemberAccess(TENANT, CLIENT, TASK, REQUESTER);
        verify(threads, times(2)).findByTaskThread(TENANT, CLIENT, TASK, "team", "team");
        verify(conversations, times(2)).findScopedById(TENANT, CLIENT, CONVERSATION);
        verify(messages).findByConversationIdScoped(TENANT, CLIENT, CONVERSATION, 101);
        verifyNoMoreInteractions(generator, workspace, outcomes, source, adapter, threadService,
                agents, access, threads, conversations, messages);
        verifyNoInteractions(creation);
    }

    private static JwtAuthenticationToken requester() {
        Jwt jwt = Jwt.withTokenValue("validated-by-server-test-token").header("alg", "none")
                .claim("jiacn", TENANT).claim("client_id", CLIENT).subject(REQUESTER)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of(), REQUESTER);
    }

    private static AgentTaskWorkspaceDTO workspace() {
        AgentTaskWorkspaceDTO value = new AgentTaskWorkspaceDTO();
        AgentTaskWorkspaceDTO.Task task = new AgentTaskWorkspaceDTO.Task();
        task.setTaskId(TASK);
        task.setVersion("1");
        value.setTask(task);
        value.setCurrentVersion("1");
        value.setMembers(List.of(member(REQUESTER, "collaborator"),
                member(COORDINATOR, "coordinator"), member(PRODUCER, "collaborator"),
                member(REVIEWER, "reviewer")));
        value.setWorkItems(List.of());
        value.setOpenRequests(List.of());
        value.setRecentEvents(List.of());
        return value;
    }

    private static AgentTaskWorkspaceDTO.Member member(String actor, String role) {
        AgentTaskWorkspaceDTO.Member member = new AgentTaskWorkspaceDTO.Member();
        member.setAgentId(actor);
        member.setRole(role);
        member.setStatus("working");
        member.setVersion("1");
        return member;
    }

    private static AgentTaskThreadEntity thread() {
        AgentTaskThreadEntity value = new AgentTaskThreadEntity().setTaskId(TASK)
                .setThreadType(AgentTaskThreadConstants.THREAD_TYPE_TEAM)
                .setThreadKey(AgentTaskThreadConstants.THREAD_KEY_TEAM)
                .setConversationId(CONVERSATION).setCreatedByAgentId(COORDINATOR)
                .setStatus(AgentTaskThreadConstants.THREAD_STATUS_ACTIVE);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        value.setCreateTime(10L);
        return value;
    }

    private static ChatConversationEntity conversation() {
        ChatConversationEntity value = new ChatConversationEntity().setId(101L).setJiacn(TENANT)
                .setConversationType(AgentTaskThreadConstants.CONVERSATION_TYPE)
                .setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE)
                .setConversationScopeKey("task-thread:" + TASK).setTaskId(TASK).setStatus(0);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        return value;
    }
}
