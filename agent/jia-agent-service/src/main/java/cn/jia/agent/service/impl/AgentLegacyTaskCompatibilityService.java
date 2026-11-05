package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.funding.FundedBountyLegacyGuard;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Assignment adapter used by the authoritative grant transaction.
 * Old task-level reporting is retired; completion belongs to formal delivery and user acceptance.
 */
@Named
public class AgentLegacyTaskCompatibilityService {
    static final String WORK_TYPE = "legacy_default";
    private static final String MEMBER_ROLE_COORDINATOR = "coordinator";
    private static final String MEMBER_ROLE_WORKER = "worker";
    private static final String ASSIGNMENT_MANUAL = "manual";
    private static final String ASSIGNMENT_AUTO = "auto";
    private static final Set<String> MEMBER_ROLES = Set.of("coordinator", "worker", "reviewer", "observer");
    private static final Set<String> ASSIGNMENT_SOURCES = Set.of(ASSIGNMENT_MANUAL, ASSIGNMENT_AUTO, "migration");
    private static final int MAX_DEFAULT_ITEMS = 2;
    private static final int MAX_COLLABORATION_ROWS = 500;

    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskMemberDao memberDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentIdentityService identityService;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final LongSupplier clock;
    private volatile FundedBountyLegacyGuard fundedBountyLegacyGuard =
            FundedBountyLegacyGuard.unconfigured();

    @Inject
    public AgentLegacyTaskCompatibilityService(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentIdentityService identityService,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter) {
        this(taskMetaDao, memberDao, workItemDao, identityService,
                mutationTransaction, eventWriter, System::currentTimeMillis);
    }

    AgentLegacyTaskCompatibilityService(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentIdentityService identityService,
            LongSupplier clock) {
        this(taskMetaDao, memberDao, workItemDao, identityService,
                directTransaction(taskMetaDao), command -> null, clock);
    }

