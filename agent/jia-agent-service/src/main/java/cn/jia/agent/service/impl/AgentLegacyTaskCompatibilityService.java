package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskAggregationCommandDTO;
import cn.jia.agent.entity.AgentTaskAggregationDTO;
import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskAggregationService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Transactional adapter from the legacy task-level assign/report API to the scoped collaboration
 * model. It intentionally does not expose or weaken B04 lease/result operations: only adapter-owned
 * leases carry the {@code legacy_} prefix, while a B04-owned claim/lease is rejected fail closed.
 */
@Named
public class AgentLegacyTaskCompatibilityService {
    static final String WORK_TYPE = "legacy_default";
    private static final String MEMBER_ROLE = "worker";
    private static final String ASSIGNMENT_MANUAL = "manual";
    private static final String ASSIGNMENT_AUTO = "auto";
    private static final Pattern CANONICAL_AGENT_ID = Pattern.compile("^agt_[0-9a-f]{32}$");
    private static final Set<String> MEMBER_ROLES = Set.of("coordinator", "worker", "reviewer", "observer");
    private static final Set<String> ASSIGNMENT_SOURCES = Set.of(ASSIGNMENT_MANUAL, ASSIGNMENT_AUTO, "migration");
    private static final int MAX_DEFAULT_ITEMS = 2;
    private static final long LEGACY_LEASE_UNTIL = Long.MAX_VALUE - 1;

    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskMemberDao memberDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskAggregationService aggregationService;
    private final LongSupplier clock;

    @Inject
    public AgentLegacyTaskCompatibilityService(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskAggregationService aggregationService) {
        this(taskMetaDao, memberDao, workItemDao, aggregationService, System::currentTimeMillis);
    }

