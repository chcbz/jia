package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliverySubmitDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskFormalDeliveryServiceImplTest {
    private static final String TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-a";
    private static final String WORK = "work-a";
    private static final String AGENT = "agent-a";
    private static final String DELIVERY = "delivery-a";
    private static final String RUN = "run-a";
    private static final String TOKEN = "lease-a";
    private static final String ARTIFACT = "manifest-a";
    private static final String HASH = "a".repeat(64);

    private AgentWorkItemLeaseService leaseService;
    private AgentTaskArtifactDao artifactDao;
    private AgentTaskFormalDeliveryDao deliveryDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentTaskMetaDao taskMetaDao;
    private AgentTaskMutationTransaction transaction;
    private AgentTaskEventWriter eventWriter;
    private AgentTaskFormalDeliveryServiceImpl service;

    @BeforeEach
    void setUp() {
        leaseService = mock(AgentWorkItemLeaseService.class);
        artifactDao = mock(AgentTaskArtifactDao.class);
        deliveryDao = mock(AgentTaskFormalDeliveryDao.class);
        workItemDao = mock(AgentTaskWorkItemDao.class);
        taskMetaDao = mock(AgentTaskMetaDao.class);
        transaction = mock(AgentTaskMutationTransaction.class);
        eventWriter = mock(AgentTaskEventWriter.class);
        when(transaction.executeWithLockedTaskRootInOwnerScope(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(4);
                    return mutation.apply(root());
                });
        service = new AgentTaskFormalDeliveryServiceImpl(leaseService, artifactDao, deliveryDao,
                workItemDao, taskMetaDao, transaction, eventWriter, () -> 1_000L);
    }

    @Test
    void pinsExactArtifactsThenAtomicallySubmitsWorkAndTask() {
        when(deliveryDao.findForUpdate(TENANT, CLIENT, DELIVERY)).thenReturn(null);
        when(deliveryDao.findLatestTaskForUpdate(TENANT, CLIENT, TASK)).thenReturn(null);
        when(workItemDao.listByTaskForUpdate(TENANT, CLIENT, OWNER, TASK, 2)).thenReturn(List.of(workItem()));
        when(leaseService.validateLeaseForResult(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), any()))
                .thenReturn(lease());
        when(artifactDao.findVersion(TENANT, CLIENT, OWNER, TASK, ARTIFACT, 1)).thenReturn(artifact());
        when(deliveryDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(deliveryDao.insertItem(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(workItemDao.updateActiveLeaseByVersion(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK),
                eq(AGENT), eq(TOKEN), eq("running"), eq(2_000L), eq(7L), anyLong(), any()))
                .thenReturn(1);
        when(taskMetaDao.updateStatusByVersionInOwnerScope(TENANT, CLIENT, OWNER, TASK, 3L, "reviewing",
                100L, null, null)).thenReturn(1);
        when(deliveryDao.listItems(TENANT, CLIENT, DELIVERY)).thenReturn(List.of(persistedItem()));

        var result = service.submit(TENANT, CLIENT, OWNER, TASK, AGENT, command());

        assertEquals("submitted", result.getState());
        assertEquals(4L, result.getTaskVersion());
        assertEquals(8L, result.getWorkItemVersion());
        assertFalse(result.getReplayed());
        ArgumentCaptor<AgentTaskFormalDeliveryEntity> delivery =
                ArgumentCaptor.forClass(AgentTaskFormalDeliveryEntity.class);
        verify(deliveryDao).insert(eq(TENANT), eq(CLIENT), delivery.capture());
        assertEquals(1L, delivery.getValue().getRevision());
        assertEquals(commandDigest("formal delivery summary"), delivery.getValue().getSubmissionDigest());

        ArgumentCaptor<AgentTaskEventWriteCommand> events =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter, org.mockito.Mockito.times(3)).append(events.capture());
        assertEquals(List.of(TaskEventType.FORMAL_DELIVERY_SUBMITTED,
                TaskEventType.WORK_ITEM_SUBMITTED, TaskEventType.TASK_REVIEWING),
                events.getAllValues().stream().map(AgentTaskEventWriteCommand::getEventType).toList());
        assertFalse(events.getAllValues().stream()
                .anyMatch(event -> event.getEventJson().contains(TOKEN)));

        var order = inOrder(deliveryDao, workItemDao, taskMetaDao, eventWriter);
        order.verify(deliveryDao).insert(eq(TENANT), eq(CLIENT), any());
        order.verify(workItemDao).updateActiveLeaseByVersion(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK),
                eq(WORK), eq(AGENT), eq(TOKEN), eq("running"), eq(2_000L), eq(7L), anyLong(), any());
        order.verify(taskMetaDao).updateStatusByVersionInOwnerScope(TENANT, CLIENT, OWNER, TASK, 3L, "reviewing",
                100L, null, null);
        order.verify(eventWriter, org.mockito.Mockito.times(3)).append(any());
    }

    @Test
    void changesRequestedDeliveryCreatesTheNextImmutableRevision() {
        AgentTaskFormalDeliveryEntity previous = delivery();
        previous.setState("changes_requested");
        previous.setVersion(1L);
        previous.setReviewedByJiacn(OWNER);
        previous.setReviewReason("needs revision");
        previous.setReviewedAt(900L);
        AgentTaskFormalDeliverySubmitDTO rework = command();
        rework.setDeliveryId("delivery-b");
        rework.setRunId("run-b");
        rework.setExpectedTaskVersion(5L);
        rework.setExpectedWorkItemVersion(9L);
        when(transaction.executeWithLockedTaskRootInOwnerScope(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(4);
                    AgentTaskMetaEntity root = root();
                    root.setTaskVersion(5L);
                    return mutation.apply(root);
                });
        AgentTaskWorkItemEntity reworkItem = workItem();
        reworkItem.setVersion(9L);
        when(deliveryDao.findForUpdate(TENANT, CLIENT, "delivery-b")).thenReturn(null);
        when(deliveryDao.findLatestTaskForUpdate(TENANT, CLIENT, TASK)).thenReturn(previous);
        when(workItemDao.listByTaskForUpdate(TENANT, CLIENT, OWNER, TASK, 2)).thenReturn(List.of(reworkItem));
        AgentWorkItemLeaseDTO reworkLease = lease();
        reworkLease.setVersion(9L);
        when(leaseService.validateLeaseForResult(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), any()))
                .thenReturn(reworkLease);
        when(artifactDao.findVersion(TENANT, CLIENT, OWNER, TASK, ARTIFACT, 1)).thenReturn(artifact());
        when(deliveryDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(deliveryDao.insertItem(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(workItemDao.updateActiveLeaseByVersion(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK),
                eq(AGENT), eq(TOKEN), eq("running"), eq(2_000L), eq(9L), anyLong(), any()))
                .thenReturn(1);
        when(taskMetaDao.updateStatusByVersionInOwnerScope(TENANT, CLIENT, OWNER, TASK, 5L, "reviewing",
                100L, null, null)).thenReturn(1);
        AgentTaskFormalDeliveryItemEntity item = persistedItem();
        item.setDeliveryId("delivery-b");
        when(deliveryDao.listItems(TENANT, CLIENT, "delivery-b")).thenReturn(List.of(item));

        var result = service.submit(TENANT, CLIENT, OWNER, TASK, AGENT, rework);

        assertEquals(2L, result.getRevision());
        ArgumentCaptor<AgentTaskFormalDeliveryEntity> inserted =
                ArgumentCaptor.forClass(AgentTaskFormalDeliveryEntity.class);
        verify(deliveryDao).insert(eq(TENANT), eq(CLIENT), inserted.capture());
        assertEquals(2L, inserted.getValue().getRevision());
        assertEquals(DELIVERY, inserted.getValue().getSupersedesDeliveryId());
    }

    @Test
    void successfulReplayIsReturnedBeforeLeaseValidation() {
        AgentTaskFormalDeliveryEntity existing = delivery();
        when(deliveryDao.findForUpdate(TENANT, CLIENT, DELIVERY)).thenReturn(existing);
        when(deliveryDao.listItems(TENANT, CLIENT, DELIVERY)).thenReturn(List.of(persistedItem()));

        var result = service.submit(TENANT, CLIENT, OWNER, TASK, AGENT, command());

        assertEquals(DELIVERY, result.getDeliveryId());
        assertEquals(true, result.getReplayed());
        verify(leaseService, never()).validateLeaseForResult(any(), any(), any(), any(), any(), any());
        verify(workItemDao, never()).updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong(), anyLong(), any());
        verify(eventWriter, never()).append(any());
    }

    @Test
    void replayWithChangedSummaryConflictsWithoutTouchingLease() {
        when(deliveryDao.findForUpdate(TENANT, CLIENT, DELIVERY)).thenReturn(delivery());
        AgentTaskFormalDeliverySubmitDTO changed = command();
        changed.setSummary("changed summary");

        AgentTaskCollaborationException failure = assertThrows(AgentTaskCollaborationException.class,
                () -> service.submit(TENANT, CLIENT, OWNER, TASK, AGENT, changed));

        assertEquals(AgentTaskCollaborationException.Reason.VERSION_CONFLICT, failure.getReason());
        verify(leaseService, never()).validateLeaseForResult(any(), any(), any(), any(), any(), any());
    }

    private static AgentTaskFormalDeliverySubmitDTO command() {
        AgentTaskFormalDeliveryItemDTO item = new AgentTaskFormalDeliveryItemDTO();
        item.setArtifactId(ARTIFACT);
        item.setArtifactVersion(1);
        item.setContentHash(HASH);
        item.setPurpose("manifest");
        AgentTaskFormalDeliverySubmitDTO command = new AgentTaskFormalDeliverySubmitDTO();
        command.setDeliveryId(DELIVERY);
        command.setRunId(RUN);
        command.setWorkItemId(WORK);
        command.setLeaseToken(TOKEN);
        command.setExpectedTaskVersion(3L);
        command.setExpectedWorkItemVersion(7L);
        command.setSummary("formal delivery summary");
        command.setManifestArtifactId(ARTIFACT);
        command.setManifestArtifactVersion(1);
        command.setItems(List.of(item));
        return command;
    }

    private static AgentTaskMetaEntity root() {
        AgentTaskMetaEntity root = new AgentTaskMetaEntity();
        root.setTenantId(TENANT);
        root.setClientId(CLIENT);
        root.setOwnerJiacn(OWNER);
        root.setTaskId(TASK);
        root.setAssignedAgentId(AGENT);
        root.setRewardStatus("running");
        root.setTaskVersion(3L);
        root.setCurrentEventVersion(4L);
        root.setStartedAt(100L);
        return root;
    }

    private static AgentTaskWorkItemEntity workItem() {
        AgentTaskWorkItemEntity item = new AgentTaskWorkItemEntity();
        item.setTenantId(TENANT);
        item.setClientId(CLIENT);
        item.setOwnerJiacn(OWNER);
        item.setTaskId(TASK);
        item.setWorkItemId(WORK);
        item.setTitle("work");
        item.setWorkType("implementation");
        item.setAssigneeAgentId(AGENT);
        item.setStatus("running");
        item.setPriority(0);
        item.setRequiredItem(true);
        item.setLeaseToken(TOKEN);
        item.setLeaseUntil(2_000L);
        item.setAttemptCount(1);
        item.setMaxAttempts(3);
        item.setVersion(7L);
        return item;
    }

    private static AgentWorkItemLeaseDTO lease() {
        AgentWorkItemLeaseDTO lease = new AgentWorkItemLeaseDTO();
        lease.setTaskId(TASK);
        lease.setWorkItemId(WORK);
        lease.setAgentId(AGENT);
        lease.setStatus("running");
        lease.setLeaseToken(TOKEN);
        lease.setLeaseUntil(2_000L);
        lease.setVersion(7L);
        return lease;
    }

    private static AgentTaskArtifactEntity artifact() {
        AgentTaskArtifactEntity artifact = new AgentTaskArtifactEntity();
        artifact.setTenantId(TENANT);
        artifact.setClientId(CLIENT);
        artifact.setOwnerJiacn(OWNER);
        artifact.setTaskId(TASK);
        artifact.setWorkItemId(WORK);
        artifact.setProducerAgentId(AGENT);
        artifact.setArtifactId(ARTIFACT);
        artifact.setArtifactVersion(1);
        artifact.setArtifactType("summary");
        artifact.setContentHash(HASH);
        return artifact;
    }

    private static AgentTaskFormalDeliveryEntity delivery() {
        AgentTaskFormalDeliveryEntity delivery = new AgentTaskFormalDeliveryEntity();
        delivery.setTenantId(TENANT);
        delivery.setClientId(CLIENT);
        delivery.setTaskId(TASK);
        delivery.setWorkItemId(WORK);
        delivery.setDeliveryId(DELIVERY);
        delivery.setRevision(1L);
        delivery.setProducerAgentId(AGENT);
        delivery.setRunId(RUN);
        delivery.setSummary("formal delivery summary");
        delivery.setState("submitted");
        delivery.setSubmissionDigest(commandDigest("formal delivery summary"));
        delivery.setManifestArtifactId(ARTIFACT);
        delivery.setManifestArtifactVersion(1);
        delivery.setSubmittedAt(1_000L);
        delivery.setVersion(0L);
        return delivery;
    }

    private static String commandDigest(String summary) {
        // Persisted protocol domain prefix contains literal backslash-n; do not migrate stored digests.
        StringBuilder canonical = new StringBuilder("formal-delivery-r2\\n");
        for (String value : List.of(DELIVERY, RUN, WORK, "3", "7", summary, ARTIFACT, "1",
                ARTIFACT, "1", HASH, "manifest")) {
            canonical.append(value.length()).append(':').append(value).append('\n');
        }
        return TaskEventPayload.ContentDigest.fromUtf8(canonical.toString()).sha256();
    }

    private static AgentTaskFormalDeliveryItemEntity persistedItem() {
        AgentTaskFormalDeliveryItemEntity item = new AgentTaskFormalDeliveryItemEntity();
        item.setDeliveryId(DELIVERY);
        item.setArtifactId(ARTIFACT);
        item.setArtifactVersion(1);
        item.setContentHash(HASH);
        item.setPurpose("manifest");
        item.setItemOrder(0);
        return item;
    }
}