    AgentLegacyTaskCompatibilityService(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentIdentityService identityService,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter,
            LongSupplier clock) {
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Autowired
    void configureFundedBountyLegacyGuard(ObjectProvider<FundedBountyLegacyGuard> provider) {
        this.fundedBountyLegacyGuard = Objects.requireNonNull(provider, "provider")
                .getIfAvailable(FundedBountyLegacyGuard::failClosed);
    }

    public String resolveAgentId(
            String tenantId, String clientId, String ownerJiacn, String requestedAgentId) {
        requireScope(tenantId, clientId, ownerJiacn);
        requireExactText(requestedAgentId, "agentId", 100);
        String canonical = identityService.resolveAgentIdInScope(
                tenantId, clientId, ownerJiacn, requestedAgentId);
        requireExactText(canonical, "resolved agentId", 100);
        return canonical;
    }

    public List<String> resolveAgentIds(
            String tenantId, String clientId, String ownerJiacn, List<String> requestedAgentIds) {
        requireScope(tenantId, clientId, ownerJiacn);
        if (requestedAgentIds == null || requestedAgentIds.isEmpty()) {
            throw invalid("At least one registered agentId is required");
        }
        if (requestedAgentIds.size() >= MAX_COLLABORATION_ROWS) {
            throw invalid("Legacy assignment exceeds the safe member limit");
        }
        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        for (String requestedAgentId : requestedAgentIds) {
            String resolved = resolveAgentId(tenantId, clientId, ownerJiacn, requestedAgentId);
            if (!canonical.add(resolved)) {
                throw invalid("Requested Agent identities resolve to a duplicate canonical member");
            }
        }
        if (canonical.isEmpty()) {
            throw invalid("At least one registered agentId is required");
        }
        return List.copyOf(canonical);
    }

    @Transactional(rollbackFor = Exception.class)
    public AssignOutcome assign(String tenantId, String clientId, String ownerJiacn, String taskId,
            List<String> requestedAgentIds, boolean automatic) {
        return assignResolved(tenantId, clientId, ownerJiacn, taskId,
                resolveAgentIds(tenantId, clientId, ownerJiacn, requestedAgentIds), automatic);
    }

    @Transactional(rollbackFor = Exception.class)
    public AssignOutcome assignResolved(String tenantId, String clientId, String ownerJiacn, String taskId,
            List<String> canonicalAgentIds, boolean automatic) {
        return assignResolvedInternal(tenantId, clientId, ownerJiacn, taskId, canonicalAgentIds, automatic,
                null, (task, agentIds) -> { });
    }

    @Transactional(rollbackFor = Exception.class)
    public AssignOutcome assignResolved(String tenantId, String clientId, String ownerJiacn, String taskId,
            List<String> canonicalAgentIds, boolean automatic,
            AssignmentPrecommitValidator precommitValidator) {
        return assignResolvedInternal(tenantId, clientId, ownerJiacn, taskId, canonicalAgentIds,
                automatic, null, precommitValidator);
    }

    @Transactional(rollbackFor = Exception.class)
    public AssignOutcome assignResolvedVersioned(String tenantId, String clientId, String ownerJiacn, String taskId,
            List<String> canonicalAgentIds, boolean automatic, long expectedTaskVersion,
            AssignmentPrecommitValidator precommitValidator) {
        if (expectedTaskVersion < 0 || expectedTaskVersion == Long.MAX_VALUE) {
            throw invalid("Expected task version is invalid");
        }
        return assignResolvedInternal(tenantId, clientId, ownerJiacn, taskId, canonicalAgentIds,
                automatic, expectedTaskVersion, precommitValidator);
    }

    private AssignOutcome assignResolvedInternal(String tenantId, String clientId, String ownerJiacn, String taskId,
            List<String> canonicalAgentIds, boolean automatic, Long expectedTaskVersion,
            AssignmentPrecommitValidator precommitValidator) {
        requireScope(tenantId, clientId, ownerJiacn);
        requireExactText(taskId, "taskId", 100);
        List<String> agentIds = requireResolvedAgentIds(canonicalAgentIds);
        Objects.requireNonNull(precommitValidator, "precommitValidator");
        long reservedAt = now();
        return mutationTransaction.executeAfterTaskRootReservationInOwnerScope(
                tenantId, clientId, ownerJiacn, taskId,
                () -> taskMetaDao.reserveOpenTaskRootInOwnerScope(tenantId, clientId, ownerJiacn, taskId, reservedAt),
                (task, rootCreated) -> assignResolvedLocked(
                        tenantId, clientId, ownerJiacn, taskId, agentIds, automatic, reservedAt, task,
                        expectedTaskVersion, precommitValidator));
    }

    private AssignOutcome assignResolvedLocked(
            String tenantId, String clientId, String ownerJiacn, String taskId, List<String> agentIds,
            boolean automatic, long changedAt, AgentTaskMetaEntity task,
            Long expectedTaskVersion, AssignmentPrecommitValidator precommitValidator) {
        validateLockedTask(task, tenantId, clientId, taskId);
        precommitValidator.beforeIdentityLock(task, agentIds);
        List<String> lockedAgentIds = identityService.lockActiveCanonicalAgentIdsInScope(
                tenantId, clientId, precommitValidator.identityOwner(ownerJiacn), agentIds);
        if (!agentIds.equals(lockedAgentIds)) {
            throw forbidden();
        }
        AgentTaskStatus taskStatus = persistedTaskStatus(task.getRewardStatus());
        if (taskStatus != AgentTaskStatus.OPEN && taskStatus != AgentTaskStatus.PLANNING
                && taskStatus != AgentTaskStatus.ASSIGNED) {
            throw invalid("Task cannot be assigned in its current status");
        }

        if (expectedTaskVersion != null) {
            if (!expectedTaskVersion.equals(task.getTaskVersion())) {
                throw invalid("Task version changed before assignment");
            }
            // Strict callers validate their grant inputs while the root and canonical identities
            // are locked, including an already-persisted idempotent assignment.
            precommitValidator.validate(task, agentIds);
        }

        List<AgentTaskMemberEntity> members = requireSnapshot(
                memberDao.listByTask(tenantId, clientId, ownerJiacn, taskId), "task member");
        List<AgentTaskWorkItemEntity> defaultItems = requireSnapshot(
                workItemDao.listByTask(
                        tenantId, clientId, ownerJiacn, taskId, null, MAX_COLLABORATION_ROWS),
                "task work item");
        rejectTruncatedSnapshot(members, "task member");
        rejectTruncatedSnapshot(defaultItems, "legacy default work item");

        if (!members.isEmpty() || !defaultItems.isEmpty()) {
            List<String> persistedAgentIds = validateIdempotentAssignment(
                    task, taskId, agentIds, members, defaultItems);
            return new AssignOutcome(persistedAgentIds, false, null, null);
        }

        if (expectedTaskVersion == null) {
            precommitValidator.validate(task, agentIds);
        }
        String fromStatus = taskStatus.value();
        applyAssignmentMeta(task, agentIds, changedAt);
        if (expectedTaskVersion == null) {
            requireSingleMutation(taskMetaDao.updateById(task), "task assignment metadata");
        } else {
            long resultVersion = expectedTaskVersion + 1;
            requireSingleMutation(taskMetaDao.updateAssignmentByVersion(
                    task, expectedTaskVersion, resultVersion, changedAt),
                    "versioned task assignment metadata");
            task.setTaskVersion(resultVersion);
            task.setUpdateTime(changedAt);
        }

        String source = automatic ? ASSIGNMENT_AUTO : ASSIGNMENT_MANUAL;
        for (int index = 0; index < agentIds.size(); index++) {
            String agentId = agentIds.get(index);
            insertMember(tenantId, clientId, ownerJiacn, taskId, agentId, source, changedAt,
                    index == 0 ? MEMBER_ROLE_COORDINATOR : MEMBER_ROLE_WORKER);
            insertDefaultWorkItem(tenantId, clientId, ownerJiacn, taskId, agentId);
        }
        String taskAssignedEventId = appendAssignmentEvents(
                tenantId, clientId, ownerJiacn, taskId, task, agentIds, source, fromStatus, changedAt);
        return new AssignOutcome(agentIds, true, taskAssignedEventId, changedAt);
    }

    /**
     * Strict v2 adapter used only while the caller already holds this exact owner-scoped task root.
     * The caller owns the surrounding REQUIRED transaction and must preserve task -> identity -> input -> grant order.
     */
    public AssignOutcome assignResolvedVersionedWithLockedTask(String tenantId, String clientId,
            String ownerJiacn, String taskId, List<String> canonicalAgentIds, boolean automatic,
            long expectedTaskVersion, AssignmentPrecommitValidator precommitValidator,
            AgentTaskMetaEntity lockedTask) {
        requireScope(tenantId, clientId, ownerJiacn);
        requireExactText(taskId, "taskId", 100);
        if (expectedTaskVersion < 0 || expectedTaskVersion == Long.MAX_VALUE) {
            throw invalid("Expected task version is invalid");
        }
        return assignResolvedLocked(tenantId, clientId, ownerJiacn, taskId,
                requireResolvedAgentIds(canonicalAgentIds), automatic, now(), lockedTask,
                expectedTaskVersion, Objects.requireNonNull(precommitValidator, "precommitValidator"));
    }

    private void validateLockedTask(
            AgentTaskMetaEntity task, String tenantId, String clientId, String taskId) {
        if (task == null) {
            throw notFound();
        }
        if (!tenantId.equals(task.getTenantId()) || !clientId.equals(task.getClientId())
                || !taskId.equals(task.getTaskId())) {
            throw invalidPersisted("Scoped task identity is non-canonical or mismatched");
        }
        requireTaskVersion(task.getTaskVersion());
    }

    private void applyAssignmentMeta(
            AgentTaskMetaEntity task, List<String> agentIds, long changedAt) {
        String primaryAgentId = agentIds.getFirst();
        task.setAssignedAgentId(primaryAgentId);
        task.setRewardStatus(AgentTaskStatus.ASSIGNED.value());
        task.setAssignedAt(firstNonNull(task.getAssignedAt(), changedAt));
        task.setCollaborationMode(agentIds.size() == 1 ? "single" : "team");
        task.setMaxAgents(agentIds.size());
        task.setCoordinatorAgentId(primaryAgentId);
    }

    private List<String> validateIdempotentAssignment(
            AgentTaskMetaEntity task,
            String taskId,
            List<String> requestedAgentIds,
            List<AgentTaskMemberEntity> members,
            List<AgentTaskWorkItemEntity> defaultItems) {
        if (persistedTaskStatus(task.getRewardStatus()) != AgentTaskStatus.ASSIGNED) {
            throw invalid("Existing collaboration rows cannot be reassigned in the current task status");
        }
        Set<String> requested = new LinkedHashSet<>(requestedAgentIds);
        Set<String> persistedMembers = new LinkedHashSet<>();
        String persistedCoordinator = null;
        for (AgentTaskMemberEntity member : members) {
            requireExactText(member.getAgentId(), "persisted member agentId", 100);
            validateMember(member, task.getTenantId(), task.getClientId(),
                    taskId, member.getAgentId());
            AgentTaskMemberStatus memberStatus = persistedMemberStatus(member.getMemberStatus());
            if (memberStatus != AgentTaskMemberStatus.ACCEPTED
                    || member.getStartedAt() != null || member.getCompletedAt() != null
                    || member.getFailureReason() != null) {
                throw invalidPersisted("Assigned task contains a progressed legacy member");
            }
            if (MEMBER_ROLE_COORDINATOR.equals(member.getMemberRole())) {
                if (persistedCoordinator != null) {
                    throw invalidPersisted("Legacy assignment has multiple coordinators");
                }
                persistedCoordinator = member.getAgentId();
            } else if (!MEMBER_ROLE_WORKER.equals(member.getMemberRole())) {
                throw invalidPersisted("Legacy assignment contains a non-adapter member role");
            }
            if (!persistedMembers.add(member.getAgentId())) {
                throw invalidPersisted("Task has duplicate member identities");
            }
        }
        Set<String> persistedItems = new LinkedHashSet<>();
        for (AgentTaskWorkItemEntity item : defaultItems) {
            requireExactText(item.getAssigneeAgentId(), "persisted work item assignee", 100);
            validateDefaultWorkItem(item, task.getTenantId(), task.getClientId(),
                    taskId, item.getAssigneeAgentId());
            if (persistedWorkItemStatus(item.getStatus()) != AgentTaskWorkItemStatus.READY
                    || !Objects.equals(item.getAttemptCount(), 0)
                    || item.getLeaseToken() != null || item.getLeaseUntil() != null
                    || item.getResultArtifactId() != null || item.getSubmittedAt() != null
                    || item.getCompletedAt() != null) {
                throw invalidPersisted("Assigned task contains a progressed legacy work item");
            }
            if (!persistedItems.add(item.getAssigneeAgentId())) {
                throw invalidPersisted("Task member has multiple legacy default work items");
            }
        }
        if (!requested.equals(persistedMembers) || !requested.equals(persistedItems)
                || members.size() != requested.size() || defaultItems.size() != requested.size()) {
            throw invalid("Legacy reassignment must preserve the byte-exact member set");
        }
        String primary = task.getCoordinatorAgentId();
        if (StringUtil.isBlank(primary)) {
            primary = task.getAssignedAgentId();
        }
        if (!requested.contains(primary)
                || !Objects.equals(persistedCoordinator, primary)
                || !Objects.equals(task.getAssignedAgentId(), primary)
                || !Objects.equals(task.getCoordinatorAgentId(), primary)
                || !Objects.equals(task.getMaxAgents(), requested.size())
                || !Objects.equals(task.getCollaborationMode(), requested.size() == 1 ? "single" : "team")
                || task.getAssignedAt() == null || task.getAssignedAt() <= 0
                || task.getStartedAt() != null || task.getCompletedAt() != null
                || task.getFailureReason() != null) {
            throw invalidPersisted("Task collaboration metadata does not match its member set");
        }
        String finalPrimary = primary;
        List<String> ordered = new ArrayList<>();
        ordered.add(finalPrimary);
        members.stream().map(AgentTaskMemberEntity::getAgentId)
                .filter(agentId -> !finalPrimary.equals(agentId))
                .forEachOrdered(ordered::add);
        return List.copyOf(ordered);
    }

    private String appendAssignmentEvents(
            String tenantId, String clientId, String ownerJiacn, String taskId, AgentTaskMetaEntity task,
            List<String> agentIds, String source, String fromStatus, long occurredAt) {
        long taskVersion = task.getTaskVersion();
        TaskEventPayload.Builder taskPayload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.TASK_ID, taskId)
                .put(TaskEventPayload.Key.FROM_STATUS, fromStatus)
                .put(TaskEventPayload.Key.TO_STATUS, AgentTaskStatus.ASSIGNED.value())
                .put(TaskEventPayload.Key.SOURCE, source)
                .put(TaskEventPayload.Key.MEMBER_COUNT, agentIds.size())
                .put(TaskEventPayload.Key.RESULT_VERSION, taskVersion)
                .put(TaskEventPayload.Key.ASSIGNED_AT, occurredAt);
        AgentTaskEventWriteCommand taskAssignedCommand = AgentTaskMutationEventSupport.command(
                tenantId, clientId, ownerJiacn, taskId, TaskEventType.TASK_ASSIGNED,
                TaskEventType.ActorType.SYSTEM, null, TaskEventType.Aggregate.TASK, taskId,
                taskPayload, occurredAt, taskVersion);
        AgentTaskEventWriteResult taskAssignedResult = eventWriter.append(taskAssignedCommand);
        if (taskAssignedResult == null || taskAssignedResult.getEvent() == null
                || !Objects.equals(taskAssignedCommand.getEventId(),
                        taskAssignedResult.getEvent().getEventId())) {
            throw new IllegalStateException("TASK_ASSIGNED append did not return its persisted event identity");
        }
        String taskAssignedEventId = taskAssignedResult.getEvent().getEventId();

        agentIds.stream().sorted(AgentLegacyTaskCompatibilityService::compareUtf8Unsigned)
                .forEach(agentId -> {
                    String role = agentId.equals(agentIds.getFirst())
                            ? MEMBER_ROLE_COORDINATOR : MEMBER_ROLE_WORKER;
                    TaskEventPayload.Builder memberPayload = TaskEventPayload.builder()
                            .put(TaskEventPayload.Key.AGENT_ID, agentId)
                            .put(TaskEventPayload.Key.MEMBER_ID, agentId)
                            .put(TaskEventPayload.Key.ROLE, role)
                            .put(TaskEventPayload.Key.SOURCE, source)
                            .put(TaskEventPayload.Key.TO_STATUS,
                                    AgentTaskMemberStatus.ACCEPTED.value())
                            .put(TaskEventPayload.Key.RESULT_VERSION, 0L)
                            .put(TaskEventPayload.Key.ASSIGNED_AT, occurredAt);
                    eventWriter.append(AgentTaskMutationEventSupport.command(
                            tenantId, clientId, ownerJiacn, taskId, TaskEventType.MEMBER_ACCEPTED,
                            TaskEventType.ActorType.SYSTEM, null,
                            TaskEventType.Aggregate.MEMBER, agentId,
                            memberPayload, occurredAt, 0L));
                });

        agentIds.stream()
                .map(agentId -> new AssignedWorkItem(agentId, defaultWorkItemId(taskId, agentId)))
                .sorted((left, right) -> compareUtf8Unsigned(
                        left.workItemId(), right.workItemId()))
                .forEach(assigned -> {
                    TaskEventPayload.Builder itemPayload = TaskEventPayload.builder()
                            .put(TaskEventPayload.Key.WORK_ITEM_ID, assigned.workItemId())
                            .put(TaskEventPayload.Key.ASSIGNEE_AGENT_ID, assigned.agentId())
                            .put(TaskEventPayload.Key.TO_STATUS,
                                    AgentTaskWorkItemStatus.READY.value())
                            .put(TaskEventPayload.Key.ATTEMPT_COUNT, 0L)
                            .put(TaskEventPayload.Key.MAX_ATTEMPTS, 3L)
                            .put(TaskEventPayload.Key.RESULT_VERSION, 0L);
                    eventWriter.append(AgentTaskMutationEventSupport.command(
                            tenantId, clientId, ownerJiacn, taskId, TaskEventType.WORK_ITEM_READY,
                            TaskEventType.ActorType.SYSTEM, null,
                            TaskEventType.Aggregate.WORK_ITEM, assigned.workItemId(),
                            itemPayload, occurredAt, 0L));
                });
        return taskAssignedEventId;
    }

