package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskArtifactOutcomeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskAcceptedArtifactRow;
import cn.jia.agent.entity.AgentTaskArtifactAcceptDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeDecisionEntity;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeEntity;
import cn.jia.agent.entity.AgentTaskArtifactRefDTO;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskArtifactOutcomeServiceImplTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final long NOW = 1_726_000_000_000L;

    private AgentTaskArtifactDao artifactDao;
    private AgentTaskArtifactOutcomeDao outcomeDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskMetaDao taskDao;
    private AgentTaskMutationTransaction transaction;
    private AgentTaskEventWriter eventWriter;
    private AgentTaskArtifactOutcomeServiceImpl service;
    private AgentTaskMetaEntity root;

    @BeforeEach
    void setUp() {
        artifactDao = mock(AgentTaskArtifactDao.class);
        outcomeDao = mock(AgentTaskArtifactOutcomeDao.class);
        memberDao = mock(AgentTaskMemberDao.class);
        taskDao = mock(AgentTaskMetaDao.class);
        transaction = mock(AgentTaskMutationTransaction.class);
        eventWriter = mock(AgentTaskEventWriter.class);
        root = task(ACTOR);
        when(transaction.executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any())).thenAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(3);
            return mutation.apply(root);
        });
        when(outcomeDao.insertDecision(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        service = new AgentTaskArtifactOutcomeServiceImpl(
                artifactDao, outcomeDao, memberDao, taskDao, transaction, eventWriter, () -> NOW);
    }

    @Test
    void coordinatorAcceptsLatestAndSupersedesExplicitConflictsInDeterministicOrder() {
        AgentTaskArtifactEntity accepted = artifact("artifact-z", 1, "work-1", "analysis", OTHER);
        AgentTaskArtifactEntity oldA = artifact("artifact-a", 1, "work-1", "analysis", OTHER);
        AgentTaskArtifactEntity oldB = artifact("artifact-b", 1, "work-1", "analysis", OTHER);
        stubArtifacts(accepted, oldB, oldA);
        AgentTaskArtifactOutcomeEntity priorAccepted = outcome(
                oldA, "accepted", 1, "prior-decision", null, null);
        when(outcomeDao.findForUpdate(TENANT, CLIENT, TASK, "artifact-a", 1))
                .thenReturn(priorAccepted);
        when(outcomeDao.findForUpdate(TENANT, CLIENT, TASK, "artifact-b", 1))
                .thenReturn(null);
        when(outcomeDao.findForUpdate(TENANT, CLIENT, TASK, "artifact-z", 1))
                .thenReturn(null);
        when(outcomeDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(outcomeDao.updateByVersion(eq(TENANT), eq(CLIENT), eq(TASK),
                eq("artifact-a"), eq(1), eq("accepted"), eq(1L), any())).thenReturn(1);

        var result = service.accept(TENANT, CLIENT, TASK, ACTOR,
                command("decision-1", ref("artifact-z", 1, 0),
                        ref("artifact-b", 1, 0), ref("artifact-a", 1, 1)));

        assertEquals("artifact-z", result.getArtifactId());
        assertEquals("accepted", result.getOutcomeState());
        assertEquals(1L, result.getOutcomeVersion());
        InOrder locks = inOrder(artifactDao);
        locks.verify(artifactDao).findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-a");
        locks.verify(artifactDao).findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-b");
        locks.verify(artifactDao).findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-z");

        ArgumentCaptor<AgentTaskEventWriteCommand> events =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter, times(3)).append(events.capture());
        assertEquals(List.of(TaskEventType.ARTIFACT_ACCEPTED,
                        TaskEventType.ARTIFACT_SUPERSEDED,
                        TaskEventType.ARTIFACT_SUPERSEDED),
                events.getAllValues().stream().map(AgentTaskEventWriteCommand::getEventType).toList());
        assertEquals(List.of("artifact-z", "artifact-a", "artifact-b"),
                events.getAllValues().stream().map(event ->
                        (String) JsonUtil.jsonToMap(event.getEventJson())
                                .get(TaskEventPayload.Key.ARTIFACT_ID)).toList());
        Map<String, Object> superseded = JsonUtil.jsonToMap(
                events.getAllValues().get(1).getEventJson());
        assertEquals("artifact-z", superseded.get(
                TaskEventPayload.Key.SUPERSEDED_BY_ARTIFACT_ID));
        assertFalse(events.getAllValues().get(0).getEventJson().contains("content"));
    }

    @Test
    void exactReplayReturnsOriginalAcceptedOutcomeWithoutWritesEvenAfterNewVersionPublished() {
        AgentTaskArtifactEntity version1 = artifact(
                "artifact-replay", 1, "work-1", "analysis", OTHER);
        AgentTaskArtifactEntity version2 = artifact(
                "artifact-replay", 2, "work-1", "analysis", OTHER);
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-replay"))
                .thenReturn(version1, version2);
        when(artifactDao.findVersion(TENANT, CLIENT, TASK, "artifact-replay", 1))
                .thenReturn(version1);
        List<AgentTaskArtifactOutcomeDecisionEntity> persisted = new ArrayList<>();
        when(outcomeDao.findDecisionForUpdate(TENANT, CLIENT, TASK, "decision-replay"))
                .thenAnswer(invocation -> persisted.isEmpty() ? null : persisted.get(0));
        when(outcomeDao.findForUpdate(TENANT, CLIENT, TASK, "artifact-replay", 1))
                .thenReturn(null);
        when(outcomeDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(outcomeDao.insertDecision(eq(TENANT), eq(CLIENT), any())).thenAnswer(invocation -> {
            AgentTaskArtifactOutcomeDecisionEntity row = invocation.getArgument(2);
            row.setTenantId(TENANT);
            row.setClientId(CLIENT);
            persisted.add(row);
            return 1;
        });
        AgentTaskArtifactAcceptDTO command = command(
                "decision-replay", ref("artifact-replay", 1, 0));

        var first = service.accept(TENANT, CLIENT, TASK, ACTOR, command);
        var replay = service.accept(TENANT, CLIENT, TASK, ACTOR, command);

        assertEquals(first.getDecisionId(), replay.getDecisionId());
        assertEquals(first.getOutcomeVersion(), replay.getOutcomeVersion());
        verify(outcomeDao, times(1)).insert(eq(TENANT), eq(CLIENT), any());
        verify(outcomeDao, times(1)).insertDecision(eq(TENANT), eq(CLIENT), any());
        verify(eventWriter, times(1)).append(any());
    }

    @Test
    void reusedDecisionIdWithChangedExpectedVersionFailsClosed() {
        AgentTaskArtifactEntity artifact = artifact(
                "artifact-replay", 1, "work-1", "analysis", OTHER);
        stubArtifacts(artifact);
        AgentTaskArtifactOutcomeDecisionEntity stored = decision(
                artifact, 1, "decision-replay", "b".repeat(64));
        when(outcomeDao.findDecisionForUpdate(TENANT, CLIENT, TASK, "decision-replay"))
                .thenReturn(stored);

        AgentTaskCollaborationException failure = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.accept(TENANT, CLIENT, TASK, ACTOR,
                        command("decision-replay", ref("artifact-replay", 1, 1))));

        assertEquals(Reason.VERSION_CONFLICT, failure.getReason());
        verify(outcomeDao, never()).insert(any(), any(), any());
        verify(eventWriter, never()).append(any());
    }

    @Test
    void onlyLatestArtifactVersionCanBecomeAccepted() {
        AgentTaskArtifactEntity requested = artifact(
                "artifact-versioned", 1, "work-1", "analysis", OTHER);
        AgentTaskArtifactEntity latest = artifact(
                "artifact-versioned", 2, "work-1", "analysis", OTHER);
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-versioned"))
                .thenReturn(latest);
        when(artifactDao.findVersion(TENANT, CLIENT, TASK, "artifact-versioned", 1))
                .thenReturn(requested);

        AgentTaskCollaborationException failure = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.accept(TENANT, CLIENT, TASK, ACTOR,
                        command("decision-latest", ref("artifact-versioned", 1, 0))));

        assertEquals(Reason.VERSION_CONFLICT, failure.getReason());
        verify(outcomeDao, never()).insert(any(), any(), any());
    }

    @Test
    void workerCannotDecideAndContaminatedReviewerIdentityFailsClosed() {
        root = task(OTHER);
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, ACTOR))
                .thenReturn(member(ACTOR, "worker", "working", TENANT));
        AgentTaskCollaborationException worker = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.accept(TENANT, CLIENT, TASK, ACTOR,
                        command("decision-acl", ref("artifact-a", 1, 0))));
        assertEquals(Reason.FORBIDDEN, worker.getReason());
        verify(artifactDao, never()).findLatestVersionForUpdate(any(), any(), any(), any());

        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, ACTOR))
                .thenReturn(member(ACTOR, "reviewer", "working", "tenant-b"));
        AgentTaskCollaborationException contaminated = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.accept(TENANT, CLIENT, TASK, ACTOR,
                        command("decision-acl-2", ref("artifact-a", 1, 0))));
        assertEquals(Reason.INVALID_PERSISTED_STATE, contaminated.getReason());
    }

    @Test
    void supersededArtifactsMustShareConflictScopeAndCasZeroWritesNoEvent() {
        AgentTaskArtifactEntity accepted = artifact(
                "artifact-new", 1, "work-1", "analysis", OTHER);
        AgentTaskArtifactEntity different = artifact(
                "artifact-old", 1, "work-2", "analysis", OTHER);
        stubArtifacts(accepted, different);
        AgentTaskCollaborationException scope = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.accept(TENANT, CLIENT, TASK, ACTOR,
                        command("decision-scope", ref("artifact-new", 1, 0),
                                ref("artifact-old", 1, 0))));
        assertEquals(Reason.INVALID_REQUEST, scope.getReason());

        AgentTaskArtifactEntity lone = artifact(
                "artifact-cas", 1, "work-1", "analysis", OTHER);
        stubArtifacts(lone);
        when(outcomeDao.findForUpdate(TENANT, CLIENT, TASK, "artifact-cas", 1))
                .thenReturn(null);
        when(outcomeDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(0);
        AgentTaskCollaborationException cas = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.accept(TENANT, CLIENT, TASK, ACTOR,
                        command("decision-cas", ref("artifact-cas", 1, 0))));
        assertEquals(Reason.VERSION_CONFLICT, cas.getReason());
        verify(eventWriter, never()).append(any());
    }

    @Test
    void authoritativeSurfacePassesAclFlagsAndRejectsSupersededOrContaminatedRows() {
        root = task(OTHER);
        when(taskDao.findByTaskId(TENANT, CLIENT, TASK)).thenReturn(root);
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, ACTOR))
                .thenReturn(member(ACTOR, "reviewer", "working", TENANT));
        AgentTaskAcceptedArtifactRow accepted = acceptedRow("artifact-a", "accepted");
        when(outcomeDao.listAuthoritativeAccepted(
                TENANT, CLIENT, TASK, "work-1", ACTOR, true, false, 25))
                .thenReturn(List.of(accepted));

        var rows = service.listAuthoritativeAccepted(
                TENANT, CLIENT, TASK, ACTOR, "work-1", 25);

        assertEquals(List.of("artifact-a"),
                rows.stream().map(value -> value.getArtifactId()).toList());
        assertEquals("accepted", rows.get(0).getOutcomeState());

        accepted.setOutcomeState("superseded");
        AgentTaskCollaborationException invalid = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.listAuthoritativeAccepted(
                        TENANT, CLIENT, TASK, ACTOR, "work-1", 25));
        assertEquals(Reason.INVALID_PERSISTED_STATE, invalid.getReason());
    }

    @Test
    void missingOutcomeSchemaFailsClosedWithoutWritesOrFakeAcceptance() {
        AgentTaskArtifactEntity artifact = artifact(
                "artifact-schema", 1, "work-1", "analysis", OTHER);
        stubArtifacts(artifact);
        when(outcomeDao.findDecisionForUpdate(
                TENANT, CLIENT, TASK, "decision-schema"))
                .thenThrow(new DataAccessResourceFailureException("missing outcome table"));

        AgentTaskCollaborationException failure = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.accept(TENANT, CLIENT, TASK, ACTOR,
                        command("decision-schema", ref("artifact-schema", 1, 0))));

        assertEquals(Reason.INVALID_PERSISTED_STATE, failure.getReason());
        assertEquals("Artifact outcome storage is unavailable", failure.getMessage());
        verify(outcomeDao, never()).insert(any(), any(), any());
        verify(outcomeDao, never()).insertDecision(any(), any(), any());
        verify(eventWriter, never()).append(any());
    }

    @Test
    void storageFailureIsMappedToNonLeakingPersistedStateFailure() {
        AgentTaskArtifactEntity artifact = artifact(
                "artifact-a", 1, "work-1", "analysis", OTHER);
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-a"))
                .thenThrow(new DataAccessResourceFailureException("jdbc secret details"));

        AgentTaskCollaborationException failure = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.accept(TENANT, CLIENT, TASK, ACTOR,
                        command("decision-storage", ref("artifact-a", 1, 0))));

        assertEquals(Reason.INVALID_PERSISTED_STATE, failure.getReason());
        assertEquals("Artifact outcome storage is unavailable", failure.getMessage());
        assertFalse(failure.getMessage().contains("jdbc secret"));
    }

    private void stubArtifacts(AgentTaskArtifactEntity... artifacts) {
        for (AgentTaskArtifactEntity artifact : artifacts) {
            when(artifactDao.findLatestVersionForUpdate(
                    TENANT, CLIENT, TASK, artifact.getArtifactId())).thenReturn(artifact);
            when(artifactDao.findVersion(TENANT, CLIENT, TASK,
                    artifact.getArtifactId(), artifact.getArtifactVersion())).thenReturn(artifact);
        }
    }

    private AgentTaskArtifactAcceptDTO command(
            String decisionId, AgentTaskArtifactRefDTO accepted,
            AgentTaskArtifactRefDTO... superseded) {
        AgentTaskArtifactAcceptDTO command = new AgentTaskArtifactAcceptDTO();
        command.setDecisionId(decisionId);
        command.setAcceptedArtifact(accepted);
        command.setSupersededArtifacts(List.of(superseded));
        return command;
    }

    private AgentTaskArtifactRefDTO ref(String artifactId, int version, long outcomeVersion) {
        AgentTaskArtifactRefDTO ref = new AgentTaskArtifactRefDTO();
        ref.setArtifactId(artifactId);
        ref.setArtifactVersion(version);
        ref.setExpectedOutcomeVersion(outcomeVersion);
        return ref;
    }

    private AgentTaskArtifactEntity artifact(
            String artifactId, int version, String workItem, String type, String producer) {
        AgentTaskArtifactEntity artifact = new AgentTaskArtifactEntity()
                .setArtifactId(artifactId).setArtifactVersion(version).setTaskId(TASK)
                .setWorkItemId(workItem).setArtifactType(type).setProducerAgentId(producer)
                .setTitle(artifactId).setContentHash("a".repeat(64))
                .setVisibility("task_members").setCreatedAt(NOW - 100);
        artifact.setTenantId(TENANT);
        artifact.setClientId(CLIENT);
        return artifact;
    }

    private AgentTaskArtifactOutcomeEntity outcome(
            AgentTaskArtifactEntity artifact, String state, long version, String decisionId,
            String replacementId, Integer replacementVersion) {
        AgentTaskArtifactOutcomeEntity outcome = new AgentTaskArtifactOutcomeEntity()
                .setTaskId(TASK).setArtifactId(artifact.getArtifactId())
                .setArtifactVersion(artifact.getArtifactVersion()).setOutcomeState(state)
                .setSupersededByArtifactId(replacementId)
                .setSupersededByArtifactVersion(replacementVersion)
                .setDecisionId(decisionId).setDecisionDigest("a".repeat(64))
                .setDecidedByAgentId(ACTOR).setDecidedAt(NOW - 50).setVersion(version);
        outcome.setTenantId(TENANT);
        outcome.setClientId(CLIENT);
        return outcome;
    }


    private AgentTaskArtifactOutcomeDecisionEntity decision(
            AgentTaskArtifactEntity artifact, long outcomeVersion,
            String decisionId, String digest) {
        AgentTaskArtifactOutcomeDecisionEntity decision =
                new AgentTaskArtifactOutcomeDecisionEntity()
                        .setTaskId(TASK)
                        .setDecisionId(decisionId)
                        .setDecisionDigest(digest)
                        .setAcceptedArtifactId(artifact.getArtifactId())
                        .setAcceptedArtifactVersion(artifact.getArtifactVersion())
                        .setAcceptedOutcomeVersion(outcomeVersion)
                        .setDecidedByAgentId(ACTOR)
                        .setDecidedAt(NOW - 50);
        decision.setTenantId(TENANT);
        decision.setClientId(CLIENT);
        return decision;
    }

    private AgentTaskAcceptedArtifactRow acceptedRow(String artifactId, String state) {
        AgentTaskAcceptedArtifactRow row = new AgentTaskAcceptedArtifactRow();
        row.setTenantId(TENANT);
        row.setClientId(CLIENT);
        row.setTaskId(TASK);
        row.setArtifactId(artifactId);
        row.setWorkItemId("work-1");
        row.setProducerAgentId(OTHER);
        row.setArtifactType("analysis");
        row.setTitle(artifactId);
        row.setContentHash("a".repeat(64));
        row.setArtifactVersion(1);
        row.setVisibility("task_members");
        row.setCreatedAt(NOW - 100);
        row.setOutcomeState(state);
        row.setOutcomeVersion(1L);
        row.setDecisionId("decision-list");
        row.setDecidedByAgentId(ACTOR);
        row.setDecidedAt(NOW - 50);
        return row;
    }

    private AgentTaskMetaEntity task(String coordinator) {
        AgentTaskMetaEntity task = new AgentTaskMetaEntity().setTaskId(TASK)
                .setCoordinatorAgentId(coordinator).setTaskVersion(1L)
                .setCurrentEventVersion(0L);
        task.setTenantId(TENANT);
        task.setClientId(CLIENT);
        return task;
    }

    private AgentTaskMemberEntity member(
            String agentId, String role, String status, String tenant) {
        AgentTaskMemberEntity member = new AgentTaskMemberEntity().setTaskId(TASK)
                .setAgentId(agentId).setMemberRole(role).setMemberStatus(status).setVersion(1L);
        member.setTenantId(tenant);
        member.setClientId(CLIENT);
        return member;
    }
}
