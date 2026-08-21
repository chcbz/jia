package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskWorkspaceDao;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.ArtifactRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.EventRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.RequestRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.WorkItemRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.service.AgentService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskWorkspaceServiceImplTest extends BaseMockTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Mock AgentService agentService;
    @Mock AgentTaskWorkspaceDao dao;

    private AgentTaskWorkspaceServiceImpl service;
    private TaskRow task;
    private MemberRow actor;

    @BeforeEach
    void setUp() {
        service = new AgentTaskWorkspaceServiceImpl(agentService, dao);
        task = task(0L);
        actor = member(ACTOR, "worker", "accepted", 0L);
        when(agentService.requireApiKeyOwnedAgent(CLIENT, TENANT, ACTOR))
                .thenReturn(new AgentRuntimeDTO());
        when(dao.findTask(TENANT, CLIENT, TASK)).thenReturn(task);
        when(dao.findActorMember(TENANT, CLIENT, TASK, ACTOR)).thenReturn(actor);
        when(dao.findMembers(TENANT, CLIENT, TASK)).thenReturn(List.of(actor));
        when(dao.findWorkItems(TENANT, CLIENT, TASK)).thenReturn(List.of());
        when(dao.findOpenRequests(TENANT, CLIENT, TASK)).thenReturn(List.of());
        when(dao.findVisibleArtifacts(
                org.mockito.ArgumentMatchers.eq(TENANT),
                org.mockito.ArgumentMatchers.eq(CLIENT),
                org.mockito.ArgumentMatchers.eq(TASK),
                org.mockito.ArgumentMatchers.eq(ACTOR), anyBoolean(), anyBoolean()))
                .thenReturn(List.of());
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(List.of());
    }

    @Test
    void transactionContractIsSingleRequiredRepeatableReadOnlyBoundary() throws Exception {
        Method method = AgentTaskWorkspaceServiceImpl.class.getMethod(
                "snapshot", String.class, String.class, String.class, String.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRED, tx.propagation());
        assertEquals(Isolation.REPEATABLE_READ, tx.isolation());
        assertTrue(tx.readOnly());
        assertEquals(List.of(Exception.class), List.of(tx.rollbackFor()));
        assertEquals(String.class, AgentTaskWorkspaceDTO.Member.class
                .getDeclaredField("version").getType());
        assertEquals(String.class, AgentTaskWorkspaceDTO.Member.class
                .getDeclaredField("joinedAt").getType());
        assertEquals(String.class, AgentTaskWorkspaceDTO.WorkItem.class
                .getDeclaredField("leaseUntil").getType());
        assertEquals(String.class, AgentTaskWorkspaceDTO.Artifact.class
                .getDeclaredField("createdAt").getType());
        assertEquals(String.class, AgentTaskWorkspaceDTO.Event.class
                .getDeclaredField("occurredAt").getType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "working", "blocked", "done", "failed"})
    void allFrozenReadableActorStatusesAreAccepted(String status) {
        actor.setMemberStatus(status);
        AgentTaskWorkspaceDTO result = service.snapshot(TENANT, CLIENT, TASK, ACTOR);
        assertEquals(status, result.getMembers().get(0).getStatus());
        assertNull(result.getConversationId());
        assertEquals("0", result.getCurrentVersion());
        assertFalse(result.isTimelineTruncated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invited", "rejected", "left", "unknown"})
    void nonReadableOrUnknownActorStatusesUseSameNotFound(String status) {
        actor.setMemberStatus(status);
        AgentTaskWorkspaceException error = assertThrows(AgentTaskWorkspaceException.class,
                () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR));
        assertEquals(AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN,
                error.getReason());
        verify(dao, never()).findWorkItems(TENANT, CLIENT, TASK);
    }

    @Test
    void foreignInactiveOrAbsentOwnedIdentityUsesGenericNotFound() {
        when(agentService.requireApiKeyOwnedAgent(CLIENT, TENANT, ACTOR)).thenThrow(
                new AgentServiceImpl.AgentBizException("AGENT_FORBIDDEN", "internal detail"));
        AgentTaskWorkspaceException error = assertThrows(AgentTaskWorkspaceException.class,
                () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR));
        assertEquals(AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN,
                error.getReason());
        verify(dao, never()).findTask(TENANT, CLIENT, TASK);
    }

    @Test
    void coordinatorMetadataWithoutExactMemberCannotBypassGate() {
        task.setCoordinatorAgentId(ACTOR);
        when(dao.findActorMember(TENANT, CLIENT, TASK, ACTOR)).thenReturn(null);
        AgentTaskWorkspaceException error = assertThrows(AgentTaskWorkspaceException.class,
                () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR));
        assertEquals(AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN,
                error.getReason());
    }

    @Test
    void completeCollectionAccepts499AndRejects500() {
        List<MemberRow> rows = members(499);
        when(dao.findMembers(TENANT, CLIENT, TASK)).thenReturn(rows);
        assertEquals(499, service.snapshot(TENANT, CLIENT, TASK, ACTOR).getMembers().size());

        when(dao.findMembers(TENANT, CLIENT, TASK)).thenReturn(members(500));
        AgentTaskWorkspaceException error = assertThrows(AgentTaskWorkspaceException.class,
                () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR));
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                error.getReason());
    }

    @Test
    void workItemCollectionAccepts499AndRejects500() {
        when(dao.findWorkItems(TENANT, CLIENT, TASK)).thenReturn(workItems(499));
        assertEquals(499, service.snapshot(TENANT, CLIENT, TASK, ACTOR).getWorkItems().size());
        when(dao.findWorkItems(TENANT, CLIENT, TASK)).thenReturn(workItems(500));
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());
    }

    @Test
    void openRequestCollectionAccepts499AndRejects500() {
        when(dao.findOpenRequests(TENANT, CLIENT, TASK)).thenReturn(requests(499));
        assertEquals(499, service.snapshot(TENANT, CLIENT, TASK, ACTOR).getOpenRequests().size());
        when(dao.findOpenRequests(TENANT, CLIENT, TASK)).thenReturn(requests(500));
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());
    }

    @Test
    void artifactsUse100Of101SentinelAndNeverExposeSensitiveColumns() {
        List<ArtifactRow> artifacts = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            artifacts.add(artifact("artifact-" + i, i + 1, ACTOR, "task_members"));
        }
        when(dao.findVisibleArtifacts(
                org.mockito.ArgumentMatchers.eq(TENANT),
                org.mockito.ArgumentMatchers.eq(CLIENT),
                org.mockito.ArgumentMatchers.eq(TASK),
                org.mockito.ArgumentMatchers.eq(ACTOR), anyBoolean(), anyBoolean()))
                .thenReturn(artifacts.subList(0, 100), artifacts);
        AgentTaskWorkspaceDTO exactlyHundred = service.snapshot(TENANT, CLIENT, TASK, ACTOR);
        assertEquals(100, exactlyHundred.getRecentArtifacts().size());
        assertFalse(exactlyHundred.isRecentArtifactsTruncated());
        AgentTaskWorkspaceDTO result = service.snapshot(TENANT, CLIENT, TASK, ACTOR);
        assertEquals(100, result.getRecentArtifacts().size());
        assertTrue(result.isRecentArtifactsTruncated());
        assertEquals("1", result.getRecentArtifacts().get(0).getArtifactVersion());
        try {
            String json = cn.jia.core.util.JsonUtil.getMapper().writeValueAsString(result);
            assertFalse(json.contains("contentHash"));
            assertFalse(json.contains("content"));
            assertFalse(json.contains("storageUri"));
            assertFalse(json.contains("metadata"));
            assertFalse(json.contains("leaseToken"));
            assertFalse(json.contains("eventJson"));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void hiddenArtifactEventPreservesContinuityAsVersionAndRedactedOnly() throws Exception {
        task.setCurrentEventVersion(1L);
        ArtifactRow privateArtifact = artifact("artifact-private", 1, OTHER, "private");
        privateArtifact.setCreatedAt(999L);
        when(dao.findArtifactVersion(TENANT, CLIENT, TASK, "artifact-private", 1))
                .thenReturn(privateArtifact);
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(List.of(
                artifactEvent(1L, "artifact-private", 1)));

        AgentTaskWorkspaceDTO.Event event = service.snapshot(
                TENANT, CLIENT, TASK, ACTOR).getRecentEvents().get(0);
        assertEquals("1", event.getVersion());
        assertEquals(Boolean.TRUE, event.getRedacted());
        assertNull(event.getEventType());
        String json = cn.jia.core.util.JsonUtil.getMapper().writeValueAsString(event);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> serialized =
                cn.jia.core.util.JsonUtil.getMapper().readValue(json, java.util.Map.class);
        assertEquals(java.util.Set.of("version", "redacted"), serialized.keySet());
    }

    @Test
    void versionsAboveJavascriptSafeIntegerRemainExactDecimalStrings() {
        long version = 9_007_199_254_740_993L;
        task.setTaskVersion(version);
        task.setCurrentEventVersion(version);
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(List.of(event(version)));
        AgentTaskWorkspaceDTO result = service.snapshot(TENANT, CLIENT, TASK, ACTOR);
        assertEquals("9007199254740993", result.getTask().getVersion());
        assertEquals("9007199254740993", result.getCurrentVersion());
        assertEquals("9007199254740993", result.getRecentEvents().get(0).getVersion());
    }

    @Test
    void timelineBoundariesZeroOneHundredAndHundredOneAreDeterministic() {
        AgentTaskWorkspaceDTO zero = service.snapshot(TENANT, CLIENT, TASK, ACTOR);
        assertTrue(zero.getRecentEvents().isEmpty());
        assertFalse(zero.isTimelineTruncated());

        task.setCurrentEventVersion(1L);
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(eventsDescending(1));
        assertFalse(service.snapshot(TENANT, CLIENT, TASK, ACTOR).isTimelineTruncated());

        task.setCurrentEventVersion(100L);
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(eventsDescending(100));
        AgentTaskWorkspaceDTO hundred = service.snapshot(TENANT, CLIENT, TASK, ACTOR);
        assertEquals(100, hundred.getRecentEvents().size());
        assertEquals("1", hundred.getRecentEvents().get(0).getVersion());
        assertFalse(hundred.isTimelineTruncated());

        task.setCurrentEventVersion(101L);
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(eventsDescending(101));
        AgentTaskWorkspaceDTO hundredOne = service.snapshot(TENANT, CLIENT, TASK, ACTOR);
        assertEquals(100, hundredOne.getRecentEvents().size());
        assertEquals("2", hundredOne.getRecentEvents().get(0).getVersion());
        assertEquals("101", hundredOne.getRecentEvents().get(99).getVersion());
        assertTrue(hundredOne.isTimelineTruncated());
    }

    @Test
    void hundredFirstTimelineSentinelIsAlsoSemanticallyValidated() {
        task.setCurrentEventVersion(101L);
        List<EventRow> rows = eventsDescending(101);
        EventRow sentinel = rows.get(100);
        sentinel.setAggregateType(TaskEventType.Aggregate.ARTIFACT);
        sentinel.setAggregateId("hidden-corrupt-artifact");
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(rows);
        assertSnapshotUnavailable();
    }

    @Test
    void middleGapAndMissingFinalVersionFailClosed() {
        task.setCurrentEventVersion(3L);
        when(dao.findLatestEvents(TENANT, CLIENT, TASK))
                .thenReturn(List.of(event(3L), event(1L)));
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());

        when(dao.findLatestEvents(TENANT, CLIENT, TASK))
                .thenReturn(List.of(event(2L), event(1L)));
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());
    }


    @Test
    void taskAbsenceOrNonExactScopeIs404ButAuthorizedTaskCorruptionIs503() {
        when(dao.findTask(TENANT, CLIENT, TASK)).thenReturn(null);
        assertEquals(AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());

        TaskRow wrongScope = task(0L);
        wrongScope.setClientId("CLIENT-A");
        when(dao.findTask(TENANT, CLIENT, TASK)).thenReturn(wrongScope);
        assertEquals(AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());

        task.setRewardStatus("RUNNING");
        when(dao.findTask(TENANT, CLIENT, TASK)).thenReturn(task);
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());
        verify(dao).findActorMember(TENANT, CLIENT, TASK, ACTOR);
    }

    @Test
    void taskVersionsAndProjectionCorruptionFailAs503OnlyAfterStrictMemberGate() {
        task.setTaskVersion(null);
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());

        task.setTaskVersion(0L);
        task.setCurrentEventVersion(-1L);
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());

        task.setCurrentEventVersion(0L);
        task.setMaxAgents(0);
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());

        when(dao.findActorMember(TENANT, CLIENT, TASK, ACTOR)).thenReturn(null);
        assertEquals(AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());
    }

    @Test
    void supplementaryTaskBoundaryAndC01hBaselineEventPassCanonicalValidation() {
        String supplementary = new String(Character.toChars(0x1f642));
        String supplementaryTask = "t" + supplementary.repeat(99);
        TaskRow unicodeTask = task(1L);
        unicodeTask.setTaskId(supplementaryTask);
        MemberRow unicodeActor = member(ACTOR, "worker", "accepted", 0L);
        unicodeActor.setTaskId(supplementaryTask);
        EventRow baseline = event(1L);
        baseline.setTaskId(supplementaryTask);
        baseline.setEventType(TaskEventType.HISTORICAL_BASELINE_IMPORTED);
        baseline.setActorType(TaskEventType.ActorType.SYSTEM);
        baseline.setActorId("c01h-b09");
        baseline.setAggregateType(TaskEventType.Aggregate.TASK);
        baseline.setAggregateId(supplementaryTask);
        baseline.setEventJson(TaskEventPayload.builder()
                .put(TaskEventPayload.Key.SOURCE, "b09")
                .put(TaskEventPayload.Key.DECISION_CODE, "c01h_b09_v1")
                .put(TaskEventPayload.Key.CONTENT_SHA256, "a".repeat(64))
                .put(TaskEventPayload.Key.MEMBER_COUNT, 1L)
                .put(TaskEventPayload.Key.WORK_ITEM_COUNT, 0L).toJson());

        when(agentService.requireApiKeyOwnedAgent(CLIENT, TENANT, ACTOR))
                .thenReturn(new AgentRuntimeDTO());
        when(dao.findTask(TENANT, CLIENT, supplementaryTask)).thenReturn(unicodeTask);
        when(dao.findActorMember(TENANT, CLIENT, supplementaryTask, ACTOR))
                .thenReturn(unicodeActor);
        when(dao.findMembers(TENANT, CLIENT, supplementaryTask))
                .thenReturn(List.of(unicodeActor));
        when(dao.findWorkItems(TENANT, CLIENT, supplementaryTask)).thenReturn(List.of());
        when(dao.findOpenRequests(TENANT, CLIENT, supplementaryTask)).thenReturn(List.of());
        when(dao.findVisibleArtifacts(TENANT, CLIENT, supplementaryTask, ACTOR, false, false))
                .thenReturn(List.of());
        when(dao.findLatestEvents(TENANT, CLIENT, supplementaryTask))
                .thenReturn(List.of(baseline));

        AgentTaskWorkspaceDTO snapshot = service.snapshot(
                TENANT, CLIENT, supplementaryTask, ACTOR);
        assertEquals(supplementaryTask, snapshot.getTask().getTaskId());
        assertEquals(TaskEventType.HISTORICAL_BASELINE_IMPORTED,
                snapshot.getRecentEvents().get(0).getEventType());
    }

    @Test
    void overCodePointLimitAndUnpairedSurrogateFailBeforeIdentityOrDaoReads() {
        String supplementary = new String(Character.toChars(0x1f642));
        assertThrows(IllegalArgumentException.class,
                () -> service.snapshot(TENANT, CLIENT,
                        "t" + supplementary.repeat(100), ACTOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.snapshot(TENANT, CLIENT, "task-\ud800", ACTOR));
    }

    @Test
    void canonicalWriterVariantsRemainAcceptedWithoutInventedRequirements() {
        task.setCurrentEventVersion(1L);
        EventRow assigned = event(1L);
        assigned.setEventType(TaskEventType.TASK_ASSIGNED);
        assigned.setEventJson(TaskEventPayload.builder()
                .put(TaskEventPayload.Key.FROM_STATUS, "open")
                .put(TaskEventPayload.Key.TO_STATUS, "assigned")
                .put(TaskEventPayload.Key.EXPECTED_VERSION, 0L)
                .put(TaskEventPayload.Key.RESULT_VERSION, 1L).toJson());
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(List.of(assigned));
        assertEquals(TaskEventType.TASK_ASSIGNED,
                service.snapshot(TENANT, CLIENT, TASK, ACTOR)
                        .getRecentEvents().get(0).getEventType());

        EventRow submitted = event(1L);
        submitted.setEventType(TaskEventType.WORK_ITEM_SUBMITTED);
        submitted.setAggregateType(TaskEventType.Aggregate.WORK_ITEM);
        submitted.setAggregateId("work-1");
        submitted.setEventJson(TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, "work-1")
                .put(TaskEventPayload.Key.FROM_STATUS, "running")
                .put(TaskEventPayload.Key.TO_STATUS, "submitted")
                .put(TaskEventPayload.Key.EXPECTED_VERSION, 0L)
                .put(TaskEventPayload.Key.RESULT_VERSION, 1L).toJson());
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(List.of(submitted));
        assertEquals(TaskEventType.WORK_ITEM_SUBMITTED,
                service.snapshot(TENANT, CLIENT, TASK, ACTOR)
                        .getRecentEvents().get(0).getEventType());
    }

    @Test
    void knownEventTypeCannotUseWrongAggregateActorOrSemanticPayload() {
        task.setCurrentEventVersion(1L);
        EventRow corrupt = event(1L);
        corrupt.setAggregateType(TaskEventType.Aggregate.ARTIFACT);
        corrupt.setAggregateId("artifact-private");
        assertEventUnavailable(corrupt);

        corrupt = event(1L);
        corrupt.setActorType(TaskEventType.ActorType.AGENT);
        corrupt.setActorId(ACTOR);
        assertEventUnavailable(corrupt);

        corrupt = event(1L);
        corrupt.setEventJson(TaskEventPayload.builder()
                .put(TaskEventPayload.Key.FROM_STATUS, "assigned")
                .put(TaskEventPayload.Key.TO_STATUS, "completed")
                .put(TaskEventPayload.Key.EXPECTED_VERSION, 0L)
                .put(TaskEventPayload.Key.RESULT_VERSION, 1L).toJson());
        assertEventUnavailable(corrupt);

        corrupt = event(1L);
        corrupt.setEventJson("{\"fromStatus\":\"assigned\","
                + "\"toStatus\":\"running\",\"toStatus\":\"running\","
                + "\"expectedVersion\":0,\"resultVersion\":1}");
        assertEventUnavailable(corrupt);
    }

    @Test
    void duplicateVersionWrongScopeAndArtifactDisguisedAsTaskEventFailClosed() {
        task.setCurrentEventVersion(2L);
        when(dao.findLatestEvents(TENANT, CLIENT, TASK))
                .thenReturn(List.of(event(2L), event(2L)));
        assertSnapshotUnavailable();

        EventRow wrongScope = event(1L);
        wrongScope.setTenantId("TENANT-A");
        task.setCurrentEventVersion(1L);
        assertEventUnavailable(wrongScope);

        EventRow disguised = event(1L);
        disguised.setAggregateType(TaskEventType.Aggregate.ARTIFACT);
        disguised.setAggregateId("artifact-private");
        disguised.setEventJson(TaskEventPayload.builder()
                .put(TaskEventPayload.Key.ARTIFACT_ID, "artifact-private")
                .put(TaskEventPayload.Key.ARTIFACT_TYPE, "document")
                .put(TaskEventPayload.Key.ARTIFACT_VERSION, 1L)
                .put(TaskEventPayload.Key.VISIBILITY, "private")
                .put(TaskEventPayload.Key.CONTENT_SHA256, "a".repeat(64)).toJson());
        assertEventUnavailable(disguised);
    }

    @Test
    void artifactEventMustMatchExactPersistedProducerTypeVisibilityWorkItemAndScope() {
        task.setCurrentEventVersion(1L);
        ArtifactRow canonical = artifact("artifact-private", 1, OTHER, "private");
        canonical.setWorkItemId("work-1");
        EventRow event = artifactEvent(1L, canonical);

        List<ArtifactRow> corruptRows = new ArrayList<>();
        ArtifactRow producerCase = artifact("artifact-private", 1,
                OTHER.toUpperCase(java.util.Locale.ROOT), "private");
        producerCase.setWorkItemId("work-1");
        corruptRows.add(producerCase);
        ArtifactRow artifactIdCase = artifact("Artifact-private", 1, OTHER, "private");
        artifactIdCase.setWorkItemId("work-1");
        corruptRows.add(artifactIdCase);
        ArtifactRow artifactVersion = artifact("artifact-private", 2, OTHER, "private");
        artifactVersion.setWorkItemId("work-1");
        artifactVersion.setCreatedAt(1001L);
        corruptRows.add(artifactVersion);
        ArtifactRow type = artifact("artifact-private", 1, OTHER, "private");
        type.setArtifactType("image");
        type.setWorkItemId("work-1");
        corruptRows.add(type);
        ArtifactRow visibility = artifact("artifact-private", 1, OTHER, "reviewer");
        visibility.setWorkItemId("work-1");
        corruptRows.add(visibility);
        ArtifactRow workItem = artifact("artifact-private", 1, OTHER, "private");
        workItem.setWorkItemId("work-2");
        corruptRows.add(workItem);
        ArtifactRow scope = artifact("artifact-private", 1, OTHER, "private");
        scope.setWorkItemId("work-1");
        scope.setTaskId("TASK-1");
        corruptRows.add(scope);

        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(List.of(event));
        for (ArtifactRow corrupt : corruptRows) {
            when(dao.findArtifactVersion(TENANT, CLIENT, TASK, "artifact-private", 1))
                    .thenReturn(corrupt);
            assertSnapshotUnavailable();
        }
    }

    @Test
    void artifactAggregateIsAlwaysCanonicalAndAclChecked() {
        task.setCurrentEventVersion(1L);
        EventRow wrongType = artifactEvent(1L,
                artifact("artifact-private", 1, OTHER, "private"));
        wrongType.setEventType(TaskEventType.TASK_STARTED);
        assertEventUnavailable(wrongType);

        ArtifactRow privateArtifact = artifact("artifact-private", 1, OTHER, "private");
        EventRow canonical = artifactEvent(1L, privateArtifact);
        when(dao.findArtifactVersion(TENANT, CLIENT, TASK, "artifact-private", 1))
                .thenReturn(privateArtifact);
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(List.of(canonical));
        AgentTaskWorkspaceDTO.Event projected = service.snapshot(
                TENANT, CLIENT, TASK, ACTOR).getRecentEvents().get(0);
        assertEquals(Boolean.TRUE, projected.getRedacted());
    }


    private void assertEventUnavailable(EventRow row) {
        when(dao.findLatestEvents(TENANT, CLIENT, TASK)).thenReturn(List.of(row));
        assertSnapshotUnavailable();
    }

    private void assertSnapshotUnavailable() {
        assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                assertThrows(AgentTaskWorkspaceException.class,
                        () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR)).getReason());
    }

    private static TaskRow task(long currentVersion) {
        TaskRow row = new TaskRow();
        row.setTenantId(TENANT);
        row.setClientId(CLIENT);
        row.setTaskId(TASK);
        row.setRewardStatus("running");
        row.setCollaborationMode("team");
        row.setRiskLevel("low");
        row.setMaxAgents(5);
        row.setReviewRequired(false);
        row.setTaskVersion(0L);
        row.setCurrentEventVersion(currentVersion);
        return row;
    }

    private static MemberRow member(String id, String role, String status, long version) {
        MemberRow row = new MemberRow();
        row.setTenantId(TENANT);
        row.setClientId(CLIENT);
        row.setTaskId(TASK);
        row.setAgentId(id);
        row.setMemberRole(role);
        row.setMemberStatus(status);
        row.setAssignmentSource("manual");
        row.setVersion(version);
        return row;
    }

    private List<MemberRow> members(int count) {
        List<MemberRow> rows = new ArrayList<>();
        rows.add(actor);
        for (int i = 1; i < count; i++) {
            rows.add(member("agent-" + i, "worker", "accepted", i));
        }
        return rows;
    }

    private static List<WorkItemRow> workItems(int count) {
        List<WorkItemRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WorkItemRow row = new WorkItemRow();
            row.setTenantId(TENANT);
            row.setClientId(CLIENT);
            row.setTaskId(TASK);
            row.setWorkItemId("work-" + i);
            row.setTitle("work");
            row.setWorkType("analysis");
            row.setStatus("ready");
            row.setPriority(i);
            row.setRequiredItem(true);
            row.setAttemptCount(0);
            row.setMaxAttempts(3);
            row.setVersion((long) i);
            rows.add(row);
        }
        return rows;
    }

    private static List<RequestRow> requests(int count) {
        List<RequestRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RequestRow row = new RequestRow();
            row.setTenantId(TENANT);
            row.setClientId(CLIENT);
            row.setTaskId(TASK);
            row.setRequestId("request-" + i);
            row.setRequesterAgentId(ACTOR);
            row.setTargetType("agent");
            row.setTargetId(OTHER);
            row.setRequestType("help");
            row.setStatus("open");
            row.setPriority(i);
            row.setTitle("request");
            row.setDescription("safe");
            row.setVersion((long) i);
            rows.add(row);
        }
        return rows;
    }

    private static ArtifactRow artifact(
            String id, int version, String producer, String visibility) {
        ArtifactRow row = new ArtifactRow();
        row.setTenantId(TENANT);
        row.setClientId(CLIENT);
        row.setTaskId(TASK);
        row.setArtifactId(id);
        row.setProducerAgentId(producer);
        row.setArtifactType("document");
        row.setTitle("safe title");
        row.setArtifactVersion(version);
        row.setVisibility(visibility);
        row.setCreatedAt(1000L + version);
        return row;
    }

    private static EventRow event(long version) {
        EventRow row = new EventRow();
        row.setTenantId(TENANT);
        row.setClientId(CLIENT);
        row.setTaskId(TASK);
        row.setEventVersion(version);
        row.setEventType(TaskEventType.TASK_STARTED);
        row.setActorType(TaskEventType.ActorType.SYSTEM);
        row.setAggregateType(TaskEventType.Aggregate.TASK);
        row.setAggregateId(TASK);
        row.setEventJson(TaskEventPayload.builder()
                .put(TaskEventPayload.Key.FROM_STATUS, "assigned")
                .put(TaskEventPayload.Key.TO_STATUS, "running")
                .put(TaskEventPayload.Key.EXPECTED_VERSION, Math.max(0L, version - 1L))
                .put(TaskEventPayload.Key.RESULT_VERSION, version).toJson());
        row.setOccurredAt(1000L + version);
        return row;
    }

    private static EventRow artifactEvent(long version, String artifactId, int artifactVersion) {
        return artifactEvent(version,
                artifact(artifactId, artifactVersion, OTHER, "private"));
    }

    private static EventRow artifactEvent(long version, ArtifactRow artifact) {
        EventRow row = event(version);
        row.setEventType(TaskEventType.ARTIFACT_PUBLISHED);
        row.setActorType(TaskEventType.ActorType.AGENT);
        row.setActorId(artifact.getProducerAgentId());
        row.setAggregateType(TaskEventType.Aggregate.ARTIFACT);
        row.setAggregateId(artifact.getArtifactId());
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.ARTIFACT_ID, artifact.getArtifactId())
                .put(TaskEventPayload.Key.ARTIFACT_TYPE, artifact.getArtifactType())
                .put(TaskEventPayload.Key.ARTIFACT_VERSION,
                        artifact.getArtifactVersion().longValue())
                .put(TaskEventPayload.Key.VISIBILITY, artifact.getVisibility())
                .put(TaskEventPayload.Key.CONTENT_SHA256, "a".repeat(64));
        if (artifact.getWorkItemId() != null) {
            payload.put(TaskEventPayload.Key.WORK_ITEM_ID, artifact.getWorkItemId());
        }
        row.setEventJson(payload.toJson());
        row.setOccurredAt(artifact.getCreatedAt());
        return row;
    }

    private static List<EventRow> eventsDescending(int current) {
        List<EventRow> rows = new ArrayList<>();
        for (long version = current; version >= 1; version--) {
            rows.add(event(version));
        }
        return rows;
    }
}