    private void insertMember(String tenantId, String clientId, String ownerJiacn, String taskId,
            String agentId, String source, long changedAt, String role) {
        AgentTaskMemberDTO member = new AgentTaskMemberDTO();
        member.setTaskId(taskId);
        member.setAgentId(agentId);
        member.setMemberRole(role);
        member.setMemberStatus(AgentTaskMemberStatus.ACCEPTED.value());
        member.setAssignmentSource(source);
        member.setJoinedAt(changedAt);
        member.setAcceptedAt(changedAt);
        requireSingleMutation(memberDao.insert(tenantId, clientId, ownerJiacn, member), "task member");
    }

    private void insertDefaultWorkItem(
            String tenantId, String clientId, String ownerJiacn, String taskId, String agentId) {
        AgentTaskWorkItemDTO item = new AgentTaskWorkItemDTO();
        item.setWorkItemId(defaultWorkItemId(taskId, agentId));
        item.setTaskId(taskId);
        item.setTitle("Legacy task execution: " + taskId);
        item.setDescription("Default required work item created by the legacy assign compatibility adapter.");
        item.setWorkType(WORK_TYPE);
        item.setAssigneeAgentId(agentId);
        item.setStatus(AgentTaskWorkItemStatus.READY.value());
        item.setPriority(0);
        item.setRequiredItem(true);
        item.setDependencyJson("[]");
        item.setAttemptCount(0);
        item.setMaxAttempts(3);
        requireSingleMutation(workItemDao.insert(tenantId, clientId, ownerJiacn, item), "default work item");
    }

