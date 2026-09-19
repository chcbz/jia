package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskFormalDeliveryDecisionDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskFormalDeliveryDecisionServiceImplTest {
    private static final String TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-a";
    private static final String WORK = "work-a";
    private static final String DELIVERY = "delivery-a";
    private static final String MANIFEST = "manifest-a";
    private static final String HASH = "a".repeat(64);

    private AgentTaskFormalDeliveryDao deliveryDao;
    private AgentTaskMetaDao taskMetaDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentTaskMutationTransaction transaction;
    private AgentTaskEventWriter eventWriter;
    private AgentTaskFormalDeliveryDecisionServiceImpl service;

    @BeforeEach
    void setUp() {
        deliveryDao = mock(AgentTaskFormalDeliveryDao.class);
        taskMetaDao = mock(AgentTaskMetaDao.class);
        workItemDao = mock(AgentTaskWorkItemDao.class);
        transaction = mock(AgentTaskMutationTransaction.class);
        eventWriter = mock(AgentTaskEventWriter.class);
        when(transaction.executeWithLockedTaskRootInOwnerScope(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(4);
                    return mutation.apply(root());
                });
        service = new AgentTaskFormalDeliveryDecisionServiceImpl(deliveryDao, taskMetaDao,
                workItemDao, transaction, eventWriter, () -> 2_000L);
    }

    @Test
    void changesRequestedReturnsSubmittedWorkToReadyAndRequiresNewLeaseLater() {
        when(deliveryDao.findForUpdate(TENANT, CLIENT, DELIVERY)).thenReturn(delivery());
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, OWNER, TASK, WORK)).thenReturn(workItem());
        when(deliveryDao.reviewByVersion(TENANT, CLIENT, DELIVERY, "submitted", 0L,
                "changes_requested", OWNER, "add tests", 2_000L)).thenReturn(1);
        when(taskMetaDao.updateStatusByVersionInOwnerScope(TENANT, CLIENT, OWNER, TASK, 4L, "running",
                100L, null, null)).thenReturn(1);
        when(workItemDao.updateByVersion(eq(TENANT), eq(CLIENT), eq(OWNER), eq(WORK), eq(8L), any()))
                .thenReturn(1);
        when(deliveryDao.listItems(TENANT, CLIENT, DELIVERY)).thenReturn(List.of(item()));

        var result = service.decide(TENANT, CLIENT, TASK, OWNER, changesRequested());

        assertEquals("changes_requested", result.getState());
        assertEquals(5L, result.getTaskVersion());
        assertEquals(9L, result.getWorkItemVersion());
        assertFalse(result.getReplayed());
        ArgumentCaptor<AgentTaskWorkItemDTO> update = ArgumentCaptor.forClass(AgentTaskWorkItemDTO.class);
        verify(workItemDao).updateByVersion(eq(TENANT), eq(CLIENT), eq(OWNER), eq(WORK), eq(8L), update.capture());
        assertEquals("ready", update.getValue().getStatus());
        assertEquals(null, update.getValue().getLeaseToken());
        assertEquals(null, update.getValue().getLeaseUntil());
        assertEquals(null, update.getValue().getResultArtifactId());

        ArgumentCaptor<AgentTaskEventWriteCommand> events = ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter, org.mockito.Mockito.times(3)).append(events.capture());
        assertEquals(List.of(TaskEventType.FORMAL_DELIVERY_CHANGES_REQUESTED,
                TaskEventType.WORK_ITEM_READY, TaskEventType.TASK_STARTED),
                events.getAllValues().stream().map(AgentTaskEventWriteCommand::getEventType).toList());
        assertFalse(events.getAllValues().getFirst().getEventJson().contains("add tests"));
    }

    @Test
    void acceptedDecisionCompletesTaskAndWorkItem() {
        when(deliveryDao.findForUpdate(TENANT, CLIENT, DELIVERY)).thenReturn(delivery());
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, OWNER, TASK, WORK)).thenReturn(workItem());
        when(deliveryDao.reviewByVersion(TENANT, CLIENT, DELIVERY, "submitted", 0L,
                "accepted", OWNER, null, 2_000L)).thenReturn(1);
        when(taskMetaDao.updateStatusByVersionInOwnerScope(TENANT, CLIENT, OWNER, TASK, 4L, "completed",
                100L, 2_000L, null)).thenReturn(1);
        when(workItemDao.updateByVersion(eq(TENANT), eq(CLIENT), eq(OWNER), eq(WORK), eq(8L), any()))
                .thenReturn(1);
        when(deliveryDao.listItems(TENANT, CLIENT, DELIVERY)).thenReturn(List.of(item()));

        var result = service.decide(TENANT, CLIENT, TASK, OWNER, accepted());

        assertEquals("accepted", result.getState());
        ArgumentCaptor<AgentTaskWorkItemDTO> update = ArgumentCaptor.forClass(AgentTaskWorkItemDTO.class);
        verify(workItemDao).updateByVersion(eq(TENANT), eq(CLIENT), eq(OWNER), eq(WORK), eq(8L), update.capture());
        assertEquals("completed", update.getValue().getStatus());
        assertEquals(2_000L, update.getValue().getCompletedAt());
    }

    @Test
    void crossTenantOwnerCannotDecide() {
        assertThrows(AgentTaskCollaborationException.class,
                () -> service.decide(TENANT, CLIENT, TASK, "owner-b", accepted()));
        verify(transaction, never()).executeWithLockedTaskRootInOwnerScope(any(), any(), any(), any(), any());
    }

    private static AgentTaskFormalDeliveryDecisionDTO accepted() {
        AgentTaskFormalDeliveryDecisionDTO dto = base();
        dto.setDecision("accepted");
        return dto;
    }

    private static AgentTaskFormalDeliveryDecisionDTO changesRequested() {
        AgentTaskFormalDeliveryDecisionDTO dto = base();
        dto.setDecision("changes_requested");
        dto.setReviewReason("add tests");
        return dto;
    }

    private static AgentTaskFormalDeliveryDecisionDTO base() {
        AgentTaskFormalDeliveryDecisionDTO dto = new AgentTaskFormalDeliveryDecisionDTO();
        dto.setDeliveryId(DELIVERY);
        dto.setExpectedTaskVersion(4L);
        dto.setExpectedDeliveryVersion(0L);
        return dto;
    }

    private static AgentTaskMetaEntity root() {
        AgentTaskMetaEntity root = new AgentTaskMetaEntity();
        root.setTenantId(TENANT); root.setClientId(CLIENT); root.setOwnerJiacn(OWNER); root.setTaskId(TASK);
        root.setRewardStatus("reviewing"); root.setTaskVersion(4L); root.setCurrentEventVersion(6L);
        root.setStartedAt(100L);
        return root;
    }

    private static AgentTaskFormalDeliveryEntity delivery() {
        AgentTaskFormalDeliveryEntity entity = new AgentTaskFormalDeliveryEntity();
        entity.setTenantId(TENANT); entity.setClientId(CLIENT); entity.setTaskId(TASK);
        entity.setWorkItemId(WORK); entity.setDeliveryId(DELIVERY); entity.setRevision(1L);
        entity.setProducerAgentId("agent-a"); entity.setRunId("run-a"); entity.setSummary("summary");
        entity.setState("submitted"); entity.setSubmissionDigest(HASH); entity.setManifestArtifactId(MANIFEST);
        entity.setManifestArtifactVersion(1); entity.setSubmittedAt(1_000L); entity.setVersion(0L);
        return entity;
    }

    private static AgentTaskWorkItemEntity workItem() {
        AgentTaskWorkItemEntity item = new AgentTaskWorkItemEntity();
        item.setTenantId(TENANT); item.setClientId(CLIENT); item.setOwnerJiacn(OWNER); item.setTaskId(TASK); item.setWorkItemId(WORK);
        item.setTitle("work"); item.setWorkType("implementation"); item.setAssigneeAgentId("agent-a");
        item.setStatus("submitted"); item.setPriority(0); item.setRequiredItem(true);
        item.setAttemptCount(1); item.setMaxAttempts(3); item.setResultArtifactId(MANIFEST);
        item.setSubmittedAt(1_000L); item.setVersion(8L);
        return item;
    }

    private static AgentTaskFormalDeliveryItemEntity item() {
        AgentTaskFormalDeliveryItemEntity item = new AgentTaskFormalDeliveryItemEntity();
        item.setDeliveryId(DELIVERY); item.setArtifactId(MANIFEST); item.setArtifactVersion(1);
        item.setContentHash(HASH); item.setPurpose("manifest"); item.setItemOrder(0);
        return item;
    }
}
