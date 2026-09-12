package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemDependencyService;
import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemResultCommitDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskArtifactService;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkItemResultCommitServiceImplTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String WORK = "work-1";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TOKEN = "lease_current";
    private static final long VERSION = 7L;
    private static final long LEASE_UNTIL = 2_000L;
    private static final long NOW = 1_000L;

    private AgentWorkItemLeaseService leaseService;
    private AgentTaskArtifactService artifactService;
    private AgentTaskWorkItemDao workItemDao;
    private AgentTaskMutationTransaction mutationTransaction;
    private AgentTaskEventWriter eventWriter;
    private AgentWorkItemDependencyService dependencyService;
    private AgentWorkItemResultCommitServiceImpl service;

    @BeforeEach
    void setUp() {
        leaseService = mock(AgentWorkItemLeaseService.class);
        artifactService = mock(AgentTaskArtifactService.class);
        workItemDao = mock(AgentTaskWorkItemDao.class);
        mutationTransaction = mock(AgentTaskMutationTransaction.class);
        eventWriter = mock(AgentTaskEventWriter.class);
        dependencyService = mock(AgentWorkItemDependencyService.class);
        when(mutationTransaction.executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any())).thenAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(3);
            AgentTaskMetaEntity root = new AgentTaskMetaEntity()
                    .setTaskId(TASK).setTaskVersion(0L).setCurrentEventVersion(0L);
            root.setTenantId(TENANT);
            root.setClientId(CLIENT);
            return mutation.apply(root);
        });
        service = new AgentWorkItemResultCommitServiceImpl(
                leaseService, artifactService, workItemDao,
                mutationTransaction, eventWriter, dependencyService, () -> NOW);
    }

    @Test
    void reusesB04ValidationThenAppliesExactLeaseCas() {
        AgentWorkItemResultCommitDTO command = command();
        when(leaseService.validateLeaseForResult(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(lease());
        AgentTaskArtifactViewDTO artifact = new AgentTaskArtifactViewDTO();
        artifact.setArtifactId("artifact-1");
        when(artifactService.publish(TENANT, CLIENT, TASK, AGENT, command.getArtifact()))
                .thenReturn(artifact);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(workItem());
        when(workItemDao.updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(LEASE_UNTIL), eq(VERSION), eq(NOW), any())).thenReturn(1);

        var result = service.commitResult(TENANT, CLIENT, TASK, AGENT, command);

        assertEquals("submitted", result.getStatus());
        assertEquals(VERSION + 1, result.getWorkItemVersion());
        ArgumentCaptor<AgentTaskWorkItemDTO> update = ArgumentCaptor.forClass(AgentTaskWorkItemDTO.class);
        verify(workItemDao).updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(LEASE_UNTIL), eq(VERSION), eq(NOW), update.capture());
        assertEquals("submitted", update.getValue().getStatus());
        assertEquals("artifact-1", update.getValue().getResultArtifactId());
        assertNull(update.getValue().getLeaseToken());
        assertNull(update.getValue().getLeaseUntil());
        verify(dependencyService).resolveReady(TENANT, CLIENT, TASK);
    }

    @Test
    void casLossAfterArtifactPublishRaisesConflictForTransactionRollback() {
        AgentWorkItemResultCommitDTO command = command();
        when(leaseService.validateLeaseForResult(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(lease());
        AgentTaskArtifactViewDTO artifact = new AgentTaskArtifactViewDTO();
        artifact.setArtifactId("artifact-1");
        when(artifactService.publish(TENANT, CLIENT, TASK, AGENT, command.getArtifact()))
                .thenReturn(artifact);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(workItem());
        when(workItemDao.updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(LEASE_UNTIL), eq(VERSION), eq(NOW), any())).thenReturn(0);

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.commitResult(TENANT, CLIENT, TASK, AGENT, command));

        assertEquals(Reason.VERSION_CONFLICT, error.getReason());
    }

    @Test
    void mismatchedB04SnapshotFailsClosedBeforeArtifactWrite() {
        AgentWorkItemLeaseDTO mismatched = lease();
        mismatched.setLeaseToken("lease_other");
        when(leaseService.validateLeaseForResult(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(mismatched);

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.commitResult(TENANT, CLIENT, TASK, AGENT, command()));

        assertEquals(Reason.INVALID_PERSISTED_STATE, error.getReason());
        verify(artifactService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void resultArtifactMustUseSameWorkItemAndProducer() {
        AgentWorkItemResultCommitDTO command = command();
        command.getArtifact().setWorkItemId("work-other");
        assertEquals(Reason.INVALID_REQUEST, assertThrows(AgentTaskCollaborationException.class,
                () -> service.commitResult(TENANT, CLIENT, TASK, AGENT, command)).getReason());
        verify(leaseService, never()).validateLeaseForResult(any(), any(), any(), any(), any());
    }


    @Test
    void locksRootThenPublishesArtifactBeforeSubmittedEvent() {
        AgentWorkItemResultCommitDTO command = command();
        when(leaseService.validateLeaseForResult(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(lease());
        AgentTaskArtifactViewDTO artifact = new AgentTaskArtifactViewDTO();
        artifact.setArtifactId("artifact-1");
        when(artifactService.publish(TENANT, CLIENT, TASK, AGENT, command.getArtifact()))
                .thenReturn(artifact);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(workItem());
        when(workItemDao.updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(LEASE_UNTIL), eq(VERSION), eq(NOW), any())).thenReturn(1);

        service.commitResult(TENANT, CLIENT, TASK, AGENT, command);

        var order = inOrder(mutationTransaction, leaseService, artifactService, workItemDao,
                eventWriter, dependencyService);
        order.verify(mutationTransaction).executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any());
        order.verify(leaseService).validateLeaseForResult(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any());
        order.verify(artifactService).publish(TENANT, CLIENT, TASK, AGENT, command.getArtifact());
        order.verify(workItemDao).updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(LEASE_UNTIL), eq(VERSION), eq(NOW), any());
        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        order.verify(eventWriter).append(event.capture());
        order.verify(dependencyService).resolveReady(TENANT, CLIENT, TASK);
        assertEquals(TaskEventType.WORK_ITEM_SUBMITTED, event.getValue().getEventType());
        assertEquals("work-1", event.getValue().getAggregateId());
        assertEquals(false, event.getValue().getEventJson().contains(TOKEN));
    }

    @Test
    void resultCasLossDoesNotAppendSubmittedEvent() {
        AgentWorkItemResultCommitDTO command = command();
        when(leaseService.validateLeaseForResult(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(lease());
        AgentTaskArtifactViewDTO artifact = new AgentTaskArtifactViewDTO();
        artifact.setArtifactId("artifact-1");
        when(artifactService.publish(TENANT, CLIENT, TASK, AGENT, command.getArtifact()))
                .thenReturn(artifact);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(workItem());
        when(workItemDao.updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(LEASE_UNTIL), eq(VERSION), eq(NOW), any())).thenReturn(0);

        assertThrows(AgentTaskCollaborationException.class,
                () -> service.commitResult(TENANT, CLIENT, TASK, AGENT, command));

        verify(eventWriter, never()).append(any());
        verify(dependencyService, never()).resolveReady(any(), any(), any());
    }

    @Test
    void dependencyFailurePropagatesAfterSubmittedEventForWholeTransactionRollback() {
        AgentWorkItemResultCommitDTO command = command();
        when(leaseService.validateLeaseForResult(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(lease());
        AgentTaskArtifactViewDTO artifact = new AgentTaskArtifactViewDTO();
        artifact.setArtifactId("artifact-1");
        when(artifactService.publish(TENANT, CLIENT, TASK, AGENT, command.getArtifact()))
                .thenReturn(artifact);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(workItem());
        when(workItemDao.updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(LEASE_UNTIL), eq(VERSION), eq(NOW), any())).thenReturn(1);
        when(dependencyService.resolveReady(TENANT, CLIENT, TASK))
                .thenThrow(new IllegalStateException("dependency event failed"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.commitResult(TENANT, CLIENT, TASK, AGENT, command));

        assertEquals("dependency event failed", error.getMessage());
        verify(eventWriter).append(any());
        verify(dependencyService).resolveReady(TENANT, CLIENT, TASK);
    }

    private AgentWorkItemResultCommitDTO command() {
        AgentTaskArtifactPublishDTO artifact = new AgentTaskArtifactPublishDTO();
        artifact.setArtifactId("artifact-1");
        artifact.setWorkItemId(WORK);
        artifact.setProducerAgentId(AGENT);
        AgentWorkItemResultCommitDTO command = new AgentWorkItemResultCommitDTO();
        command.setWorkItemId(WORK);
        command.setProducerAgentId(AGENT);
        command.setLeaseToken(TOKEN);
        command.setExpectedWorkItemVersion(VERSION);
        command.setArtifact(artifact);
        return command;
    }

    private AgentWorkItemLeaseDTO lease() {
        AgentWorkItemLeaseDTO lease = new AgentWorkItemLeaseDTO();
        lease.setTaskId(TASK);
        lease.setWorkItemId(WORK);
        lease.setAgentId(AGENT);
        lease.setStatus("running");
        lease.setLeaseToken(TOKEN);
        lease.setLeaseUntil(LEASE_UNTIL);
        lease.setVersion(VERSION);
        return lease;
    }

    private AgentTaskWorkItemEntity workItem() {
        AgentTaskWorkItemEntity item = new AgentTaskWorkItemEntity();
        item.setTaskId(TASK);
        item.setWorkItemId(WORK);
        item.setTitle("work");
        item.setWorkType("implementation");
        item.setAssigneeAgentId(AGENT);
        item.setStatus("running");
        item.setPriority(1);
        item.setRequiredItem(true);
        item.setLeaseToken(TOKEN);
        item.setLeaseUntil(LEASE_UNTIL);
        item.setAttemptCount(0);
        item.setMaxAttempts(3);
        item.setVersion(VERSION);
        return item;
    }
}
