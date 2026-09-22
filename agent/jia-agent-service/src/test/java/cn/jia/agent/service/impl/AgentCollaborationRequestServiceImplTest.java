package cn.jia.agent.service.impl;

import cn.jia.agent.service.AgentCollaborationRequestService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentCollaborationRequestServiceImplTest {
    private static final AgentCollaborationRequestService.OwnerScope SCOPE =
            new AgentCollaborationRequestService.OwnerScope("0", "client-a", "owner-a");
    private WorkspaceConversationAccessService conversations;
    private PersonalWorkspaceExecutionService executions;
    private AgentCollaborationRequestService service;

    @BeforeEach
    void setUp() {
        conversations = mock(WorkspaceConversationAccessService.class);
        executions = mock(PersonalWorkspaceExecutionService.class);
        service = new AgentCollaborationRequestServiceImpl(conversations, executions);
    }

    @Test
    void privateMeetingDispatchesOnlyToItsExactTargetAndNeverSelectsSongJiang() {
        var conversation = privateConversation("99", "agent-husan-niang");
        when(conversations.requireAccessible(any(), eq("99"))).thenReturn(conversation);
        when(executions.create(any(), any(), eq("direct-key"))).thenReturn(execution(
                "pwe_direct", "99", "agent-husan-niang", "PRIVATE", null));

        var accepted = service.submit(SCOPE, new AgentCollaborationRequestService.RequestCommand(
                "99", "agent-husan-niang", "只执行相关测试并回传结果", DOCX, List.of()), "direct-key");

        assertEquals("pwe_direct", accepted.requestId());
        assertEquals("agent-husan-niang", accepted.targetAgentId());
        assertEquals("private", accepted.conversationScopeType());
        assertEquals("RUNTIME_COMMAND_DISPATCH", accepted.dispatchMode());
        var command = org.mockito.ArgumentCaptor.forClass(PersonalWorkspaceExecutionService.CreateCommand.class);
        verify(executions).create(eq(new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a")),
                command.capture(), eq("direct-key"));
        assertEquals("agent-husan-niang", command.getValue().targetAgentId());
        assertEquals(null, command.getValue().taskId());
        org.mockito.Mockito.verifyNoMoreInteractions(executions);
    }

    @Test
    void privateMeetingCannotBeSharedToThirdAgentWithoutCreatingANewScope() {
        when(conversations.requireAccessible(any(), eq("99"))).thenReturn(
                privateConversation("99", "agent-husan-niang"));

        var failure = assertThrows(AgentCollaborationRequestService.Failure.class, () -> service.submit(
                SCOPE, new AgentCollaborationRequestService.RequestCommand("99", "agent-wuyong",
                        "不要泄露密议", DOCX, List.of()), "direct-key"));

        assertEquals(AgentCollaborationRequestService.Reason.NOT_FOUND, failure.reason());
        verifyNoInteractions(executions);
    }

    @Test
    void recoveryRefusesExecutionWhoseConversationOrTaskBindingNoLongerMatches() {
        when(executions.get(any(), eq("pwe_1"))).thenReturn(execution(
                "pwe_1", "99", "agent-husan-niang", "TASK", "task-9"));
        when(conversations.requireAccessible(any(), eq("99"))).thenReturn(privateConversation("99", "agent-husan-niang"));

        var failure = assertThrows(AgentCollaborationRequestService.Failure.class,
                () -> service.get(SCOPE, "pwe_1"));

        assertEquals(AgentCollaborationRequestService.Reason.NOT_FOUND, failure.reason());
        verify(executions).get(any(), eq("pwe_1"));
    }

    private static WorkspaceConversationAccessService.ConversationView privateConversation(
            String conversationId, String target) {
        return new WorkspaceConversationAccessService.ConversationView(conversationId, "private",
                "agent:" + target, null, List.of(target), 1L, 1L);
    }

    private static PersonalWorkspaceExecutionService.ExecutionView execution(String executionId,
            String conversationId, String target, String mode, String taskId) {
        return new PersonalWorkspaceExecutionService.ExecutionView(executionId, "pwe_task_1", "pwe_run_1",
                conversationId, target, "QUEUED", null, null, 1L, DOCX, List.of(), null,
                mode, taskId, null, null);
    }

    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
}