    private void validateMember(
            AgentTaskMemberEntity member, String tenantId, String clientId,
            String taskId, String agentId) {
        if (!tenantId.equals(member.getTenantId()) || !clientId.equals(member.getClientId())
                || !taskId.equals(member.getTaskId()) || !agentId.equals(member.getAgentId())
                || !MEMBER_ROLES.contains(member.getMemberRole())
                || !ASSIGNMENT_SOURCES.contains(member.getAssignmentSource())
                || member.getJoinedAt() == null || member.getJoinedAt() <= 0
                || member.getAcceptedAt() == null || member.getAcceptedAt() <= 0
                || member.getVersion() == null || member.getVersion() < 0
                || member.getVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted task member is incomplete or mismatched");
        }
        persistedMemberStatus(member.getMemberStatus());
    }

    private void validateDefaultWorkItem(
            AgentTaskWorkItemEntity item, String tenantId, String clientId,
            String taskId, String agentId) {
        if (!tenantId.equals(item.getTenantId()) || !clientId.equals(item.getClientId())
                || !taskId.equals(item.getTaskId()) || !agentId.equals(item.getAssigneeAgentId())
                || !defaultWorkItemId(taskId, agentId).equals(item.getWorkItemId())
                || !WORK_TYPE.equals(item.getWorkType()) || !Boolean.TRUE.equals(item.getRequiredItem())
                || !"[]".equals(item.getDependencyJson())
                || StringUtil.isBlank(item.getTitle())
                || item.getPriority() == null || item.getAttemptCount() == null
                || item.getMaxAttempts() == null || item.getAttemptCount() < 0
                || item.getMaxAttempts() <= 0 || item.getAttemptCount() > item.getMaxAttempts()
                || item.getVersion() == null || item.getVersion() < 0
                || item.getVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted legacy default work item is incomplete or mismatched");
        }
        persistedWorkItemStatus(item.getStatus());
    }

