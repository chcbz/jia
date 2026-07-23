package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskAggregationCommandDTO;
import cn.jia.agent.entity.AgentTaskAggregationDTO;
import cn.jia.agent.entity.AgentTaskAggregationSnapshotRow;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentTaskStateException.Reason;
import cn.jia.agent.service.AgentTaskAggregationService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

@Named
public class AgentTaskAggregationServiceImpl implements AgentTaskAggregationService {
    private static final Pattern CANONICAL_AGENT_ID = Pattern.compile("^agt_[0-9a-f]{32}$");
    private static final int MAX_LEASE_TOKEN_LENGTH = 100;
    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskAggregationCalculator calculator;
    private final LongSupplier clock;

    @Inject
    public AgentTaskAggregationServiceImpl(AgentTaskMetaDao taskMetaDao) {
        this(taskMetaDao, new AgentTaskAggregationCalculator(), System::currentTimeMillis);
    }

    AgentTaskAggregationServiceImpl(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskAggregationCalculator calculator,
            LongSupplier clock) {
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public AgentTaskAggregationDTO aggregate(
            String tenantId, String clientId, String taskId,
            AgentTaskAggregationCommandDTO command) {
        requireScopeAndTask(tenantId, clientId, taskId);
        long expectedVersion = requireCommand(command);
        long calculatedAt = now();

        // Lock the aggregate root, then load members and work items with one UNION
        // statement. Statement-level MVCC makes the child snapshot indivisible: a
        // concurrent child commit is wholly before or wholly after this read.
        AgentTaskMetaEntity task = taskMetaDao.findByTaskIdForUpdate(
                tenantId, clientId, taskId);
        if (task == null) {
            throw notFound();
        }
        validateTask(task, tenantId, clientId, taskId);
        if (task.getTaskVersion() != expectedVersion) {
            throw conflict();
        }

        List<AgentTaskAggregationSnapshotRow> snapshot = taskMetaDao.findAggregationSnapshot(
                tenantId, clientId, taskId);
        if (snapshot == null) {
            throw invalidPersisted("Aggregate snapshot is incomplete");
        }
        List<AgentTaskMemberEntity> members = snapshot.stream()
                .filter(row -> "member".equals(row.getRowType()))
                .map(row -> memberFromSnapshot(row, tenantId, clientId, taskId))
                .toList();
        List<AgentTaskWorkItemEntity> workItems = snapshot.stream()
                .filter(row -> "work_item".equals(row.getRowType()))
                .map(row -> workItemFromSnapshot(row, tenantId, clientId, taskId))
                .toList();
        if (members.size() + workItems.size() != snapshot.size()) {
            throw invalidPersisted("Aggregate snapshot contains an unknown row type");
        }

        AgentTaskStatus currentStatus = persistedTaskStatus(task.getRewardStatus());
        AgentTaskAggregationCalculator.Decision decision = calculator.calculate(
                currentStatus, members, workItems);
        boolean changed = decision.status() != currentStatus;
        long resultVersion = expectedVersion;

        if (changed) {
            Long startedAt = task.getStartedAt();
            if (startedAt == null && startsTaskClock(decision.status())) {
                startedAt = calculatedAt;
            }
            Long completedAt = decision.status() == AgentTaskStatus.COMPLETED
                    ? firstNonNull(task.getCompletedAt(), calculatedAt)
                    : null;
            String failureReason = aggregateFailureReason(decision);
            int updated = taskMetaDao.updateStatusByVersion(
                    tenantId, clientId, taskId, expectedVersion,
                    decision.status().value(), startedAt, completedAt, failureReason);
            if (updated == 0) {
                throw conflict();
            }
            if (updated != 1) {
                throw invalidPersisted("Scoped task CAS updated an unexpected row count");
            }
            resultVersion++;
        }

        return result(taskId, currentStatus, decision, changed, resultVersion, calculatedAt);
    }

    private long requireCommand(AgentTaskAggregationCommandDTO command) {
        if (command == null || command.getExpectedVersion() == null
                || command.getExpectedVersion() < 0
                || command.getExpectedVersion() == Long.MAX_VALUE) {
            throw invalidRequest("A nonnegative incrementable expectedVersion is required");
        }
        return command.getExpectedVersion();
    }

    private void validateTask(
            AgentTaskMetaEntity task, String tenantId, String clientId, String taskId) {
        if (!tenantId.equals(task.getTenantId()) || !clientId.equals(task.getClientId())
                || !taskId.equals(task.getTaskId())) {
            throw invalidPersisted("Persisted task identity does not match its scoped lookup");
        }
        persistedTaskStatus(task.getRewardStatus());
        if (task.getTaskVersion() == null || task.getTaskVersion() < 0
                || task.getTaskVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted task version is invalid");
        }
        if (task.getStartedAt() != null && task.getStartedAt() < 0
                || task.getCompletedAt() != null && task.getCompletedAt() < 0) {
            throw invalidPersisted("Persisted task timestamps are invalid");
        }
    }

    private AgentTaskMemberEntity memberFromSnapshot(
            AgentTaskAggregationSnapshotRow row,
            String tenantId, String clientId, String taskId) {
        AgentTaskMemberEntity member = new AgentTaskMemberEntity()
                .setTaskId(row.getTaskId())
                .setAgentId(row.getEntityId())
                .setMemberRole(row.getRole())
                .setMemberStatus(row.getStatus())
                .setCompletedAt(row.getCompletedAt())
                .setVersion(row.getVersion());
        member.setTenantId(row.getTenantId());
        member.setClientId(row.getClientId());
        validateMember(member, tenantId, clientId, taskId);
        return member;
    }

    private AgentTaskWorkItemEntity workItemFromSnapshot(
            AgentTaskAggregationSnapshotRow row,
            String tenantId, String clientId, String taskId) {
        AgentTaskWorkItemEntity item = new AgentTaskWorkItemEntity()
                .setWorkItemId(row.getEntityId())
                .setTaskId(row.getTaskId())
                .setTitle(row.getTitle())
                .setWorkType(row.getWorkType())
                .setAssigneeAgentId(row.getAssigneeAgentId())
                .setStatus(row.getStatus())
                .setRequiredItem(row.getRequiredItem())
                .setLeaseToken(row.getLeaseToken())
                .setLeaseUntil(row.getLeaseUntil())
                .setAttemptCount(row.getAttemptCount())
                .setMaxAttempts(row.getMaxAttempts())
                .setResultArtifactId(row.getResultArtifactId())
                .setCompletedAt(row.getCompletedAt())
                .setVersion(row.getVersion());
        item.setTenantId(row.getTenantId());
        item.setClientId(row.getClientId());
        validateWorkItem(item, tenantId, clientId, taskId);
        return item;
    }

    private void validateMember(
            AgentTaskMemberEntity member, String tenantId, String clientId, String taskId) {
        if (member == null || !tenantId.equals(member.getTenantId())
                || !clientId.equals(member.getClientId())
                || !taskId.equals(member.getTaskId())
                || StringUtil.isBlank(member.getAgentId())) {
            throw invalidPersisted("Persisted member identity does not match the aggregate scope");
        }
        try {
            AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted member status is non-canonical or unknown");
        }
        if (member.getVersion() == null || member.getVersion() < 0
                || member.getVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted member version is invalid");
        }
    }

    private void validateWorkItem(
            AgentTaskWorkItemEntity item, String tenantId, String clientId, String taskId) {
        if (item == null || !tenantId.equals(item.getTenantId())
                || !clientId.equals(item.getClientId())
                || !taskId.equals(item.getTaskId())
                || StringUtil.isBlank(item.getWorkItemId())
                || StringUtil.isBlank(item.getTitle())
                || StringUtil.isBlank(item.getWorkType())) {
            throw invalidPersisted("Persisted work item identity is incomplete or out of scope");
        }
        AgentTaskWorkItemStatus status;
        try {
            status = AgentTaskWorkItemStatus.fromPersistedValue(item.getStatus());
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted work item status is non-canonical or unknown");
        }
        if (item.getRequiredItem() == null || item.getAttemptCount() == null
                || item.getMaxAttempts() == null || item.getAttemptCount() < 0
                || item.getMaxAttempts() <= 0
                || item.getAttemptCount() > item.getMaxAttempts()
                || item.getVersion() == null || item.getVersion() < 0
                || item.getVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Persisted work item flags, attempts or version are invalid");
        }
        if (status == AgentTaskWorkItemStatus.READY
                && item.getAttemptCount() >= item.getMaxAttempts()) {
            throw invalidPersisted("READY work item has exhausted maxAttempts");
        }
        boolean activeLeaseStatus = status == AgentTaskWorkItemStatus.CLAIMED
                || status == AgentTaskWorkItemStatus.RUNNING;
        if (activeLeaseStatus) {
            if (StringUtil.isBlank(item.getAssigneeAgentId())
                    || !CANONICAL_AGENT_ID.matcher(item.getAssigneeAgentId()).matches()
                    || StringUtil.isBlank(item.getLeaseToken())
                    || item.getLeaseToken().length() > MAX_LEASE_TOKEN_LENGTH
                    || item.getLeaseUntil() == null || item.getLeaseUntil() <= 0) {
                throw invalidPersisted("Persisted active lease is incomplete or non-canonical");
            }
        } else if (item.getLeaseToken() != null || item.getLeaseUntil() != null) {
            throw invalidPersisted("Non-active work item status retains active lease state");
        }
        if (item.getAssigneeAgentId() != null
                && (StringUtil.isBlank(item.getAssigneeAgentId())
                || !CANONICAL_AGENT_ID.matcher(item.getAssigneeAgentId()).matches())) {
            throw invalidPersisted("Persisted work item assignee is blank or non-canonical");
        }
    }

    private AgentTaskStatus persistedTaskStatus(String status) {
        try {
            return AgentTaskStatus.fromPersistedValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidPersisted("Persisted task status is non-canonical or unknown");
        }
    }

    private boolean startsTaskClock(AgentTaskStatus status) {
        return status == AgentTaskStatus.RUNNING || status == AgentTaskStatus.BLOCKED
                || status == AgentTaskStatus.REVIEWING || status == AgentTaskStatus.COMPLETED
                || status == AgentTaskStatus.FAILED;
    }

    private String aggregateFailureReason(AgentTaskAggregationCalculator.Decision decision) {
        if (decision.status() == AgentTaskStatus.FAILED) {
            return "Required work item failed without a recorded replacement: "
                    + decision.relatedWorkItemId();
        }
        if (decision.status() == AgentTaskStatus.BLOCKED) {
            return "Required work item is recoverably blocked: "
                    + decision.relatedWorkItemId();
        }
        return null;
    }

    private AgentTaskAggregationDTO result(
            String taskId,
            AgentTaskStatus currentStatus,
            AgentTaskAggregationCalculator.Decision decision,
            boolean changed,
            long version,
            long calculatedAt) {
        AgentTaskAggregationCalculator.Counts counts = decision.counts();
        AgentTaskAggregationDTO result = new AgentTaskAggregationDTO();
        result.setTaskId(taskId);
        result.setPreviousStatus(currentStatus.value());
        result.setStatus(decision.status().value());
        result.setDecision(decision.reason());
        result.setChanged(changed);
        result.setTaskVersion(version);
        result.setMemberCount(counts.memberCount());
        result.setWorkItemCount(counts.workItemCount());
        result.setRequiredWorkItemCount(counts.requiredWorkItemCount());
        result.setOptionalWorkItemCount(counts.optionalWorkItemCount());
        result.setRequiredSubmittedCount(counts.requiredSubmittedCount());
        result.setRequiredCompletedCount(counts.requiredCompletedCount());
        result.setRequiredBlockedCount(counts.requiredBlockedCount());
        result.setRequiredFailedCount(counts.requiredFailedCount());
        result.setCalculatedAt(calculatedAt);
        return result;
    }

    private Long firstNonNull(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private long now() {
        long now = clock.getAsLong();
        if (now < 0) {
            throw invalidPersisted("Clock returned a negative timestamp");
        }
        return now;
    }

    private void requireScopeAndTask(
            String tenantId, String clientId, String taskId) {
        if (StringUtil.isBlank(tenantId) || StringUtil.isBlank(clientId)
                || StringUtil.isBlank(taskId)) {
            throw invalidRequest("tenantId, clientId and taskId are required");
        }
    }

    private AgentTaskStateException invalidRequest(String message) {
        return new AgentTaskStateException(Reason.INVALID_REQUEST, message);
    }

    private AgentTaskStateException invalidPersisted(String message) {
        return new AgentTaskStateException(Reason.INVALID_PERSISTED_STATE, message);
    }

    private AgentTaskStateException notFound() {
        return new AgentTaskStateException(Reason.NOT_FOUND,
                "Task aggregate was not found in the requested scope");
    }

    private AgentTaskStateException conflict() {
        return new AgentTaskStateException(Reason.VERSION_CONFLICT,
                "Task aggregate version changed before the CAS could commit");
    }
}
