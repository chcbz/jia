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
import cn.jia.agent.entity.AgentTaskArtifactOutcomeViewDTO;
import cn.jia.agent.entity.AgentTaskArtifactRefDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskArtifactOutcomeService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.state.AgentTaskArtifactOutcomeState;
import cn.jia.agent.state.AgentTaskMemberStatus;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Persisted F06 accepted/superseded state machine and authoritative accepted projection. */
@Named
@Transactional(rollbackFor = Exception.class)
public class AgentTaskArtifactOutcomeServiceImpl implements AgentTaskArtifactOutcomeService {
    // One decision cannot invalidate more than the existing 100-row workspace artifact window.
    static final int MAX_SUPERSEDED_ARTIFACTS = 100;
    static final int DEFAULT_AUTHORITATIVE_LIMIT = 100;
    static final int MAX_AUTHORITATIVE_LIMIT = 100;

    private static final Set<AgentTaskMemberStatus> ACTIVE_STATUSES = Set.of(
            AgentTaskMemberStatus.ACCEPTED, AgentTaskMemberStatus.WORKING,
            AgentTaskMemberStatus.BLOCKED, AgentTaskMemberStatus.DONE);
    private static final Set<String> VISIBILITIES = Set.of("task_members", "reviewer", "private");
    private static final Comparator<ArtifactRef> REF_ORDER = (left, right) -> {
        int id = compareUtf8(left.artifactId(), right.artifactId());
        return id != 0 ? id : Integer.compare(left.artifactVersion(), right.artifactVersion());
    };

    private final AgentTaskArtifactDao artifactDao;
    private final AgentTaskArtifactOutcomeDao outcomeDao;
    private final AgentTaskMemberDao memberDao;
    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final LongSupplier clock;

    @Inject
    public AgentTaskArtifactOutcomeServiceImpl(
            AgentTaskArtifactDao artifactDao,
            AgentTaskArtifactOutcomeDao outcomeDao,
            AgentTaskMemberDao memberDao,
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter) {
        this(artifactDao, outcomeDao, memberDao, taskMetaDao, mutationTransaction, eventWriter,
                System::currentTimeMillis);
    }