    private AgentTaskStatus persistedTaskStatus(String status) {
        try {
            return AgentTaskStatus.fromPersistedValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted task status is non-canonical or unknown");
        }
    }

    private AgentTaskMemberStatus persistedMemberStatus(String status) {
        try {
            return AgentTaskMemberStatus.fromPersistedValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted member status is non-canonical or unknown");
        }
    }

    private AgentTaskWorkItemStatus persistedWorkItemStatus(String status) {
        try {
            return AgentTaskWorkItemStatus.fromPersistedValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted work item status is non-canonical or unknown");
        }
    }

    private void requireScope(String tenantId, String clientId, String ownerJiacn) {
        requireExactText(tenantId, "tenantId", 50);
        requireExactText(clientId, "clientId", 50);
        requireExactText(ownerJiacn, "ownerJiacn", 50);
        if ("0".equals(ownerJiacn)) {
            throw forbidden();
        }
    }

    private void requireExactText(String value, String name, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(name + " must be nonblank, unpadded, and free of control characters");
        }
    }

    private List<String> requireResolvedAgentIds(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            throw invalid("At least one resolved agentId is required");
        }
        if (agentIds.size() >= MAX_COLLABORATION_ROWS) {
            throw invalid("Legacy assignment exceeds the safe member limit");
        }
        LinkedHashSet<String> exact = new LinkedHashSet<>();
        for (String agentId : agentIds) {
            requireExactText(agentId, "resolved agentId", 100);
            if (!exact.add(agentId)) {
                throw invalid("Resolved Agent identities must be unique");
            }
        }
        return List.copyOf(exact);
    }

    private void requireTaskVersion(Long version) {
        if (version == null || version < 0 || version == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted task version is invalid");
        }
    }

    private <T> List<T> requireSnapshot(List<T> rows, String aggregate) {
        if (rows == null) {
            throw invalidPersisted("Scoped " + aggregate + " query returned no snapshot");
        }
        return rows;
    }

    private void rejectTruncatedSnapshot(List<?> rows, String aggregate) {
        if (rows.size() >= MAX_COLLABORATION_ROWS) {
            throw invalidPersisted("Scoped " + aggregate + " snapshot exceeds the safe limit");
        }
    }

    private void requireSingleMutation(int affected, String aggregate) {
        if (affected != 1) {
            throw invalidPersisted("Scoped " + aggregate + " mutation affected an unexpected row count");
        }
    }

    private String defaultWorkItemId(String taskId, String agentId) {
        return "wi_legacy_" + digest(taskId + "\u0000" + agentId);
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static int compareUtf8Unsigned(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            int compared = Integer.compare(
                    Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private static AgentTaskMutationTransaction directTransaction(AgentTaskMetaDao taskMetaDao) {
        Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        return new AgentTaskMutationTransaction() {
            @Override
            public <T> T executeWithLockedTaskRoot(
                    String tenantId, String clientId, String taskId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(taskMetaDao.findByTaskIdForUpdate(
                        tenantId, clientId, taskId));
            }

            @Override
            public <T> T executeWithLockedTaskRootInOwnerScope(
                    String tenantId, String clientId, String ownerJiacn, String taskId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(taskMetaDao.findByTaskIdForUpdateInOwnerScope(
                        tenantId, clientId, ownerJiacn, taskId));
            }

            @Override
            public <T> T executeWithLockedTaskRootForWorkItemInOwnerScope(
                    String tenantId, String clientId, String ownerJiacn, String workItemId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(taskMetaDao.findByWorkItemIdForUpdateInOwnerScope(
                        tenantId, clientId, ownerJiacn, workItemId));
            }

            @Override
            public <T> T executeWithLockedTaskRootForWorkItem(
                    String tenantId, String clientId, String workItemId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(taskMetaDao.findByWorkItemIdForUpdate(
                        tenantId, clientId, workItemId));
            }

            @Override
            public <T> T executeAfterTaskRootReservationInOwnerScope(
                    String tenantId, String clientId, String ownerJiacn, String taskId,
                    TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
                int reserved = reservation.reserve();
                if (reserved != 0 && reserved != 1) {
                    throw new IllegalStateException("Task root reservation returned an unexpected row count");
                }
                return mutation.apply(taskMetaDao.findByTaskIdForUpdateInOwnerScope(
                        tenantId, clientId, ownerJiacn, taskId), reserved == 1);
            }

            @Override
            public <T> T executeAfterTaskRootReservation(
                    String tenantId, String clientId, String taskId,
                    TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
                int reserved = reservation.reserve();
                if (reserved != 0 && reserved != 1) {
                    throw new IllegalStateException(
                            "Task root reservation returned an unexpected row count");
                }
                return mutation.apply(taskMetaDao.findByTaskIdForUpdate(
                        tenantId, clientId, taskId), reserved == 1);
            }
        };
    }

    private long now() {
        long value = clock.getAsLong();
        if (value <= 0) {
            throw invalidPersisted("Compatibility clock returned a nonpositive timestamp");
        }
        return value;
    }

    private Long firstNonNull(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private AgentTaskCollaborationException invalid(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_REQUEST, message);
    }

    private AgentTaskCollaborationException invalidPersisted(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_PERSISTED_STATE, message);
    }

    private AgentTaskCollaborationException forbidden() {
        return new AgentTaskCollaborationException(
                Reason.FORBIDDEN, "Operation is not permitted in the requested scope");
    }

    private AgentTaskCollaborationException notFound() {
        return new AgentTaskCollaborationException(
                Reason.NOT_FOUND, "Task was not found in the requested scope");
    }

    private record AssignedWorkItem(String agentId, String workItemId) {
    }

    @FunctionalInterface
    public interface AssignmentPrecommitValidator {
        /** A trusted grant writer may resolve a distinct executor; ordinary callers retain owner scope. */
        default String identityOwner(String taskOwner) { return taskOwner; }

        /** Called with the task root locked, before any canonical identity/runtime lock. */
        default void beforeIdentityLock(AgentTaskMetaEntity task, List<String> agentIds) {
        }

        void validate(AgentTaskMetaEntity task, List<String> agentIds);
    }

    public record AssignOutcome(
            List<String> agentIds,
            boolean changed,
            String taskAssignedEventId,
            Long occurredAt) {
        public AssignOutcome(List<String> agentIds, boolean changed) {
            this(agentIds, changed, null, null);
        }

        public AssignOutcome {
            agentIds = List.copyOf(agentIds);
            if (!changed && (taskAssignedEventId != null || occurredAt != null)) {
                throw new IllegalArgumentException("unchanged assignment cannot carry command causation");
            }
            if (changed && ((taskAssignedEventId == null) != (occurredAt == null))) {
                throw new IllegalArgumentException("changed assignment causation must be complete");
            }
        }
    }

}
