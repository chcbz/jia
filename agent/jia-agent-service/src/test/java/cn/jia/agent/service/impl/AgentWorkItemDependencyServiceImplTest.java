package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentWorkItemDependencyException;
import cn.jia.agent.exception.AgentWorkItemDependencyException.Reason;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkItemDependencyServiceImplTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final long NOW = 9_000L;

    @Test
    void locksCompleteGraphThenUnlocksOnlyNextChainNodeWithBoundedEvent() {
        Fixture fixture = fixture(root("running"));
        AgentTaskWorkItemEntity first = completed("work-a", null, 2L);
        AgentTaskWorkItemEntity second = pending("work-b", "[\"work-a\"]", 4L);
        AgentTaskWorkItemEntity third = pending("work-c", "[\"work-b\"]", 6L);
        when(fixture.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(third, first, second));
        when(fixture.dao.readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("work-b"), eq(4L), eq(NOW), any()))
                .thenReturn(1);

        var result = fixture.service.resolveReady(TENANT, CLIENT, TASK);

        assertEquals(List.of("work-b"), result.getReadyWorkItemIds());
        assertEquals(3, result.getInspectedWorkItemCount());
        assertEquals(2, result.getPendingWorkItemCount());
        assertTrue(result.getChanged());
        ArgumentCaptor<AgentTaskWorkItemDTO> update =
                ArgumentCaptor.forClass(AgentTaskWorkItemDTO.class);
        verify(fixture.dao).readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("work-b"), eq(4L), eq(NOW),
                update.capture());
        assertEquals("ready", update.getValue().getStatus());
        assertEquals("[\"work-a\"]", update.getValue().getDependencyJson());
        assertEquals(4L, update.getValue().getVersion());

        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(fixture.eventWriter).append(event.capture());
        assertEquals(TaskEventType.WORK_ITEM_READY, event.getValue().getEventType());
        assertEquals(TaskEventType.ActorType.SYSTEM, event.getValue().getActorType());
        assertEquals(null, event.getValue().getActorId());
        assertEquals("work_item", event.getValue().getAggregateType());
        assertEquals("work-b", event.getValue().getAggregateId());
        assertTrue(event.getValue().getEventJson().contains("dependency_completion"));
        assertFalse(event.getValue().getEventJson().contains("work-a"),
                "event payload must not expose dependency graph contents");

        var order = inOrder(fixture.transaction, fixture.dao, fixture.eventWriter);
        order.verify(fixture.transaction).executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any());
        order.verify(fixture.dao).listByTaskForUpdate(TENANT, CLIENT, TASK, 501);
        order.verify(fixture.dao).readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("work-b"), eq(4L), eq(NOW), any());
        order.verify(fixture.eventWriter).append(any());
    }

    @Test
    void diamondUnlocksReadyNodesInUnsignedUtf8OrderButNotTheirDependent() {
        Fixture fixture = fixture(root("running"));
        AgentTaskWorkItemEntity root = completed("root", "[]", 1L);
        AgentTaskWorkItemEntity left = pending("é-left", "[\"root\"]", 2L);
        AgentTaskWorkItemEntity right = pending("z-right", "[\"root\"]", 3L);
        AgentTaskWorkItemEntity join = pending(
                "join", "[\"z-right\",\"é-left\"]", 4L);
        when(fixture.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(left, join, right, root));
        when(fixture.dao.readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), any(), anyLong(), eq(NOW), any()))
                .thenReturn(1);

        var result = fixture.service.resolveReady(TENANT, CLIENT, TASK);

        assertEquals(List.of("z-right", "é-left"), result.getReadyWorkItemIds());
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(fixture.dao, times(2)).readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), ids.capture(), anyLong(), eq(NOW), any());
        assertEquals(List.of("z-right", "é-left"), ids.getAllValues());
    }

    @Test
    void submittedFailedCancelledAndClaimedPrerequisitesNeverUnlock() {
        Fixture fixture = fixture(root("running"));
        AgentTaskWorkItemEntity submitted = item("submitted", "submitted", null, 1L);
        submitted.setResultArtifactId("artifact-submitted");
        submitted.setSubmittedAt(100L);
        AgentTaskWorkItemEntity failed = item("failed", "failed", null, 2L);
        AgentTaskWorkItemEntity cancelled = item("cancelled", "cancelled", null, 3L);
        AgentTaskWorkItemEntity claimed = item("claimed", "claimed", null, 4L);
        claimed.setAssigneeAgentId("agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        claimed.setLeaseToken("lease-live");
        claimed.setLeaseUntil(1L);
        when(fixture.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501)).thenReturn(List.of(
                submitted, failed, cancelled, claimed,
                pending("after-submitted", "[\"submitted\"]", 5L),
                pending("after-failed", "[\"failed\"]", 6L),
                pending("after-cancelled", "[\"cancelled\"]", 7L),
                pending("after-claimed", "[\"claimed\"]", 8L)));

        var result = fixture.service.resolveReady(TENANT, CLIENT, TASK);

        assertEquals(List.of(), result.getReadyWorkItemIds());
        assertFalse(result.getChanged());
        verify(fixture.dao, never()).readyPendingByVersion(
                any(), any(), any(), any(), anyLong(), anyLong(), any());
        verify(fixture.eventWriter, never()).append(any());
    }

    @Test
    void authoritativeCompletionRequiresAcceptedArtifactAndPositiveSubmissionAndCompletionTimes() {
        Fixture missingArtifact = fixture(root("running"));
        AgentTaskWorkItemEntity completed = completed("dependency", null, 1L);
        completed.setResultArtifactId(null);
        when(missingArtifact.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(completed, pending("dependent", "[\"dependency\"]", 2L)));
        assertReason(Reason.INVALID_PERSISTED_STATE,
                () -> missingArtifact.service.resolveReady(TENANT, CLIENT, TASK));

        Fixture missingTime = fixture(root("running"));
        AgentTaskWorkItemEntity noTime = completed("dependency", null, 1L);
        noTime.setCompletedAt(0L);
        when(missingTime.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(noTime, pending("dependent", "[\"dependency\"]", 2L)));
        assertReason(Reason.INVALID_PERSISTED_STATE,
                () -> missingTime.service.resolveReady(TENANT, CLIENT, TASK));

        Fixture missingSubmission = fixture(root("running"));
        AgentTaskWorkItemEntity noSubmission = completed("dependency", null, 1L);
        noSubmission.setSubmittedAt(null);
        when(missingSubmission.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(noSubmission, pending("dependent", "[\"dependency\"]", 2L)));
        assertReason(Reason.INVALID_PERSISTED_STATE,
                () -> missingSubmission.service.resolveReady(TENANT, CLIENT, TASK));
    }

    @Test
    void strictGraphValidationRejectsMalformedDuplicateSelfUnknownCrossScopeAndCycles() {
        assertInvalidRows(List.of(
                pending("a", "[\"missing\",]", 1L)));
        assertInvalidRows(List.of(
                pending("a", " []", 1L)));
        assertInvalidRows(List.of(
                pending("a", "[] false", 1L)));
        assertInvalidRows(List.of(
                pending("a", "[1]", 1L)));
        assertInvalidRows(List.of(
                pending("a", "[\"b\",\"b\"]", 1L), ready("b", null, 1L)));
        assertInvalidRows(List.of(
                pending("a", "[\"a\"]", 1L)));
        assertInvalidRows(List.of(
                pending("a", "[\"unknown\"]", 1L)));
        AgentTaskWorkItemEntity crossScope = ready("b", null, 1L);
        crossScope.setTenantId("tenant-other");
        assertInvalidRows(List.of(pending("a", "[\"b\"]", 1L), crossScope));
        assertInvalidRows(List.of(
                pending("a", "[\"b\"]", 1L), pending("b", "[\"a\"]", 1L)));
        AgentTaskWorkItemEntity exhausted = pending("exhausted", "[]", 1L);
        exhausted.setAttemptCount(3);
        assertInvalidRows(List.of(exhausted));
    }

    @Test
    void graphBoundAndDuplicatePersistedIdsFailClosedBeforeAnyMutation() {
        List<AgentTaskWorkItemEntity> tooMany = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            tooMany.add(ready("work-" + index, null, 1L));
        }
        assertInvalidRows(tooMany);
        assertInvalidRows(List.of(ready("same", null, 1L), ready("same", null, 2L)));
    }

    @Test
    void terminalTaskStillValidatesGraphButNeverSchedules() {
        Fixture fixture = fixture(root("completed"));
        when(fixture.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(pending("independent", "[]", 1L)));

        var result = fixture.service.resolveReady(TENANT, CLIENT, TASK);

        assertFalse(result.getChanged());
        verify(fixture.dao, never()).readyPendingByVersion(
                any(), any(), any(), any(), anyLong(), anyLong(), any());

        Fixture malformed = fixture(root("cancelled"));
        when(malformed.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(pending("bad", "not-json", 1L)));
        assertReason(Reason.INVALID_PERSISTED_STATE,
                () -> malformed.service.resolveReady(TENANT, CLIENT, TASK));
    }

    @Test
    void replayIsIdempotentAndSecondCompletedSnapshotCatchesUpConcurrentDependencies() {
        Fixture fixture = fixture(root("running"));
        AgentTaskWorkItemEntity firstCompleted = completed("first", null, 1L);
        AgentTaskWorkItemEntity secondSubmitted = item("second", "submitted", null, 2L);
        secondSubmitted.setResultArtifactId("artifact-second");
        secondSubmitted.setSubmittedAt(200L);
        AgentTaskWorkItemEntity dependent = pending(
                "dependent", "[\"first\",\"second\"]", 3L);
        AgentTaskWorkItemEntity secondCompleted = completed("second", null, 3L);
        when(fixture.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(firstCompleted, secondSubmitted, dependent))
                .thenReturn(List.of(firstCompleted, secondCompleted, dependent))
                .thenReturn(List.of(firstCompleted, secondCompleted, ready("dependent",
                        "[\"first\",\"second\"]", 4L)));
        when(fixture.dao.readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("dependent"), eq(3L), eq(NOW), any()))
                .thenReturn(1);

        assertFalse(fixture.service.resolveReady(TENANT, CLIENT, TASK).getChanged());
        assertEquals(List.of("dependent"),
                fixture.service.resolveReady(TENANT, CLIENT, TASK).getReadyWorkItemIds());
        assertFalse(fixture.service.resolveReady(TENANT, CLIENT, TASK).getChanged());

        verify(fixture.dao, times(1)).readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("dependent"), eq(3L), eq(NOW), any());
        verify(fixture.eventWriter, times(1)).append(any());
    }

    @Test
    void casConflictAndEventFailurePropagateToRequiredRollbackBoundary() {
        Fixture conflict = fixture(root("running"));
        when(conflict.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(pending("ready-now", "[]", 5L)));
        when(conflict.dao.readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("ready-now"), eq(5L), eq(NOW), any()))
                .thenReturn(0);
        assertReason(Reason.VERSION_CONFLICT,
                () -> conflict.service.resolveReady(TENANT, CLIENT, TASK));
        verify(conflict.eventWriter, never()).append(any());

        Fixture unexpectedCount = fixture(root("running"));
        when(unexpectedCount.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(pending("ready-now", "[]", 5L)));
        when(unexpectedCount.dao.readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("ready-now"), eq(5L), eq(NOW), any()))
                .thenReturn(2);
        assertReason(Reason.INVALID_PERSISTED_STATE,
                () -> unexpectedCount.service.resolveReady(TENANT, CLIENT, TASK));
        verify(unexpectedCount.eventWriter, never()).append(any());

        Fixture eventFailure = fixture(root("running"));
        when(eventFailure.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(pending("ready-now", "[]", 5L)));
        when(eventFailure.dao.readyPendingByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("ready-now"), eq(5L), eq(NOW), any()))
                .thenReturn(1);
        when(eventFailure.eventWriter.append(any()))
                .thenThrow(new IllegalStateException("event append failed"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> eventFailure.service.resolveReady(TENANT, CLIENT, TASK));
        assertEquals("event append failed", failure.getMessage());
    }

    @Test
    void nonActiveStaleLeaseFailsClosedAndDoesNotTakeOverB04OrD06() {
        Fixture fixture = fixture(root("running"));
        AgentTaskWorkItemEntity stale = ready("stale", null, 2L);
        stale.setLeaseToken("old-token");
        stale.setLeaseUntil(1L);
        when(fixture.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501))
                .thenReturn(List.of(stale, pending("dependent", "[\"stale\"]", 3L)));

        assertReason(Reason.INVALID_PERSISTED_STATE,
                () -> fixture.service.resolveReady(TENANT, CLIENT, TASK));

        verify(fixture.dao, never()).readyPendingByVersion(
                any(), any(), any(), any(), anyLong(), anyLong(), any());
        verify(fixture.eventWriter, never()).append(any());
    }

    @Test
    void invalidRequestAndOutOfScopeRootFailClosed() {
        Fixture fixture = fixture(root("running"));
        assertReason(Reason.INVALID_REQUEST,
                () -> fixture.service.resolveReady(" tenant-a", CLIENT, TASK));
        verify(fixture.transaction, never()).executeWithLockedTaskRoot(any(), any(), any(), any());

        AgentTaskMetaEntity wrongRoot = root("running");
        wrongRoot.setClientId("client-other");
        Fixture outOfScope = fixture(wrongRoot);
        assertReason(Reason.INVALID_PERSISTED_STATE,
                () -> outOfScope.service.resolveReady(TENANT, CLIENT, TASK));
        verify(outOfScope.dao, never()).listByTaskForUpdate(any(), any(), any(), anyInt());
    }

    private void assertInvalidRows(List<AgentTaskWorkItemEntity> rows) {
        Fixture fixture = fixture(root("running"));
        when(fixture.dao.listByTaskForUpdate(TENANT, CLIENT, TASK, 501)).thenReturn(rows);
        assertReason(Reason.INVALID_PERSISTED_STATE,
                () -> fixture.service.resolveReady(TENANT, CLIENT, TASK));
        verify(fixture.dao, never()).readyPendingByVersion(
                any(), any(), any(), any(), anyLong(), anyLong(), any());
        verify(fixture.eventWriter, never()).append(any());
    }

    private void assertReason(Reason reason, Runnable action) {
        AgentWorkItemDependencyException failure = assertThrows(
                AgentWorkItemDependencyException.class, action::run);
        assertEquals(reason, failure.getReason());
    }

    private Fixture fixture(AgentTaskMetaEntity root) {
        AgentTaskWorkItemDao dao = mock(AgentTaskWorkItemDao.class);
        AgentTaskMutationTransaction transaction = mock(AgentTaskMutationTransaction.class);
        AgentTaskEventWriter eventWriter = mock(AgentTaskEventWriter.class);
        when(transaction.executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any())).thenAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(3);
            return mutation.apply(root);
        });
        AgentWorkItemDependencyServiceImpl service = new AgentWorkItemDependencyServiceImpl(
                dao, transaction, eventWriter, () -> NOW);
        return new Fixture(dao, transaction, eventWriter, service);
    }

    private AgentTaskMetaEntity root(String status) {
        AgentTaskMetaEntity root = new AgentTaskMetaEntity();
        root.setTenantId(TENANT);
        root.setClientId(CLIENT);
        root.setTaskId(TASK);
        root.setRewardStatus(status);
        root.setTaskVersion(1L);
        root.setCurrentEventVersion(2L);
        return root;
    }

    private AgentTaskWorkItemEntity pending(String id, String dependencies, long version) {
        return item(id, "pending", dependencies, version);
    }

    private AgentTaskWorkItemEntity ready(String id, String dependencies, long version) {
        return item(id, "ready", dependencies, version);
    }

    private AgentTaskWorkItemEntity completed(String id, String dependencies, long version) {
        AgentTaskWorkItemEntity item = item(id, "completed", dependencies, version);
        item.setResultArtifactId("artifact-" + id);
        item.setSubmittedAt(400L);
        item.setCompletedAt(500L);
        return item;
    }

    private AgentTaskWorkItemEntity item(
            String id, String status, String dependencies, long version) {
        AgentTaskWorkItemEntity item = new AgentTaskWorkItemEntity();
        item.setId(version + 100L);
        item.setTenantId(TENANT);
        item.setClientId(CLIENT);
        item.setTaskId(TASK);
        item.setWorkItemId(id);
        item.setTitle("title-" + id);
        item.setWorkType("implementation");
        item.setStatus(status);
        item.setPriority(0);
        item.setRequiredItem(true);
        item.setDependencyJson(dependencies);
        item.setAttemptCount(0);
        item.setMaxAttempts(3);
        item.setVersion(version);
        return item;
    }

    private record Fixture(
            AgentTaskWorkItemDao dao,
            AgentTaskMutationTransaction transaction,
            AgentTaskEventWriter eventWriter,
            AgentWorkItemDependencyServiceImpl service) {
    }
}
