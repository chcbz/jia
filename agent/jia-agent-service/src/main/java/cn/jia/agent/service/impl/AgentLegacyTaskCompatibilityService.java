package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskAggregationCommandDTO;
import cn.jia.agent.entity.AgentTaskAggregationDTO;
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
import cn.jia.agent.service.AgentTaskAggregationService;
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
 * Transactional adapter from the legacy task-level assign/report API to the scoped collaboration
 * model. Identity authenticity is delegated to A08. This adapter owns only deterministic
 * {@code legacy_*} leases and result markers; B04/B06-owned work is rejected fail closed.
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
    private static final long LEGACY_LEASE_UNTIL = Long.MAX_VALUE - 1;

    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskMemberDao memberDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskAggregationService aggregationService;
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
            AgentTaskAggregationService aggregationService,
            AgentIdentityService identityService,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter) {
        this(taskMetaDao, memberDao, workItemDao, aggregationService, identityService,
                mutationTransaction, eventWriter, System::currentTimeMillis);
    }

    AgentLegacyTaskCompatibilityService(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskAggregationService aggregationService,
            AgentIdentityService identityService,
            LongSupplier clock) {
        this(taskMetaDao, memberDao, workItemDao, aggregationService, identityService,
                directTransaction(taskMetaDao), command -> null, clock);
    }

    AgentLegacyTaskCompatibilityService(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskAggregationService aggregationService,
            AgentIdentityService identityService,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter,
            LongSupplier clock) {
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.aggregationService = Objects.requireNonNull(aggregationService, "aggregationService");
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
    public AssignOutcome assign(String tenantId, String clientId, String taskId,
            List<String> requestedAgentIds, boolean automatic) {
        return assignResolved(tenantId, clientId, taskId,
                resolveAgentIds(tenantId, clientId, tenantId, requestedAgentIds), automatic);
    }

    @Transactional(rollbackFor = Exception.class)
    public AssignOutcome assignResolved(String tenantId, String clientId, String taskId,
            List<String> canonicalAgentIds, boolean automatic) {
        return assignResolved(tenantId, clientId, taskId, canonicalAgentIds, automatic,
                (task, agentIds) -> { });
    }

    @Transactional(rollbackFor = Exception.class)
    public AssignOutcome assignResolved(String tenantId, String clientId, String taskId,
            List<String> canonicalAgentIds, boolean automatic,
            AssignmentPrecommitValidator precommitValidator) {
        requireScope(tenantId, clientId, tenantId);
        requireExactText(taskId, "taskId", 100);
        List<String> agentIds = requireResolvedAgentIds(canonicalAgentIds);
        Objects.requireNonNull(precommitValidator, "precommitValidator");
        long reservedAt = now();
        return mutationTransaction.executeAfterTaskRootReservation(
                tenantId, clientId, taskId,
                () -> taskMetaDao.reserveOpenTaskRoot(tenantId, clientId, taskId, reservedAt),
                (task, rootCreated) -> assignResolvedLocked(
                        tenantId, clientId, taskId, agentIds, automatic, reservedAt, task,
                        precommitValidator));
    }

    private AssignOutcome assignResolvedLocked(
            String tenantId, String clientId, String taskId, List<String> agentIds,
            boolean automatic, long changedAt, AgentTaskMetaEntity task,
            AssignmentPrecommitValidator precommitValidator) {
        validateLockedTask(task, tenantId, clientId, taskId);
        precommitValidator.beforeIdentityLock(task, agentIds);
        List<String> lockedAgentIds = identityService.lockActiveCanonicalAgentIdsInScope(
                tenantId, clientId, tenantId, agentIds);
        if (!agentIds.equals(lockedAgentIds)) {
            throw forbidden();
        }
        AgentTaskStatus taskStatus = persistedTaskStatus(task.getRewardStatus());
        if (taskStatus != AgentTaskStatus.OPEN && taskStatus != AgentTaskStatus.PLANNING
                && taskStatus != AgentTaskStatus.ASSIGNED) {
            throw invalid("Task cannot be assigned in its current status");
        }

        List<AgentTaskMemberEntity> members = requireSnapshot(
                memberDao.listByTask(tenantId, clientId, taskId), "task member");
        List<AgentTaskWorkItemEntity> defaultItems = requireSnapshot(
                workItemDao.listByTask(
                        tenantId, clientId, taskId, null, MAX_COLLABORATION_ROWS),
                "task work item");
        rejectTruncatedSnapshot(members, "task member");
        rejectTruncatedSnapshot(defaultItems, "legacy default work item");

        if (!members.isEmpty() || !defaultItems.isEmpty()) {
            List<String> persistedAgentIds = validateIdempotentAssignment(
                    task, taskId, agentIds, members, defaultItems);
            return new AssignOutcome(persistedAgentIds, false, null, null);
        }

        precommitValidator.validate(task, agentIds);
        String fromStatus = taskStatus.value();
        applyAssignmentMeta(task, agentIds, changedAt);
        requireSingleMutation(taskMetaDao.updateById(task), "task assignment metadata");

        String source = automatic ? ASSIGNMENT_AUTO : ASSIGNMENT_MANUAL;
        for (int index = 0; index < agentIds.size(); index++) {
            String agentId = agentIds.get(index);
            insertMember(tenantId, clientId, taskId, agentId, source, changedAt,
                    index == 0 ? MEMBER_ROLE_COORDINATOR : MEMBER_ROLE_WORKER);
            insertDefaultWorkItem(tenantId, clientId, taskId, agentId);
        }
        String taskAssignedEventId = appendAssignmentEvents(
                tenantId, clientId, taskId, task, agentIds, source, fromStatus, changedAt);
        return new AssignOutcome(agentIds, true, taskAssignedEventId, changedAt);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReportOutcome report(String tenantId, String clientId, String taskId,
            String requestedAgentId, String requestedStatus, String failureReason) {
        String canonicalAgentId = resolveAgentId(
                tenantId, clientId, tenantId, requestedAgentId);
        return reportResolved(tenantId, clientId, taskId,
                canonicalAgentId, requestedStatus, failureReason);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReportOutcome reportResolved(String tenantId, String clientId, String taskId,
            String agentId, String requestedStatus, String failureReason) {
        requireScope(tenantId, clientId, tenantId);
        requireExactText(taskId, "taskId", 100);
        requireExactText(agentId, "agentId", 100);
        AgentTaskStatus reportStatus = requireReportStatus(requestedStatus);
        return mutationTransaction.executeWithLockedTaskRoot(
                tenantId, clientId, taskId, task -> reportResolvedLocked(
                        tenantId, clientId, taskId, agentId, reportStatus,
                        failureReason, task));
    }

    private ReportOutcome reportResolvedLocked(
            String tenantId, String clientId, String taskId, String agentId,
            AgentTaskStatus reportStatus, String failureReason, AgentTaskMetaEntity task) {
        validateLockedTask(task, tenantId, clientId, taskId);
        fundedBountyLegacyGuard.requireLifecycleAllowed(tenantId, clientId, taskId, true);
        List<String> lockedAgentIds = identityService.lockActiveCanonicalAgentIdsInScope(
                tenantId, clientId, tenantId, List.of(agentId));
        if (!List.of(agentId).equals(lockedAgentIds)) {
            throw forbidden();
        }
        AgentTaskStatus previousTaskStatus = persistedTaskStatus(task.getRewardStatus());
        requireTaskVersion(task.getTaskVersion());

        AgentTaskMemberEntity member = memberDao.findByTaskAndAgent(
                tenantId, clientId, taskId, agentId);
        if (member == null) {
            throw forbidden();
        }
        validateMember(member, tenantId, clientId, taskId, agentId);
        AgentTaskWorkItemEntity item = requireUniqueDefaultWorkItem(
                tenantId, clientId, taskId, agentId);
        rejectB04B06OwnedState(item, taskId, agentId);
        LegacyReportState currentState = validateLegacyReportState(
                member, item, taskId, agentId);
        boolean exactDuplicate = currentState.matches(reportStatus);
        validateTaskForLegacyReport(
                previousTaskStatus, reportStatus, exactDuplicate, failureReason, member);

        long changedAt = now();
        boolean memberChanged = updateMemberForReport(
                tenantId, clientId, taskId, agentId,
                member, reportStatus, failureReason, changedAt);
        if (memberChanged) {
            appendMemberReportEvent(tenantId, clientId, taskId, agentId,
                    member, reportStatus, changedAt);
        }
        boolean itemChanged = updateWorkItemForReport(
                tenantId, clientId, taskId, agentId,
                item, reportStatus, changedAt);
        if (itemChanged) {
            appendWorkItemReportEvent(tenantId, clientId, taskId, agentId,
                    item, reportStatus, changedAt);
        }

        AgentTaskAggregationCommandDTO command = new AgentTaskAggregationCommandDTO();
        command.setExpectedVersion(task.getTaskVersion());
        AgentTaskAggregationDTO aggregate = aggregationService.aggregate(
                tenantId, clientId, taskId, command);
        boolean aggregateChanged = Boolean.TRUE.equals(aggregate.getChanged());
        if (exactDuplicate && aggregateChanged) {
            throw invalidPersisted("Duplicate legacy report exposed aggregate state drift");
        }
        validateAggregateResult(reportStatus, aggregate);
        boolean changed = memberChanged || itemChanged || aggregateChanged;
        boolean terminalTransition = aggregateChanged
                && !previousTaskStatus.isOperationalTerminal()
                && persistedTaskStatus(aggregate.getStatus()).isOperationalTerminal();
        List<String> memberAgentIds = currentMemberAgentIds(
                tenantId, clientId, taskId);
        return new ReportOutcome(aggregate.getStatus(), aggregate.getTaskVersion(), changed,
                terminalTransition, agentId, memberAgentIds);
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
            String tenantId, String clientId, String taskId, AgentTaskMetaEntity task,
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
                tenantId, clientId, taskId, TaskEventType.TASK_ASSIGNED,
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
                            tenantId, clientId, taskId, TaskEventType.MEMBER_ACCEPTED,
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
                            tenantId, clientId, taskId, TaskEventType.WORK_ITEM_READY,
                            TaskEventType.ActorType.SYSTEM, null,
                            TaskEventType.Aggregate.WORK_ITEM, assigned.workItemId(),
                            itemPayload, occurredAt, 0L));
                });
        return taskAssignedEventId;
    }

    private void appendMemberReportEvent(
            String tenantId, String clientId, String taskId, String agentId,
            AgentTaskMemberEntity member, AgentTaskStatus reportStatus, long occurredAt) {
        String targetStatus = switch (reportStatus) {
            case RUNNING -> AgentTaskMemberStatus.WORKING.value();
            case COMPLETED -> AgentTaskMemberStatus.DONE.value();
            case FAILED -> AgentTaskMemberStatus.FAILED.value();
            default -> throw invalid("Unsupported legacy report status");
        };
        long resultVersion = member.getVersion() + 1;
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.AGENT_ID, agentId)
                .put(TaskEventPayload.Key.MEMBER_ID, agentId)
                .put(TaskEventPayload.Key.ROLE, member.getMemberRole())
                .put(TaskEventPayload.Key.FROM_STATUS, member.getMemberStatus())
                .put(TaskEventPayload.Key.TO_STATUS, targetStatus)
                .put(TaskEventPayload.Key.EXPECTED_VERSION, member.getVersion())
                .put(TaskEventPayload.Key.RESULT_VERSION, resultVersion)
                .put(TaskEventPayload.Key.UPDATED_AT, occurredAt);
        eventWriter.append(AgentTaskMutationEventSupport.command(
                tenantId, clientId, taskId,
                AgentTaskMutationEventSupport.memberEvent(targetStatus),
                TaskEventType.ActorType.SYSTEM, null,
                TaskEventType.Aggregate.MEMBER, agentId,
                payload, occurredAt, resultVersion));
    }

    private void appendWorkItemReportEvent(
            String tenantId, String clientId, String taskId, String agentId,
            AgentTaskWorkItemEntity item, AgentTaskStatus reportStatus, long occurredAt) {
        String targetStatus = switch (reportStatus) {
            case RUNNING -> AgentTaskWorkItemStatus.RUNNING.value();
            case COMPLETED -> AgentTaskWorkItemStatus.COMPLETED.value();
            case FAILED -> AgentTaskWorkItemStatus.FAILED.value();
            default -> throw invalid("Unsupported legacy report status");
        };
        long resultVersion = item.getVersion() + 1;
        long attemptCount = reportStatus == AgentTaskStatus.FAILED
                ? item.getMaxAttempts() : item.getAttemptCount();
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, item.getWorkItemId())
                .put(TaskEventPayload.Key.ASSIGNEE_AGENT_ID, agentId)
                .put(TaskEventPayload.Key.FROM_STATUS, item.getStatus())
                .put(TaskEventPayload.Key.TO_STATUS, targetStatus)
                .put(TaskEventPayload.Key.ATTEMPT_COUNT, attemptCount)
                .put(TaskEventPayload.Key.MAX_ATTEMPTS, item.getMaxAttempts())
                .put(TaskEventPayload.Key.EXPECTED_VERSION, item.getVersion())
                .put(TaskEventPayload.Key.RESULT_VERSION, resultVersion)
                .put(TaskEventPayload.Key.UPDATED_AT, occurredAt);
        eventWriter.append(AgentTaskMutationEventSupport.command(
                tenantId, clientId, taskId,
                AgentTaskMutationEventSupport.workItemEvent(targetStatus),
                TaskEventType.ActorType.SYSTEM, null,
                TaskEventType.Aggregate.WORK_ITEM, item.getWorkItemId(),
                payload, occurredAt, resultVersion));
    }

    private void insertMember(String tenantId, String clientId, String taskId,
            String agentId, String source, long changedAt, String role) {
        AgentTaskMemberDTO member = new AgentTaskMemberDTO();
        member.setTaskId(taskId);
        member.setAgentId(agentId);
        member.setMemberRole(role);
        member.setMemberStatus(AgentTaskMemberStatus.ACCEPTED.value());
        member.setAssignmentSource(source);
        member.setJoinedAt(changedAt);
        member.setAcceptedAt(changedAt);
        requireSingleMutation(memberDao.insert(tenantId, clientId, member), "task member");
    }

    private void insertDefaultWorkItem(
            String tenantId, String clientId, String taskId, String agentId) {
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
        requireSingleMutation(workItemDao.insert(tenantId, clientId, item), "default work item");
    }

    private AgentTaskWorkItemEntity requireUniqueDefaultWorkItem(
            String tenantId, String clientId, String taskId, String agentId) {
        List<AgentTaskWorkItemEntity> items = requireSnapshot(
                workItemDao.listByTaskAndAssignee(
                        tenantId, clientId, taskId, agentId, MAX_COLLABORATION_ROWS),
                "member work item");
        rejectTruncatedSnapshot(items, "member work item");
        List<AgentTaskWorkItemEntity> legacyItems = items.stream()
                .filter(item -> WORK_TYPE.equals(item.getWorkType()))
                .toList();
        if (legacyItems.isEmpty()) {
            throw invalidPersisted("Task member has no legacy default work item");
        }
        if (legacyItems.size() != 1) {
            throw invalidPersisted("Task member has multiple legacy default work items");
        }
        if (items.size() != legacyItems.size()) {
            throw reserved("Task member mixes legacy default and non-legacy work items");
        }
        AgentTaskWorkItemEntity item = legacyItems.getFirst();
        validateDefaultWorkItem(item, tenantId, clientId, taskId, agentId);
        return item;
    }


    private LegacyReportState validateLegacyReportState(
            AgentTaskMemberEntity member, AgentTaskWorkItemEntity item,
            String taskId, String agentId) {
        AgentTaskMemberStatus memberStatus = persistedMemberStatus(member.getMemberStatus());
        AgentTaskWorkItemStatus itemStatus = persistedWorkItemStatus(item.getStatus());
        switch (memberStatus) {
            case ACCEPTED -> {
                if (itemStatus != AgentTaskWorkItemStatus.READY
                        || member.getCompletedAt() != null || member.getFailureReason() != null) {
                    throw invalidPersisted("Accepted legacy member/item state is not byte-exact");
                }
                validateLegacyMutableState(item, taskId, agentId, itemStatus);
            }
            case WORKING -> {
                if (itemStatus != AgentTaskWorkItemStatus.RUNNING
                        || member.getStartedAt() == null || member.getStartedAt() <= 0
                        || member.getCompletedAt() != null || member.getFailureReason() != null) {
                    throw invalidPersisted("Working legacy member/item state is not byte-exact");
                }
                validateIdempotentWorkItem(item, taskId, agentId, itemStatus);
            }
            case DONE -> {
                if (itemStatus != AgentTaskWorkItemStatus.COMPLETED
                        || member.getCompletedAt() == null || member.getCompletedAt() <= 0
                        || member.getFailureReason() != null) {
                    throw invalidPersisted("Completed legacy member/item state is not byte-exact");
                }
                validateIdempotentWorkItem(item, taskId, agentId, itemStatus);
            }
            case FAILED -> {
                if (itemStatus != AgentTaskWorkItemStatus.FAILED
                        || member.getCompletedAt() == null || member.getCompletedAt() <= 0
                        || StringUtil.isBlank(member.getFailureReason())) {
                    throw invalidPersisted("Failed legacy member/item state is not byte-exact");
                }
                validateIdempotentWorkItem(item, taskId, agentId, itemStatus);
            }
            default -> throw invalid("Task member is not owned by the legacy report state machine");
        }
        return new LegacyReportState(memberStatus, itemStatus);
    }

    private void validateTaskForLegacyReport(
            AgentTaskStatus taskStatus, AgentTaskStatus reportStatus, boolean exactDuplicate,
            String failureReason, AgentTaskMemberEntity member) {
        if (taskStatus.isOperationalTerminal()) {
            if (!exactDuplicate || taskStatus != reportStatus
                    || taskStatus == AgentTaskStatus.CANCELLED
                    || taskStatus == AgentTaskStatus.ARCHIVED) {
                throw invalid("Terminal task accepts only an exact duplicate legacy report");
            }
            if (reportStatus == AgentTaskStatus.FAILED
                    && !Objects.equals(member.getFailureReason(), requiredFailureReason(failureReason))) {
                throw invalid("Duplicate failed report must preserve the byte-exact failure reason");
            }
            return;
        }
        if (taskStatus != AgentTaskStatus.ASSIGNED && taskStatus != AgentTaskStatus.RUNNING) {
            throw invalid("Task is not in a legacy-reportable aggregate state");
        }
        if (exactDuplicate && reportStatus == AgentTaskStatus.FAILED
                && !Objects.equals(member.getFailureReason(), requiredFailureReason(failureReason))) {
            throw invalid("Duplicate failed report must preserve the byte-exact failure reason");
        }
    }

    private void validateAggregateResult(
            AgentTaskStatus reportStatus, AgentTaskAggregationDTO aggregate) {
        AgentTaskStatus aggregateStatus = persistedTaskStatus(aggregate.getStatus());
        if (reportStatus == AgentTaskStatus.RUNNING && aggregateStatus != AgentTaskStatus.RUNNING) {
            throw invalidPersisted("Running legacy report produced an inconsistent aggregate result");
        }
        if (reportStatus == AgentTaskStatus.FAILED && aggregateStatus != AgentTaskStatus.FAILED) {
            throw invalidPersisted("Failed legacy report did not produce a failed aggregate");
        }
        if (reportStatus == AgentTaskStatus.COMPLETED
                && aggregateStatus != AgentTaskStatus.RUNNING
                && aggregateStatus != AgentTaskStatus.COMPLETED) {
            throw invalidPersisted("Completed legacy report produced an inconsistent aggregate result");
        }
        if (aggregateStatus == AgentTaskStatus.COMPLETED
                && (aggregate.getRequiredWorkItemCount() == null
                || aggregate.getRequiredCompletedCount() == null
                || aggregate.getRequiredWorkItemCount() <= 0
                || !aggregate.getRequiredWorkItemCount().equals(
                        aggregate.getRequiredCompletedCount())
                || aggregate.getRequiredFailedCount() == null
                || aggregate.getRequiredFailedCount() != 0)) {
            throw invalidPersisted("Completed task aggregate is not backed by all required results");
        }
        if (aggregateStatus == AgentTaskStatus.FAILED
                && (aggregate.getRequiredFailedCount() == null
                || aggregate.getRequiredFailedCount() <= 0)) {
            throw invalidPersisted("Failed task aggregate is not backed by a required failure");
        }
    }

    private boolean updateMemberForReport(
            String tenantId, String clientId, String taskId, String agentId,
            AgentTaskMemberEntity current, AgentTaskStatus reportStatus,
            String failureReason, long changedAt) {
        AgentTaskMemberStatus currentStatus = persistedMemberStatus(current.getMemberStatus());
        AgentTaskMemberStatus target = switch (reportStatus) {
            case RUNNING -> AgentTaskMemberStatus.WORKING;
            case COMPLETED -> AgentTaskMemberStatus.DONE;
            case FAILED -> AgentTaskMemberStatus.FAILED;
            default -> throw invalid("Unsupported legacy report status");
        };
        if (currentStatus == target) {
            if (target.isTerminal()
                    && (current.getCompletedAt() == null || current.getCompletedAt() <= 0)) {
                throw invalidPersisted("Terminal task member has no completion marker");
            }
            if (target == AgentTaskMemberStatus.FAILED
                    && StringUtil.isBlank(current.getFailureReason())) {
                throw invalidPersisted("Failed task member has no failure reason");
            }
            return false;
        }
        if (currentStatus.isTerminal()) {
            throw invalid("Terminal task member cannot report a different status");
        }
        if (currentStatus != AgentTaskMemberStatus.ACCEPTED
                && currentStatus != AgentTaskMemberStatus.WORKING) {
            throw invalid("Task member is not active for legacy reporting");
        }

        AgentTaskMemberDTO update = copyMember(current);
        update.setMemberStatus(target.value());
        if (target == AgentTaskMemberStatus.WORKING && update.getStartedAt() == null) {
            update.setStartedAt(changedAt);
        }
        if (target.isTerminal() && update.getCompletedAt() == null) {
            update.setCompletedAt(changedAt);
        }
        update.setFailureReason(target == AgentTaskMemberStatus.FAILED
                ? requiredFailureReason(failureReason) : null);
        requireSingleCas(memberDao.updateByVersion(
                tenantId, clientId, taskId, agentId, current.getVersion(), update), "task member");
        return true;
    }

    private boolean updateWorkItemForReport(
            String tenantId, String clientId, String taskId, String agentId,
            AgentTaskWorkItemEntity current, AgentTaskStatus reportStatus, long changedAt) {
        AgentTaskWorkItemStatus currentStatus = persistedWorkItemStatus(current.getStatus());
        AgentTaskWorkItemStatus target = switch (reportStatus) {
            case RUNNING -> AgentTaskWorkItemStatus.RUNNING;
            case COMPLETED -> AgentTaskWorkItemStatus.COMPLETED;
            case FAILED -> AgentTaskWorkItemStatus.FAILED;
            default -> throw invalid("Unsupported legacy report status");
        };
        if (currentStatus == target) {
            validateIdempotentWorkItem(current, taskId, agentId, target);
            return false;
        }
        if (currentStatus == AgentTaskWorkItemStatus.SUBMITTED) {
            throw reserved("Submitted work item is owned by the B04/B06 result protocol");
        }
        if (currentStatus.isTerminal()) {
            throw invalid("Terminal default work item cannot report a different status");
        }
        if (currentStatus == AgentTaskWorkItemStatus.CLAIMED
                || currentStatus == AgentTaskWorkItemStatus.BLOCKED) {
            throw reserved("Non-legacy work item state must use the lease/result protocol");
        }
        validateLegacyMutableState(current, taskId, agentId, currentStatus);

        if (target == AgentTaskWorkItemStatus.RUNNING
                && currentStatus != AgentTaskWorkItemStatus.READY) {
            throw invalid("Legacy running report requires a ready default work item");
        }
        if (target == AgentTaskWorkItemStatus.COMPLETED
                && currentStatus != AgentTaskWorkItemStatus.READY
                && currentStatus != AgentTaskWorkItemStatus.RUNNING) {
            throw invalid("Legacy completed report cannot advance the current work item status");
        }
        if (target == AgentTaskWorkItemStatus.FAILED
                && currentStatus != AgentTaskWorkItemStatus.READY
                && currentStatus != AgentTaskWorkItemStatus.RUNNING) {
            throw invalid("Legacy failed report cannot advance the current work item status");
        }

        AgentTaskWorkItemDTO update = copyWorkItem(current);
        update.setStatus(target.value());
        if (target == AgentTaskWorkItemStatus.RUNNING) {
            update.setLeaseToken(legacyLeaseToken(taskId, agentId));
            update.setLeaseUntil(LEGACY_LEASE_UNTIL);
        } else {
            update.setLeaseToken(null);
            update.setLeaseUntil(null);
        }
        if (target == AgentTaskWorkItemStatus.COMPLETED) {
            if (current.getResultArtifactId() != null) {
                throw reserved("Legacy report cannot replace an existing result artifact");
            }
            update.setResultArtifactId(legacyResultId(taskId, agentId));
            update.setSubmittedAt(firstNonNull(update.getSubmittedAt(), changedAt));
            update.setCompletedAt(firstNonNull(update.getCompletedAt(), changedAt));
        }
        if (target == AgentTaskWorkItemStatus.FAILED) {
            update.setAttemptCount(update.getMaxAttempts());
            update.setCompletedAt(firstNonNull(update.getCompletedAt(), changedAt));
        }
        requireSingleCas(workItemDao.updateByVersion(
                tenantId, clientId, current.getWorkItemId(), current.getVersion(), update),
                "default work item");
        return true;
    }

    private void rejectB04B06OwnedState(
            AgentTaskWorkItemEntity item, String taskId, String agentId) {
        AgentTaskWorkItemStatus status = persistedWorkItemStatus(item.getStatus());
        if (status == AgentTaskWorkItemStatus.SUBMITTED) {
            throw reserved("Submitted work item is owned by the B04/B06 result protocol");
        }
        if (status == AgentTaskWorkItemStatus.CLAIMED
                || status == AgentTaskWorkItemStatus.BLOCKED
                || status == AgentTaskWorkItemStatus.RUNNING
                && (!legacyLeaseToken(taskId, agentId).equals(item.getLeaseToken())
                || !Objects.equals(item.getLeaseUntil(), LEGACY_LEASE_UNTIL))) {
            throw reserved("Work item is owned by the B04 lease protocol");
        }
        if (item.getResultArtifactId() != null
                && !legacyResultId(taskId, agentId).equals(item.getResultArtifactId())) {
            throw reserved("Legacy report cannot replace an existing result artifact");
        }
    }

    private void validateLegacyMutableState(
            AgentTaskWorkItemEntity item, String taskId, String agentId,
            AgentTaskWorkItemStatus status) {
        String expectedLease = legacyLeaseToken(taskId, agentId);
        if (status == AgentTaskWorkItemStatus.RUNNING) {
            if (!expectedLease.equals(item.getLeaseToken())
                    || !Objects.equals(item.getLeaseUntil(), LEGACY_LEASE_UNTIL)
                    || item.getResultArtifactId() != null) {
                throw reserved("Running work item is not owned by the exact legacy lease");
            }
            return;
        }
        if (item.getLeaseToken() != null || item.getLeaseUntil() != null) {
            throw invalidPersisted("Non-running legacy work item retains lease state");
        }
        if (item.getResultArtifactId() != null) {
            throw reserved("Legacy report cannot replace an existing result artifact");
        }
    }

    private void validateIdempotentWorkItem(
            AgentTaskWorkItemEntity item, String taskId, String agentId,
            AgentTaskWorkItemStatus status) {
        if (status == AgentTaskWorkItemStatus.RUNNING) {
            validateLegacyMutableState(item, taskId, agentId, status);
            return;
        }
        if (item.getLeaseToken() != null || item.getLeaseUntil() != null) {
            throw invalidPersisted("Terminal default work item retains lease state");
        }
        if (status == AgentTaskWorkItemStatus.COMPLETED
                && (!legacyResultId(taskId, agentId).equals(item.getResultArtifactId())
                || item.getSubmittedAt() == null || item.getSubmittedAt() <= 0
                || item.getCompletedAt() == null || item.getCompletedAt() <= 0)) {
            throw reserved("Completed work item is not an exact legacy result");
        }
        if (status == AgentTaskWorkItemStatus.FAILED
                && (!Objects.equals(item.getAttemptCount(), item.getMaxAttempts())
                || item.getResultArtifactId() != null || item.getSubmittedAt() != null
                || item.getCompletedAt() == null || item.getCompletedAt() <= 0)) {
            throw invalidPersisted("Failed default work item is incomplete");
        }
    }

    private List<String> currentMemberAgentIds(
            String tenantId, String clientId, String taskId) {
        List<AgentTaskMemberEntity> members = requireSnapshot(
                memberDao.listByTask(tenantId, clientId, taskId), "task member");
        rejectTruncatedSnapshot(members, "task member");
        LinkedHashSet<String> agentIds = new LinkedHashSet<>();
        for (AgentTaskMemberEntity member : members) {
            requireExactText(member.getAgentId(), "persisted member agentId", 100);
            validateMember(member, tenantId, clientId, taskId, member.getAgentId());
            AgentTaskMemberStatus status = persistedMemberStatus(member.getMemberStatus());
            if (status == AgentTaskMemberStatus.REJECTED || status == AgentTaskMemberStatus.LEFT) {
                continue;
            }
            if (!agentIds.add(member.getAgentId())) {
                throw invalidPersisted("Task has duplicate member identities");
            }
        }
        return List.copyOf(agentIds);
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

    private AgentTaskMemberDTO copyMember(AgentTaskMemberEntity current) {
        AgentTaskMemberDTO update = new AgentTaskMemberDTO();
        update.setTaskId(current.getTaskId());
        update.setAgentId(current.getAgentId());
        update.setMemberRole(current.getMemberRole());
        update.setMemberStatus(current.getMemberStatus());
        update.setAssignmentSource(current.getAssignmentSource());
        update.setJoinedAt(current.getJoinedAt());
        update.setAcceptedAt(current.getAcceptedAt());
        update.setStartedAt(current.getStartedAt());
        update.setCompletedAt(current.getCompletedAt());
        update.setLastHeartbeatAt(current.getLastHeartbeatAt());
        update.setFailureReason(current.getFailureReason());
        return update;
    }

    private AgentTaskWorkItemDTO copyWorkItem(AgentTaskWorkItemEntity current) {
        AgentTaskWorkItemDTO update = new AgentTaskWorkItemDTO();
        update.setWorkItemId(current.getWorkItemId());
        update.setTaskId(current.getTaskId());
        update.setTitle(current.getTitle());
        update.setDescription(current.getDescription());
        update.setWorkType(current.getWorkType());
        update.setRequiredAbilities(current.getRequiredAbilities());
        update.setAssigneeAgentId(current.getAssigneeAgentId());
        update.setStatus(current.getStatus());
        update.setPriority(current.getPriority());
        update.setRequiredItem(current.getRequiredItem());
        update.setDependencyJson(current.getDependencyJson());
        update.setLeaseToken(current.getLeaseToken());
        update.setLeaseUntil(current.getLeaseUntil());
        update.setAttemptCount(current.getAttemptCount());
        update.setMaxAttempts(current.getMaxAttempts());
        update.setResultArtifactId(current.getResultArtifactId());
        update.setSubmittedAt(current.getSubmittedAt());
        update.setCompletedAt(current.getCompletedAt());
        return update;
    }

    private AgentTaskStatus requireReportStatus(String status) {
        if (!AgentTaskStatus.RUNNING.value().equals(status)
                && !AgentTaskStatus.COMPLETED.value().equals(status)
                && !AgentTaskStatus.FAILED.value().equals(status)) {
            throw invalid("Legacy report status must be canonical running, completed or failed");
        }
        return AgentTaskStatus.fromPersistedValue(status);
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
        if (!tenantId.equals(ownerJiacn)) {
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

    private void requireSingleCas(int updated, String aggregate) {
        if (updated == 0) {
            throw conflict(aggregate + " changed concurrently");
        }
        if (updated != 1) {
            throw invalidPersisted("Scoped " + aggregate + " CAS affected an unexpected row count");
        }
    }

    private String requiredFailureReason(String failureReason) {
        if (StringUtil.isBlank(failureReason)) {
            return "Legacy task report failed";
        }
        if (failureReason.length() > 1000
                || !failureReason.equals(failureReason.strip())
                || failureReason.chars().anyMatch(Character::isISOControl)) {
            throw invalid("failureReason must fit storage, be unpadded, and contain no control characters");
        }
        return failureReason;
    }

    private String defaultWorkItemId(String taskId, String agentId) {
        return "wi_legacy_" + digest(taskId + "\u0000" + agentId);
    }

    private String legacyLeaseToken(String taskId, String agentId) {
        return "legacy_" + digest("lease\u0000" + taskId + "\u0000" + agentId);
    }

    private String legacyResultId(String taskId, String agentId) {
        return "legacy_result_" + digest("result\u0000" + taskId + "\u0000" + agentId);
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
            public <T> T executeWithLockedTaskRootForWorkItem(
                    String tenantId, String clientId, String workItemId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(taskMetaDao.findByWorkItemIdForUpdate(
                        tenantId, clientId, workItemId));
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

    private AgentTaskCollaborationException conflict(String message) {
        return new AgentTaskCollaborationException(Reason.VERSION_CONFLICT, message);
    }

    private AgentTaskCollaborationException reserved(String message) {
        return new AgentTaskCollaborationException(Reason.RESERVED_FOR_LEASE_PROTOCOL, message);
    }


    private record AssignedWorkItem(String agentId, String workItemId) {
    }

    private record LegacyReportState(
            AgentTaskMemberStatus memberStatus, AgentTaskWorkItemStatus workItemStatus) {
        boolean matches(AgentTaskStatus reportStatus) {
            return switch (reportStatus) {
                case RUNNING -> memberStatus == AgentTaskMemberStatus.WORKING
                        && workItemStatus == AgentTaskWorkItemStatus.RUNNING;
                case COMPLETED -> memberStatus == AgentTaskMemberStatus.DONE
                        && workItemStatus == AgentTaskWorkItemStatus.COMPLETED;
                case FAILED -> memberStatus == AgentTaskMemberStatus.FAILED
                        && workItemStatus == AgentTaskWorkItemStatus.FAILED;
                default -> false;
            };
        }
    }

    @FunctionalInterface
    public interface AssignmentPrecommitValidator {
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

    public record ReportOutcome(
            String taskStatus,
            long taskVersion,
            boolean changed,
            boolean terminalTransition,
            String reportingAgentId,
            List<String> memberAgentIds) {
        public ReportOutcome {
            memberAgentIds = List.copyOf(memberAgentIds);
        }
    }
}