    AgentLegacyTaskCompatibilityService(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskAggregationService aggregationService,
            LongSupplier clock) {
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.aggregationService = Objects.requireNonNull(aggregationService, "aggregationService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(rollbackFor = Exception.class)
    public void assign(String tenantId, String clientId, String taskId,
            List<String> agentIds, boolean automatic) {
        requireScope(tenantId, clientId, taskId);
        if (agentIds == null || agentIds.isEmpty()) {
            throw invalid("At least one canonical agentId is required");
        }
        agentIds.forEach(this::requireCanonicalAgentId);

        AgentTaskMetaEntity task = lockTask(tenantId, clientId, taskId);
        AgentTaskStatus taskStatus = persistedTaskStatus(task.getRewardStatus());
        if (taskStatus != AgentTaskStatus.ASSIGNED) {
            throw invalid("Legacy collaboration rows may only be created for an assigned task");
        }

        long changedAt = now();
        String source = automatic ? ASSIGNMENT_AUTO : ASSIGNMENT_MANUAL;
        for (String agentId : agentIds) {
            ensureMember(tenantId, clientId, taskId, agentId, source, changedAt);
            ensureDefaultWorkItem(tenantId, clientId, taskId, agentId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ReportOutcome report(String tenantId, String clientId, String taskId,
            String agentId, String requestedStatus, String failureReason) {
        requireScope(tenantId, clientId, taskId);
        requireCanonicalAgentId(agentId);
        AgentTaskStatus reportStatus = requireReportStatus(requestedStatus);
        AgentTaskMetaEntity task = lockTask(tenantId, clientId, taskId);
        persistedTaskStatus(task.getRewardStatus());
        requireTaskVersion(task.getTaskVersion());

        AgentTaskMemberEntity member = memberDao.findByTaskAndAgent(
                tenantId, clientId, taskId, agentId);
        if (member == null) {
            throw forbidden();
        }
        validateMember(member, taskId, agentId);
        AgentTaskWorkItemEntity item = requireUniqueDefaultWorkItem(
                tenantId, clientId, taskId, agentId);

        long changedAt = now();
        updateMemberForReport(tenantId, clientId, taskId, agentId,
                member, reportStatus, failureReason, changedAt);
        updateWorkItemForReport(tenantId, clientId, taskId, agentId,
                item, reportStatus, changedAt);

        AgentTaskAggregationCommandDTO command = new AgentTaskAggregationCommandDTO();
        command.setExpectedVersion(task.getTaskVersion());
        AgentTaskAggregationDTO aggregate = aggregationService.aggregate(
                tenantId, clientId, taskId, command);
        return new ReportOutcome(aggregate.getStatus(), aggregate.getTaskVersion());
    }

    private AgentTaskMetaEntity lockTask(String tenantId, String clientId, String taskId) {
        AgentTaskMetaEntity task = taskMetaDao.findByTaskIdForUpdate(tenantId, clientId, taskId);
        if (task == null) {
            throw notFound();
        }
        if (!tenantId.equals(task.getTenantId()) || !clientId.equals(task.getClientId())
                || !taskId.equals(task.getTaskId())) {
            throw invalidPersisted("Scoped task identity is non-canonical or mismatched");
        }
        requireTaskVersion(task.getTaskVersion());
        return task;
    }

    private void ensureMember(String tenantId, String clientId, String taskId,
            String agentId, String source, long changedAt) {
        AgentTaskMemberEntity existing = memberDao.findByTaskAndAgent(
                tenantId, clientId, taskId, agentId);
        if (existing != null) {
            validateMember(existing, taskId, agentId);
            AgentTaskMemberStatus status = persistedMemberStatus(existing.getMemberStatus());
            if (status != AgentTaskMemberStatus.ACCEPTED && status != AgentTaskMemberStatus.WORKING) {
                throw invalid("Existing task member cannot be reassigned through the legacy adapter");
            }
            return;
        }

        AgentTaskMemberDTO member = new AgentTaskMemberDTO();
        member.setTaskId(taskId);
        member.setAgentId(agentId);
        member.setMemberRole(MEMBER_ROLE);
        member.setMemberStatus(AgentTaskMemberStatus.ACCEPTED.value());
        member.setAssignmentSource(source);
        member.setJoinedAt(changedAt);
        member.setAcceptedAt(changedAt);
        requireSingleInsert(memberDao.insert(tenantId, clientId, member), "task member");
    }

    private void ensureDefaultWorkItem(
            String tenantId, String clientId, String taskId, String agentId) {
        List<AgentTaskWorkItemEntity> existing = defaultWorkItems(
                tenantId, clientId, taskId, agentId);
        if (existing.size() > 1) {
            throw invalidPersisted("Task member has multiple legacy default work items");
        }
        if (existing.size() == 1) {
            validateDefaultWorkItem(existing.getFirst(), taskId, agentId);
            return;
        }

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
        requireSingleInsert(workItemDao.insert(tenantId, clientId, item), "default work item");
    }

    private AgentTaskWorkItemEntity requireUniqueDefaultWorkItem(
            String tenantId, String clientId, String taskId, String agentId) {
        List<AgentTaskWorkItemEntity> items = defaultWorkItems(
                tenantId, clientId, taskId, agentId);
        if (items.isEmpty()) {
            throw invalidPersisted("Task member has no legacy default work item");
        }
        if (items.size() != 1) {
            throw invalidPersisted("Task member has multiple legacy default work items");
        }
        AgentTaskWorkItemEntity item = items.getFirst();
        validateDefaultWorkItem(item, taskId, agentId);
        return item;
    }

    private List<AgentTaskWorkItemEntity> defaultWorkItems(
            String tenantId, String clientId, String taskId, String agentId) {
        List<AgentTaskWorkItemEntity> rows = workItemDao.listByTaskAssigneeAndType(
                tenantId, clientId, taskId, agentId, WORK_TYPE, MAX_DEFAULT_ITEMS);
        if (rows == null) {
            throw invalidPersisted("Default work item query returned no snapshot");
        }
        return rows;
    }

    private void updateMemberForReport(
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
            return;
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
    }

    private void updateWorkItemForReport(
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
            validateIdempotentWorkItem(current, target);
            return;
        }
        if (currentStatus.isTerminal()) {
            throw invalid("Terminal default work item cannot report a different status");
        }
        if (currentStatus == AgentTaskWorkItemStatus.CLAIMED
                || currentStatus == AgentTaskWorkItemStatus.RUNNING) {
            if (!isLegacyLease(current.getLeaseToken())) {
                throw new AgentTaskCollaborationException(Reason.RESERVED_FOR_LEASE_PROTOCOL,
                        "B04-owned work item must use the lease/result protocol");
            }
        } else if (current.getLeaseToken() != null || current.getLeaseUntil() != null) {
            throw invalidPersisted("Non-active default work item retains lease state");
        }
        if (target == AgentTaskWorkItemStatus.RUNNING
                && currentStatus != AgentTaskWorkItemStatus.READY) {
            throw invalid("Legacy running report requires a ready default work item");
        }
        if (target == AgentTaskWorkItemStatus.COMPLETED
                && currentStatus != AgentTaskWorkItemStatus.READY
                && currentStatus != AgentTaskWorkItemStatus.RUNNING
                && currentStatus != AgentTaskWorkItemStatus.SUBMITTED) {
            throw invalid("Legacy completed report cannot advance the current work item status");
        }
        if (target == AgentTaskWorkItemStatus.FAILED
                && currentStatus != AgentTaskWorkItemStatus.READY
                && currentStatus != AgentTaskWorkItemStatus.RUNNING
                && currentStatus != AgentTaskWorkItemStatus.BLOCKED) {
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
    }

    private void validateMember(AgentTaskMemberEntity member, String taskId, String agentId) {
        if (!taskId.equals(member.getTaskId()) || !agentId.equals(member.getAgentId())
                || !MEMBER_ROLES.contains(member.getMemberRole())
                || !ASSIGNMENT_SOURCES.contains(member.getAssignmentSource())
                || member.getVersion() == null || member.getVersion() < 0
                || member.getVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted task member is incomplete or mismatched");
        }
        persistedMemberStatus(member.getMemberStatus());
    }

    private void validateDefaultWorkItem(
            AgentTaskWorkItemEntity item, String taskId, String agentId) {
        if (!taskId.equals(item.getTaskId()) || !agentId.equals(item.getAssigneeAgentId())
                || !WORK_TYPE.equals(item.getWorkType()) || !Boolean.TRUE.equals(item.getRequiredItem())
                || StringUtil.isBlank(item.getWorkItemId()) || StringUtil.isBlank(item.getTitle())
                || item.getPriority() == null || item.getAttemptCount() == null
                || item.getMaxAttempts() == null || item.getAttemptCount() < 0
                || item.getMaxAttempts() <= 0 || item.getAttemptCount() > item.getMaxAttempts()
                || item.getVersion() == null || item.getVersion() < 0
                || item.getVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted legacy default work item is incomplete or mismatched");
        }
        persistedWorkItemStatus(item.getStatus());
    }

    private void validateIdempotentWorkItem(
            AgentTaskWorkItemEntity item, AgentTaskWorkItemStatus status) {
        if (status == AgentTaskWorkItemStatus.RUNNING) {
            if (!isLegacyLease(item.getLeaseToken())
                    || item.getLeaseUntil() == null || item.getLeaseUntil() <= 0) {
                throw new AgentTaskCollaborationException(Reason.RESERVED_FOR_LEASE_PROTOCOL,
                        "B04-owned work item must use the lease/result protocol");
            }
        } else if (item.getLeaseToken() != null || item.getLeaseUntil() != null) {
            throw invalidPersisted("Terminal default work item retains lease state");
        }
        if (status == AgentTaskWorkItemStatus.COMPLETED
                && (StringUtil.isBlank(item.getResultArtifactId())
                || item.getCompletedAt() == null || item.getCompletedAt() <= 0)) {
            throw invalidPersisted("Completed default work item has no accepted result marker");
        }
        if (status == AgentTaskWorkItemStatus.FAILED
                && !Objects.equals(item.getAttemptCount(), item.getMaxAttempts())) {
            throw invalidPersisted("Failed default work item has not exhausted attempts");
        }
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

    private void requireScope(String tenantId, String clientId, String taskId) {
        if (StringUtil.isBlank(tenantId) || StringUtil.isBlank(clientId)
                || StringUtil.isBlank(taskId)) {
            throw invalid("tenantId, clientId and taskId are required");
        }
    }

    private void requireCanonicalAgentId(String agentId) {
        if (agentId == null || !CANONICAL_AGENT_ID.matcher(agentId).matches()) {
            throw invalid("agentId must be an ADR-001 canonical identifier");
        }
    }

    private void requireTaskVersion(Long version) {
        if (version == null || version < 0 || version == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted task version is invalid");
        }
    }

    private void requireSingleInsert(int inserted, String aggregate) {
        if (inserted != 1) {
            throw invalidPersisted("Scoped " + aggregate + " insert affected an unexpected row count");
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
        return failureReason;
    }

    private boolean isLegacyLease(String leaseToken) {
        return leaseToken != null && leaseToken.startsWith("legacy_") && leaseToken.length() == 39;
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

    private long now() {
        long value = clock.getAsLong();
        if (value < 0) {
            throw invalidPersisted("Compatibility clock returned a negative timestamp");
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

    public record ReportOutcome(String taskStatus, long taskVersion) {
    }
}