    AgentTaskArtifactOutcomeServiceImpl(
            AgentTaskArtifactDao artifactDao,
            AgentTaskArtifactOutcomeDao outcomeDao,
            AgentTaskMemberDao memberDao,
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter,
            LongSupplier clock) {
        this.artifactDao = Objects.requireNonNull(artifactDao, "artifactDao");
        this.outcomeDao = Objects.requireNonNull(outcomeDao, "outcomeDao");
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao");
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskArtifactOutcomeViewDTO accept(
            String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskArtifactAcceptDTO command) {
        requireScope(tenantId, clientId, taskId, actorAgentId);
        Decision decision = normalizeDecision(
                tenantId, clientId, taskId, actorAgentId, command);
        try {
            return mutationTransaction.executeWithLockedTaskRoot(tenantId, clientId, taskId,
                    root -> acceptLocked(tenantId, clientId, taskId, actorAgentId,
                            decision, requireExactRoot(root, tenantId, clientId, taskId)));
        } catch (AgentTaskCollaborationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable("Artifact outcome storage is unavailable", exception);
        }
    }

    private AgentTaskArtifactOutcomeViewDTO acceptLocked(
            String tenantId, String clientId, String taskId, String actorAgentId,
            Decision decision, AgentTaskMetaEntity root) {
        Access access = requireAccess(
                tenantId, clientId, taskId, actorAgentId, root, true);

        // Frozen lock order: task root -> artifact logical chains (UTF-8 byte order)
        // -> outcome decision/exact rows -> writes -> events.
        LockedArtifacts locked = lockAndLoadArtifacts(
                tenantId, clientId, taskId, access, decision);
        Map<ArtifactRef, AgentTaskArtifactEntity> artifacts = locked.byRef();
        AgentTaskArtifactOutcomeDecisionEntity replay = outcomeDao.findDecisionForUpdate(
                tenantId, clientId, taskId, decision.decisionId());
        if (replay != null) {
            requireExactReplay(tenantId, clientId, taskId, actorAgentId, decision, replay);
            return view(artifacts.get(decision.accepted()), replay);
        }
        requireAcceptedArtifactIsLatest(decision, locked);

        List<ArtifactRef> orderedRefs = decision.orderedRefs();
        Map<ArtifactRef, AgentTaskArtifactOutcomeEntity> current = new LinkedHashMap<>();
        for (ArtifactRef ref : orderedRefs) {
            current.put(ref, outcomeDao.findForUpdate(
                    tenantId, clientId, taskId, ref.artifactId(), ref.artifactVersion()));
        }
        validateTransitions(tenantId, clientId, taskId, decision, current);

        long decidedAt = now();
        AgentTaskArtifactOutcomeEntity accepted = persist(
                tenantId, clientId, taskId, actorAgentId, decision,
                decision.accepted(), current.get(decision.accepted()),
                AgentTaskArtifactOutcomeState.ACCEPTED, decidedAt);
        List<AgentTaskArtifactOutcomeEntity> superseded = new ArrayList<>();
        for (ArtifactRef ref : decision.superseded()) {
            superseded.add(persist(tenantId, clientId, taskId, actorAgentId, decision,
                    ref, current.get(ref), AgentTaskArtifactOutcomeState.SUPERSEDED, decidedAt));
        }
        persistDecision(tenantId, clientId, taskId, actorAgentId,
                decision, accepted, decidedAt);

        appendOutcomeEvent(tenantId, clientId, taskId, actorAgentId,
                artifacts.get(decision.accepted()), accepted, null, decidedAt);
        for (int index = 0; index < decision.superseded().size(); index++) {
            appendOutcomeEvent(tenantId, clientId, taskId, actorAgentId,
                    artifacts.get(decision.superseded().get(index)), superseded.get(index),
                    decision.accepted(), decidedAt);
        }
        return view(artifacts.get(decision.accepted()), accepted);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<AgentTaskArtifactOutcomeViewDTO> listAuthoritativeAccepted(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String workItemId, Integer limit) {
        requireScope(tenantId, clientId, taskId, actorAgentId);
        if (workItemId != null) {
            requireId(workItemId, "workItemId", 100);
        }
        final Access access;
        try {
            access = requireReadAccess(tenantId, clientId, taskId, actorAgentId);
        } catch (AgentTaskCollaborationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw unavailable("Task access state is unavailable", exception);
        }
        int bounded = limit == null ? DEFAULT_AUTHORITATIVE_LIMIT : limit;
        if (bounded < 1 || bounded > MAX_AUTHORITATIVE_LIMIT) {
            throw invalid("limit must be between 1 and " + MAX_AUTHORITATIVE_LIMIT);
        }
        final List<AgentTaskAcceptedArtifactRow> rows;
        try {
            rows = requiredList(outcomeDao.listAuthoritativeAccepted(
                    tenantId, clientId, taskId, workItemId, actorAgentId,
                    access.reviewer(), access.coordinator(), bounded));
        } catch (DataAccessException exception) {
            throw unavailable("Artifact outcome storage is unavailable", exception);
        }
        List<AgentTaskArtifactOutcomeViewDTO> result = new ArrayList<>(rows.size());
        Set<ArtifactKey> unique = new HashSet<>();
        for (AgentTaskAcceptedArtifactRow row : rows) {
            if (!validAcceptedRow(row, tenantId, clientId, taskId, workItemId, access)
                    || !unique.add(new ArtifactKey(
                    row.getArtifactId(), row.getArtifactVersion()))) {
                throw unavailable("Authoritative artifact outcome data is invalid", null);
            }
            result.add(view(row));
        }
        return List.copyOf(result);
    }

    private LockedArtifacts lockAndLoadArtifacts(
            String tenantId, String clientId, String taskId,
            Access access, Decision decision) {
        List<String> artifactIds = decision.orderedRefs().stream()
                .map(ArtifactRef::artifactId).distinct()
                .sorted(AgentTaskArtifactOutcomeServiceImpl::compareUtf8).toList();
        Map<String, AgentTaskArtifactEntity> latestById = new HashMap<>();
        for (String artifactId : artifactIds) {
            AgentTaskArtifactEntity latest = artifactDao.findLatestVersionForUpdate(
                    tenantId, clientId, taskId, artifactId);
            if (!validArtifact(latest, tenantId, clientId, taskId)
                    || !artifactId.equals(latest.getArtifactId())) {
                throw notFound();
            }
            latestById.put(artifactId, latest);
        }

        Map<ArtifactRef, AgentTaskArtifactEntity> artifacts = new LinkedHashMap<>();
        for (ArtifactRef ref : decision.orderedRefs()) {
            AgentTaskArtifactEntity artifact = artifactDao.findVersion(
                    tenantId, clientId, taskId, ref.artifactId(), ref.artifactVersion());
            if (!validArtifact(artifact, tenantId, clientId, taskId, ref)
                    || !canReadArtifact(access, artifact)) {
                throw notFound();
            }
            artifacts.put(ref, artifact);
        }
        AgentTaskArtifactEntity target = artifacts.get(decision.accepted());
        for (ArtifactRef ref : decision.superseded()) {
            AgentTaskArtifactEntity conflict = artifacts.get(ref);
            if (!Objects.equals(target.getWorkItemId(), conflict.getWorkItemId())
                    || !target.getArtifactType().equals(conflict.getArtifactType())) {
                throw invalid("Superseded artifacts must share work item and artifact type");
            }
        }
        return new LockedArtifacts(Map.copyOf(artifacts), Map.copyOf(latestById));
    }

    private void requireAcceptedArtifactIsLatest(
            Decision decision, LockedArtifacts locked) {
        AgentTaskArtifactEntity accepted = locked.byRef().get(decision.accepted());
        if (accepted == null) {
            throw unavailable("Accepted artifact lock set is incomplete", null);
        }
        AgentTaskArtifactEntity latest = locked.latestById().get(
                decision.accepted().artifactId());
        if (!validArtifact(latest, accepted.getTenantId(), accepted.getClientId(),
                accepted.getTaskId())
                || !accepted.getArtifactId().equals(latest.getArtifactId())
                || !Objects.equals(latest.getArtifactVersion(), accepted.getArtifactVersion())) {
            throw conflict("Only the latest artifact version can be accepted");
        }
    }

    private void validateTransitions(
            String tenantId, String clientId, String taskId, Decision decision,
            Map<ArtifactRef, AgentTaskArtifactOutcomeEntity> current) {
        for (ArtifactRef ref : decision.orderedRefs()) {
            AgentTaskArtifactOutcomeEntity row = current.get(ref);
            if (row != null && (!tenantId.equals(row.getTenantId())
                    || !clientId.equals(row.getClientId()) || !taskId.equals(row.getTaskId())
                    || !ref.artifactId().equals(row.getArtifactId())
                    || row.getArtifactVersion() == null
                    || ref.artifactVersion() != row.getArtifactVersion())) {
                throw unavailable("Persisted artifact outcome scope is invalid", null);
            }
            long actualVersion = row == null ? 0 : requireOutcomeVersion(row);
            if (actualVersion != ref.expectedOutcomeVersion()) {
                throw conflict("Artifact outcome changed concurrently");
            }
            if (row == null) {
                continue;
            }
            AgentTaskArtifactOutcomeState state = requireOutcomeState(row);
            requireOutcomeAudit(row);
            requireOutcomeShape(row, state);
            if (ref.equals(decision.accepted()) || state == AgentTaskArtifactOutcomeState.SUPERSEDED) {
                throw new AgentTaskCollaborationException(Reason.INVALID_TRANSITION,
                        "Artifact outcome transition is not allowed");
            }
        }
    }

    private AgentTaskArtifactOutcomeEntity persist(
            String tenantId, String clientId, String taskId, String actorAgentId,
            Decision decision, ArtifactRef ref, AgentTaskArtifactOutcomeEntity current,
            AgentTaskArtifactOutcomeState state, long decidedAt) {
        long resultVersion = ref.expectedOutcomeVersion() + 1;
        AgentTaskArtifactOutcomeEntity next = new AgentTaskArtifactOutcomeEntity()
                .setTaskId(taskId)
                .setArtifactId(ref.artifactId())
                .setArtifactVersion(ref.artifactVersion())
                .setOutcomeState(state.value())
                .setSupersededByArtifactId(state == AgentTaskArtifactOutcomeState.SUPERSEDED
                        ? decision.accepted().artifactId() : null)
                .setSupersededByArtifactVersion(state == AgentTaskArtifactOutcomeState.SUPERSEDED
                        ? decision.accepted().artifactVersion() : null)
                .setDecisionId(decision.decisionId())
                .setDecisionDigest(decision.digest())
                .setDecidedByAgentId(actorAgentId)
                .setDecidedAt(decidedAt)
                .setVersion(resultVersion);
        try {
            int affected = current == null
                    ? outcomeDao.insert(tenantId, clientId, next)
                    : outcomeDao.updateByVersion(tenantId, clientId, taskId,
                    ref.artifactId(), ref.artifactVersion(), current.getOutcomeState(),
                    ref.expectedOutcomeVersion(), next);
            if (affected != 1) {
                throw conflict("Artifact outcome changed concurrently");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("Artifact outcome changed concurrently");
        } catch (DataAccessException exception) {
            throw unavailable("Artifact outcome could not be persisted", exception);
        }
        return next;
    }

    private void persistDecision(
            String tenantId, String clientId, String taskId, String actorAgentId,
            Decision decision, AgentTaskArtifactOutcomeEntity accepted, long decidedAt) {
        AgentTaskArtifactOutcomeDecisionEntity record =
                new AgentTaskArtifactOutcomeDecisionEntity()
                        .setTaskId(taskId)
                        .setDecisionId(decision.decisionId())
                        .setDecisionDigest(decision.digest())
                        .setAcceptedArtifactId(decision.accepted().artifactId())
                        .setAcceptedArtifactVersion(decision.accepted().artifactVersion())
                        .setAcceptedOutcomeVersion(accepted.getVersion())
                        .setDecidedByAgentId(actorAgentId)
                        .setDecidedAt(decidedAt);
        try {
            if (outcomeDao.insertDecision(tenantId, clientId, record) != 1) {
                throw conflict("Artifact decision changed concurrently");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("Decision id is already bound to another decision");
        } catch (DataAccessException exception) {
            throw unavailable("Artifact decision could not be persisted", exception);
        }
    }

    private void requireExactReplay(
            String tenantId, String clientId, String taskId, String actorAgentId,
            Decision decision, AgentTaskArtifactOutcomeDecisionEntity record) {
        long expectedAcceptedOutcomeVersion = decision.accepted().expectedOutcomeVersion() + 1;
        if (!tenantId.equals(record.getTenantId())
                || !clientId.equals(record.getClientId())
                || !taskId.equals(record.getTaskId())
                || !decision.decisionId().equals(record.getDecisionId())
                || !decision.digest().equals(record.getDecisionDigest())
                || !decision.accepted().artifactId().equals(record.getAcceptedArtifactId())
                || !Objects.equals(decision.accepted().artifactVersion(),
                record.getAcceptedArtifactVersion())
                || !Objects.equals(expectedAcceptedOutcomeVersion,
                record.getAcceptedOutcomeVersion())
                || !actorAgentId.equals(record.getDecidedByAgentId())
                || record.getDecidedAt() == null || record.getDecidedAt() <= 0) {
            throw conflict("Decision id is already bound to different input");
        }
    }

    private void appendOutcomeEvent(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskArtifactEntity artifact, AgentTaskArtifactOutcomeEntity outcome,
            ArtifactRef replacement, long occurredAt) {
        AgentTaskArtifactOutcomeState state = requireOutcomeState(outcome);
        String from = outcome.getVersion() == 1 ? "draft" : "accepted";
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.ARTIFACT_ID, artifact.getArtifactId())
                .put(TaskEventPayload.Key.ARTIFACT_TYPE, artifact.getArtifactType())
                .put(TaskEventPayload.Key.ARTIFACT_VERSION,
                        artifact.getArtifactVersion().longValue())
                .put(TaskEventPayload.Key.PRODUCER_AGENT_ID, artifact.getProducerAgentId())
                .put(TaskEventPayload.Key.VISIBILITY, artifact.getVisibility())
                .put(TaskEventPayload.Key.FROM_STATUS, from)
                .put(TaskEventPayload.Key.TO_STATUS, state.value())
                .put(TaskEventPayload.Key.EXPECTED_VERSION, outcome.getVersion() - 1)
                .put(TaskEventPayload.Key.RESULT_VERSION, outcome.getVersion())
                .put(TaskEventPayload.Key.DECISION_ID, outcome.getDecisionId());
        if (artifact.getWorkItemId() != null) {
            payload.put(TaskEventPayload.Key.WORK_ITEM_ID, artifact.getWorkItemId());
        }
        if (replacement != null) {
            payload.put(TaskEventPayload.Key.SUPERSEDED_BY_ARTIFACT_ID,
                    replacement.artifactId());
            payload.put(TaskEventPayload.Key.SUPERSEDED_BY_ARTIFACT_VERSION,
                    replacement.artifactVersion());
        }
        String eventType = state == AgentTaskArtifactOutcomeState.ACCEPTED
                ? TaskEventType.ARTIFACT_ACCEPTED : TaskEventType.ARTIFACT_SUPERSEDED;
        eventWriter.append(AgentTaskMutationEventSupport.command(
                tenantId, clientId, taskId, eventType, TaskEventType.ActorType.AGENT,
                actorAgentId, TaskEventType.Aggregate.ARTIFACT,
                AgentTaskWorkspaceEventValidator.artifactOutcomeAggregateId(
                        taskId, artifact.getArtifactId(), artifact.getArtifactVersion()),
                payload, occurredAt, outcome.getVersion()));
    }

    private Access requireReadAccess(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        AgentTaskMetaEntity root;
        try {
            root = taskMetaDao.findByTaskId(tenantId, clientId, taskId);
        } catch (DataAccessException exception) {
            throw unavailable("Task access state is unavailable", exception);
        }
        return requireAccess(tenantId, clientId, taskId, actorAgentId,
                requireExactRoot(root, tenantId, clientId, taskId), false);
    }

    private Access requireAccess(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskMetaEntity root, boolean decide) {
        boolean coordinator = actorAgentId.equals(root.getCoordinatorAgentId());
        final AgentTaskMemberEntity member;
        try {
            member = memberDao.findByTaskAndAgent(
                    tenantId, clientId, taskId, actorAgentId);
        } catch (DataAccessException exception) {
            throw unavailable("Task member access state is unavailable", exception);
        }
        if (coordinator) {
            return new Access(actorAgentId, "coordinator", true, true);
        }
        if (member == null) {
            throw forbidden();
        }
        if (!tenantId.equals(member.getTenantId()) || !clientId.equals(member.getClientId())
                || !taskId.equals(member.getTaskId())
                || !actorAgentId.equals(member.getAgentId())) {
            throw unavailable("Persisted task member scope is invalid", null);
        }
        AgentTaskMemberStatus status;
        try {
            status = AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
        } catch (IllegalArgumentException exception) {
            throw unavailable("Persisted member status is invalid", exception);
        }
        if (!ACTIVE_STATUSES.contains(status)) {
            throw forbidden();
        }
        String role = member.getMemberRole();
        if (!Set.of("coordinator", "worker", "reviewer", "observer").contains(role)) {
            throw unavailable("Persisted member role is invalid", null);
        }
        boolean reviewer = "reviewer".equals(role);
        boolean delegatedCoordinator = "coordinator".equals(role);
        if (decide && !reviewer && !delegatedCoordinator) {
            throw forbidden();
        }
        return new Access(actorAgentId, role, reviewer, delegatedCoordinator);
    }

    private Decision normalizeDecision(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskArtifactAcceptDTO command) {
        if (command == null) {
            throw invalid("artifact outcome command is required");
        }
        requireId(command.getDecisionId(), "decisionId", 100);
        ArtifactRef accepted = requireRef(command.getAcceptedArtifact(), "acceptedArtifact");
        List<AgentTaskArtifactRefDTO> supplied = command.getSupersededArtifacts() == null
                ? List.of() : command.getSupersededArtifacts();
        if (supplied.size() > MAX_SUPERSEDED_ARTIFACTS) {
            throw invalid("supersededArtifacts exceeds authoritative artifact window");
        }
        List<ArtifactRef> superseded = new ArrayList<>(supplied.size());
        Set<ArtifactKey> unique = new HashSet<>();
        unique.add(accepted.key());
        for (AgentTaskArtifactRefDTO value : supplied) {
            ArtifactRef ref = requireRef(value, "supersededArtifact");
            if (!unique.add(ref.key())) {
                throw invalid("artifact outcome references must be unique");
            }
            superseded.add(ref);
        }
        superseded.sort(REF_ORDER);
        List<ArtifactRef> ordered = new ArrayList<>(superseded.size() + 1);
        ordered.add(accepted);
        ordered.addAll(superseded);
        ordered.sort(REF_ORDER);
        String digest = decisionDigest(tenantId, clientId, taskId, actorAgentId,
                command.getDecisionId(), accepted, superseded);
        return new Decision(command.getDecisionId(), accepted,
                List.copyOf(superseded), List.copyOf(ordered), digest);
    }

    private ArtifactRef requireRef(AgentTaskArtifactRefDTO value, String name) {
        if (value == null) {
            throw invalid(name + " is required");
        }
        requireId(value.getArtifactId(), name + ".artifactId", 100);
        if (value.getArtifactVersion() == null || value.getArtifactVersion() < 1) {
            throw invalid(name + ".artifactVersion must be positive");
        }
        if (value.getExpectedOutcomeVersion() == null
                || value.getExpectedOutcomeVersion() < 0
                || value.getExpectedOutcomeVersion() == Long.MAX_VALUE) {
            throw invalid(name + ".expectedOutcomeVersion is out of range");
        }
        return new ArtifactRef(value.getArtifactId(), value.getArtifactVersion(),
                value.getExpectedOutcomeVersion());
    }

    private boolean validArtifact(AgentTaskArtifactEntity artifact,
            String tenantId, String clientId, String taskId) {
        return artifact != null
                && tenantId.equals(artifact.getTenantId())
                && clientId.equals(artifact.getClientId())
                && taskId.equals(artifact.getTaskId())
                && artifact.getArtifactVersion() != null
                && artifact.getArtifactVersion() > 0
                && validPersistedId(artifact.getArtifactId(), 100)
                && validPersistedId(artifact.getProducerAgentId(), 100)
                && validPersistedId(artifact.getArtifactType(), 30)
                && artifact.getTitle() != null
                && artifact.getContentHash() != null
                && artifact.getContentHash().matches("[0-9a-f]{64}")
                && artifact.getCreatedAt() != null && artifact.getCreatedAt() > 0
                && VISIBILITIES.contains(artifact.getVisibility());
    }

    private boolean validArtifact(AgentTaskArtifactEntity artifact,
            String tenantId, String clientId, String taskId, ArtifactRef ref) {
        return validArtifact(artifact, tenantId, clientId, taskId)
                && ref.artifactId().equals(artifact.getArtifactId())
                && ref.artifactVersion() == artifact.getArtifactVersion();
    }

    private boolean canReadArtifact(Access access, AgentTaskArtifactEntity artifact) {
        if (access.coordinator()
                || access.actorAgentId().equals(artifact.getProducerAgentId())) {
            return true;
        }
        return switch (artifact.getVisibility()) {
            case "task_members" -> true;
            case "reviewer" -> access.reviewer();
            case "private" -> false;
            default -> false;
        };
    }

    private boolean validAcceptedRow(AgentTaskAcceptedArtifactRow row,
            String tenantId, String clientId, String taskId,
            String requestedWorkItemId, Access access) {
        if (row == null || !tenantId.equals(row.getTenantId())
                || !clientId.equals(row.getClientId()) || !taskId.equals(row.getTaskId())
                || row.getArtifactVersion() == null || row.getArtifactVersion() < 1
                || row.getOutcomeVersion() == null || row.getOutcomeVersion() < 1
                || !AgentTaskArtifactOutcomeState.ACCEPTED.value().equals(row.getOutcomeState())
                || !validPersistedId(row.getArtifactId(), 100)
                || row.getWorkItemId() != null
                && !validPersistedId(row.getWorkItemId(), 100)
                || requestedWorkItemId != null
                && !requestedWorkItemId.equals(row.getWorkItemId())
                || !validPersistedId(row.getProducerAgentId(), 100)
                || !validPersistedId(row.getArtifactType(), 30) || row.getTitle() == null
                || row.getContentHash() == null || !row.getContentHash().matches("[0-9a-f]{64}")
                || !validPersistedId(row.getDecisionId(), 100)
                || !validPersistedId(row.getDecidedByAgentId(), 100)
                || row.getCreatedAt() == null || row.getCreatedAt() <= 0
                || row.getDecidedAt() == null || row.getDecidedAt() <= 0
                || !VISIBILITIES.contains(row.getVisibility())) {
            return false;
        }
        if (access.coordinator()
                || access.actorAgentId().equals(row.getProducerAgentId())) {
            return true;
        }
        return "task_members".equals(row.getVisibility())
                || access.reviewer() && "reviewer".equals(row.getVisibility());
    }

    private AgentTaskArtifactOutcomeViewDTO view(
            AgentTaskArtifactEntity artifact, AgentTaskArtifactOutcomeEntity outcome) {
        if (artifact == null || outcome == null
                || requireOutcomeState(outcome) != AgentTaskArtifactOutcomeState.ACCEPTED) {
            throw unavailable("Accepted artifact outcome is invalid", null);
        }
        AgentTaskArtifactOutcomeViewDTO view = new AgentTaskArtifactOutcomeViewDTO();
        view.setArtifactId(artifact.getArtifactId());
        view.setTaskId(artifact.getTaskId());
        view.setWorkItemId(artifact.getWorkItemId());
        view.setProducerAgentId(artifact.getProducerAgentId());
        view.setArtifactType(artifact.getArtifactType());
        view.setTitle(artifact.getTitle());
        view.setContentHash(artifact.getContentHash());
        view.setArtifactVersion(artifact.getArtifactVersion());
        view.setVisibility(artifact.getVisibility());
        view.setCreatedAt(artifact.getCreatedAt());
        view.setOutcomeState(outcome.getOutcomeState());
        view.setOutcomeVersion(outcome.getVersion());
        view.setDecisionId(outcome.getDecisionId());
        view.setDecidedByAgentId(outcome.getDecidedByAgentId());
        view.setDecidedAt(outcome.getDecidedAt());
        return view;
    }

    private AgentTaskArtifactOutcomeViewDTO view(
            AgentTaskArtifactEntity artifact,
            AgentTaskArtifactOutcomeDecisionEntity decision) {
        if (artifact == null || decision == null) {
            throw unavailable("Accepted artifact decision is invalid", null);
        }
        AgentTaskArtifactOutcomeViewDTO view = new AgentTaskArtifactOutcomeViewDTO();
        view.setArtifactId(artifact.getArtifactId());
        view.setTaskId(artifact.getTaskId());
        view.setWorkItemId(artifact.getWorkItemId());
        view.setProducerAgentId(artifact.getProducerAgentId());
        view.setArtifactType(artifact.getArtifactType());
        view.setTitle(artifact.getTitle());
        view.setContentHash(artifact.getContentHash());
        view.setArtifactVersion(artifact.getArtifactVersion());
        view.setVisibility(artifact.getVisibility());
        view.setCreatedAt(artifact.getCreatedAt());
        view.setOutcomeState(AgentTaskArtifactOutcomeState.ACCEPTED.value());
        view.setOutcomeVersion(decision.getAcceptedOutcomeVersion());
        view.setDecisionId(decision.getDecisionId());
        view.setDecidedByAgentId(decision.getDecidedByAgentId());
        view.setDecidedAt(decision.getDecidedAt());
        return view;
    }

    private AgentTaskArtifactOutcomeViewDTO view(AgentTaskAcceptedArtifactRow row) {
        AgentTaskArtifactOutcomeViewDTO view = new AgentTaskArtifactOutcomeViewDTO();
        view.setArtifactId(row.getArtifactId());
        view.setTaskId(row.getTaskId());
        view.setWorkItemId(row.getWorkItemId());
        view.setProducerAgentId(row.getProducerAgentId());
        view.setArtifactType(row.getArtifactType());
        view.setTitle(row.getTitle());
        view.setContentHash(row.getContentHash());
        view.setArtifactVersion(row.getArtifactVersion());
        view.setVisibility(row.getVisibility());
        view.setCreatedAt(row.getCreatedAt());
        view.setOutcomeState(row.getOutcomeState());
        view.setOutcomeVersion(row.getOutcomeVersion());
        view.setDecisionId(row.getDecisionId());
        view.setDecidedByAgentId(row.getDecidedByAgentId());
        view.setDecidedAt(row.getDecidedAt());
        return view;
    }

    private AgentTaskMetaEntity requireExactRoot(AgentTaskMetaEntity root,
            String tenantId, String clientId, String taskId) {
        if (root == null || !tenantId.equals(root.getTenantId())
                || !clientId.equals(root.getClientId()) || !taskId.equals(root.getTaskId())) {
            throw notFound();
        }
        return root;
    }

    private void requireOutcomeAudit(AgentTaskArtifactOutcomeEntity row) {
        if (!validPersistedId(row.getDecisionId(), 100)
                || row.getDecisionDigest() == null
                || !row.getDecisionDigest().matches("[0-9a-f]{64}")
                || !validPersistedId(row.getDecidedByAgentId(), 100)
                || row.getDecidedAt() == null || row.getDecidedAt() <= 0) {
            throw unavailable("Persisted artifact outcome audit data is invalid", null);
        }
    }

    private void requireOutcomeShape(AgentTaskArtifactOutcomeEntity row,
            AgentTaskArtifactOutcomeState state) {
        boolean acceptedShape = row.getSupersededByArtifactId() == null
                && row.getSupersededByArtifactVersion() == null;
        boolean supersededShape = validPersistedId(row.getSupersededByArtifactId(), 100)
                && row.getSupersededByArtifactVersion() != null
                && row.getSupersededByArtifactVersion() > 0
                && (!row.getArtifactId().equals(row.getSupersededByArtifactId())
                || !row.getArtifactVersion().equals(row.getSupersededByArtifactVersion()));
        if (state == AgentTaskArtifactOutcomeState.ACCEPTED && !acceptedShape
                || state == AgentTaskArtifactOutcomeState.SUPERSEDED && !supersededShape) {
            throw unavailable("Persisted artifact outcome shape is invalid", null);
        }
    }

    private AgentTaskArtifactOutcomeState requireOutcomeState(
            AgentTaskArtifactOutcomeEntity row) {
        try {
            return AgentTaskArtifactOutcomeState.fromPersistedValue(row.getOutcomeState());
        } catch (IllegalArgumentException exception) {
            throw unavailable("Persisted artifact outcome state is invalid", exception);
        }
    }

    private long requireOutcomeVersion(AgentTaskArtifactOutcomeEntity row) {
        if (row.getVersion() == null || row.getVersion() < 1) {
            throw unavailable("Persisted artifact outcome version is invalid", null);
        }
        return row.getVersion();
    }

    private long now() {
        long value = clock.getAsLong();
        if (value <= 0) {
            throw unavailable("Artifact outcome clock is invalid", null);
        }
        return value;
    }

    private static String decisionDigest(String tenantId, String clientId, String taskId,
            String actorAgentId, String decisionId, ArtifactRef accepted,
            List<ArtifactRef> superseded) {
        StringBuilder canonical = new StringBuilder("f06-v1");
        for (String value : List.of(tenantId, clientId, taskId, actorAgentId, decisionId)) {
            appendField(canonical, value);
        }
        appendRef(canonical, accepted);
        canonical.append('|').append(superseded.size());
        superseded.forEach(ref -> appendRef(canonical, ref));
        return sha256(canonical.toString());
    }

    private static void appendRef(StringBuilder target, ArtifactRef ref) {
        appendField(target, ref.artifactId());
        target.append('|').append(ref.artifactVersion())
                .append('|').append(ref.expectedOutcomeVersion());
    }

    private static void appendField(StringBuilder target, String value) {
        target.append('|').append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int compareUtf8(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(a.length, b.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(a[index]),
                    Byte.toUnsignedInt(b[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static <T> List<T> requiredList(List<T> values) {
        if (values == null) {
            throw unavailable("Artifact outcome query returned no result container", null);
        }
        return values;
    }

    private static void requireScope(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        requireId(tenantId, "tenantId", 50);
        requireId(clientId, "clientId", 50);
        requireId(taskId, "taskId", 100);
        requireId(actorAgentId, "actorAgentId", 100);
    }

    private static void requireId(String value, String name, int maxCodePoints) {
        if (!validPersistedId(value, maxCodePoints)) {
            throw invalid(name + " is invalid");
        }
    }

    private static boolean validPersistedId(String value, int maxCodePoints) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.codePointCount(0, value.length()) > maxCodePoints
                || value.codePoints().allMatch(AgentTaskArtifactOutcomeServiceImpl::isPadding)
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        return true;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static AgentTaskCollaborationException invalid(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_REQUEST, message);
    }

    private static AgentTaskCollaborationException notFound() {
        return new AgentTaskCollaborationException(
                Reason.NOT_FOUND, "Resource was not found in the requested scope");
    }

    private static AgentTaskCollaborationException forbidden() {
        return new AgentTaskCollaborationException(
                Reason.FORBIDDEN, "Operation is not permitted");
    }

    private static AgentTaskCollaborationException conflict(String message) {
        return new AgentTaskCollaborationException(Reason.VERSION_CONFLICT, message);
    }

    private static AgentTaskCollaborationException unavailable(
            String message, Throwable cause) {
        return cause == null
                ? new AgentTaskCollaborationException(Reason.INVALID_PERSISTED_STATE, message)
                : new AgentTaskCollaborationException(Reason.INVALID_PERSISTED_STATE, message, cause);
    }

    private record ArtifactKey(String artifactId, int artifactVersion) {
    }

    private record ArtifactRef(
            String artifactId, int artifactVersion, long expectedOutcomeVersion) {
        ArtifactKey key() {
            return new ArtifactKey(artifactId, artifactVersion);
        }
    }

    private record LockedArtifacts(
            Map<ArtifactRef, AgentTaskArtifactEntity> byRef,
            Map<String, AgentTaskArtifactEntity> latestById) {
    }

    private record Decision(
            String decisionId,
            ArtifactRef accepted,
            List<ArtifactRef> superseded,
            List<ArtifactRef> orderedRefs,
            String digest) {
    }

    private record Access(
            String actorAgentId, String role, boolean reviewer, boolean coordinator) {
    }
}
