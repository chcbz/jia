package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskReworkExecutionService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskReworkExecutionServiceImplTest {
    private static final PersonalWorkspaceExecutionService.OwnerScope SCOPE =
            new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a");
    private AgentTaskFormalDeliveryDao formalDeliveries;
    private PersonalWorkspaceExecutionDao executions;
    private PersonalWorkspaceExecutionService executionService;
    private AgentTaskMutationTransaction taskMutations;
    private AgentTaskReworkExecutionService service;

    @BeforeEach
    void setUp() {
        formalDeliveries = mock(AgentTaskFormalDeliveryDao.class);
        executions = mock(PersonalWorkspaceExecutionDao.class);
        executionService = mock(PersonalWorkspaceExecutionService.class);
        taskMutations = mock(AgentTaskMutationTransaction.class);
        service = new AgentTaskReworkExecutionServiceImpl(
                formalDeliveries, executions, executionService, taskMutations);
        AgentTaskMetaEntity root = new AgentTaskMetaEntity().setTaskId("task-1");
        root.setTenantId("0"); root.setClientId("client-a"); root.setOwnerJiacn("owner-a");
        doAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> callback = invocation.getArgument(4);
            return callback.apply(root);
        }).when(taskMutations).executeWithLockedTaskRootInOwnerScope(
                eq("0"), eq("client-a"), eq("owner-a"), eq("task-1"), any());
    }

    @Test
    void exactChangesRequestedDecisionAndPublishedVersionCreateOneIdempotentTaskExecution() {
        when(formalDeliveries.findForUpdate("0", "client-a", "delivery-1"))
                .thenReturn(changesRequested(1L));
        when(executions.findPublishedReworkSource("0", "client-a", "owner-a", "task-1",
                "delivery-1", "output-1", "file-1", 3)).thenReturn(source());
        PersonalWorkspaceExecutionService.ExecutionView expected =
                new PersonalWorkspaceExecutionService.ExecutionView(
                        "exec-1", "task-1", "run-1", "conversation-1", "agent-a", "QUEUED",
                        null, null, 1L, "application/pdf", List.of(), null,
                        "TASK", "task-1", "work-1", "running");
        when(executionService.create(eq(SCOPE), any(), eq("rework-key-0001"))).thenReturn(expected);

        var result = service.create(SCOPE, command(1L), "rework-key-0001");

        assertEquals(expected, result);
        ArgumentCaptor<PersonalWorkspaceExecutionService.CreateCommand> create =
                ArgumentCaptor.forClass(PersonalWorkspaceExecutionService.CreateCommand.class);
        verify(executionService).create(eq(SCOPE), create.capture(), eq("rework-key-0001"));
        assertEquals("task-1", create.getValue().taskId());
        assertEquals("conversation-1", create.getValue().conversationId());
        assertEquals(List.of(new PersonalWorkspaceExecutionService.InputSelection("file-1", 3)),
                create.getValue().inputs());
        assertEquals(new PersonalWorkspaceExecutionService.SourceOutputRef(
                "delivery-1", 1L, "output-1", "file-1", 3),
                create.getValue().sourceOutputRef());
    }

    @Test
    void acceptedDecisionCannotCreateReworkExecution() {
        AgentTaskFormalDeliveryEntity accepted = changesRequested(1L).setState("accepted");
        when(formalDeliveries.findForUpdate("0", "client-a", "delivery-1"))
                .thenReturn(accepted);

        var failure = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.create(SCOPE, command(1L), "rework-key-accepted"));

        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT, failure.getReason());
        verify(executions, never()).findPublishedReworkSource(
                any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(executionService, never()).create(any(), any(), any());
    }

    @Test
    void staleDecisionVersionOrNonPublishedSourceFailsBeforeQueueCreation() {
        when(formalDeliveries.findForUpdate("0", "client-a", "delivery-1"))
                .thenReturn(changesRequested(1L));
        var stale = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.create(SCOPE, command(2L), "rework-key-0001"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT, stale.getReason());
        verify(executions, never()).findPublishedReworkSource(
                any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(executionService, never()).create(any(), any(), any());

        when(executions.findPublishedReworkSource("0", "client-a", "owner-a", "task-1",
                "delivery-1", "output-1", "file-1", 3)).thenReturn(null);
        var missing = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.create(SCOPE, command(1L), "rework-key-0002"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND, missing.getReason());
        verify(executionService, never()).create(any(), any(), any());
    }

    private static AgentTaskReworkExecutionService.ReworkCommand command(long decisionVersion) {
        return new AgentTaskReworkExecutionService.ReworkCommand(
                "task-1", "delivery-1", decisionVersion, "conversation-1", "agent-a",
                "output-1", "file-1", 3, "按验收意见返工", "application/pdf");
    }

    private static AgentTaskFormalDeliveryEntity changesRequested(long version) {
        AgentTaskFormalDeliveryEntity delivery = new AgentTaskFormalDeliveryEntity()
                .setTaskId("task-1").setDeliveryId("delivery-1")
                .setState("changes_requested").setVersion(version)
                .setReviewedByJiacn("owner-a").setReviewedAt(2_000L);
        delivery.setTenantId("0"); delivery.setClientId("client-a");
        return delivery;
    }

    private static PersonalWorkspaceExecutionOutputEntity source() {
        PersonalWorkspaceExecutionOutputEntity source = new PersonalWorkspaceExecutionOutputEntity()
                .setOutputId("output-1").setFormalDeliveryId("delivery-1")
                .setWorkspaceFileId("file-1").setWorkspaceFileVersion(3)
                .setOutputState("COMMITTED").setPublicationState("PUBLISHED");
        source.setTenantId("0"); source.setClientId("client-a"); source.setOwnerJiacn("owner-a");
        return source;
    }
}
