package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMemberWorkItemStateDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskStateDTO;
import cn.jia.agent.entity.AgentTaskStateTransitionDTO;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentTaskStateException.Reason;
import cn.jia.agent.service.AgentTaskStateService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.LongSupplier;

@Named
public class AgentTaskStateServiceImpl implements AgentTaskStateService {
    private static final String AGGREGATE_TASK = "task";
    private static final String AGGREGATE_MEMBER = "member";
    private static final String AGGREGATE_WORK_ITEM = "work_item";

    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskMemberDao memberDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final LongSupplier clock;

    @Inject
    public AgentTaskStateServiceImpl(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao) {
        this(taskMetaDao, memberDao, workItemDao, System::currentTimeMillis);
    }

    AgentTaskStateServiceImpl(
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMemberDao memberDao,
            AgentTaskWorkItemDao workItemDao,
            LongSupplier clock) {
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskStateDTO transitionTask(String tenantId, String clientId, String taskId,
            AgentTaskStateTransitionDTO transition) {
        requireScopeAndId(tenantId, clientId, taskId, "taskId");
        RequiredTransition required = requireTransition(transition);
        AgentTaskMetaEntity current = taskMetaDao.findByTaskId(tenantId, clientId, taskId);
        if (current == null) {
            throw notFound("Task state was not found in the requested scope");
        }
        requireCurrentVersion(current.getTaskVersion(), required.expectedVersion());
        AgentTaskStatus currentStatus = persistedTaskStatus(current.getRewardStatus());
        AgentTaskStatus targetStatus = requestedTaskStatus(required.targetStatus());
        requireTransition(currentStatus.canTransitionTo(targetStatus), currentStatus.value(), targetStatus.value());

        long changedAt = now();
        Long startedAt = current.getStartedAt();
        if (targetStatus == AgentTaskStatus.RUNNING && startedAt == null) {
            startedAt = changedAt;
        }
        Long completedAt = current.getCompletedAt();
        if (targetStatus == AgentTaskStatus.COMPLETED && completedAt == null) {
            completedAt = changedAt;
        }
        String failureReason = taskFailureReason(current, targetStatus, required.failureReason());
        int updated = taskMetaDao.updateStatusByVersion(
                tenantId, clientId, taskId, required.expectedVersion(), targetStatus.value(),
                startedAt, completedAt, failureReason);
        requireSingleCasUpdate(updated);
        return state(AGGREGATE_TASK, taskId, null, null,
                targetStatus.value(), required.expectedVersion() + 1, changedAt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskStateDTO transitionMember(String tenantId, String clientId, String taskId, String agentId,
            AgentTaskStateTransitionDTO transition) {
        requireScopeAndId(tenantId, clientId, taskId, "taskId");
        requireId(agentId, "agentId");
        MemberChange change = prepareMember(
                tenantId, clientId, taskId, agentId, transition, now());
        requireSingleCasUpdate(memberDao.updateByVersion(
                tenantId, clientId, taskId, agentId, change.expectedVersion(), change.update()));
        return change.result();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskStateDTO transitionWorkItem(String tenantId, String clientId, String workItemId,
            AgentTaskStateTransitionDTO transition) {
        requireScopeAndId(tenantId, clientId, workItemId, "workItemId");
        WorkItemChange change = prepareWorkItem(
                tenantId, clientId, null, workItemId, transition, now());
        requireSingleCasUpdate(workItemDao.updateByVersion(
                tenantId, clientId, workItemId, change.expectedVersion(), change.update()));
        return change.result();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskMemberWorkItemStateDTO transitionMemberAndWorkItem(
            String tenantId, String clientId, String taskId, String agentId, String workItemId,
            AgentTaskStateTransitionDTO memberTransition,
            AgentTaskStateTransitionDTO workItemTransition) {
        requireScopeAndId(tenantId, clientId, taskId, "taskId");
        requireId(agentId, "agentId");
        requireId(workItemId, "workItemId");
        long changedAt = now();

        MemberChange memberChange = prepareMember(
                tenantId, clientId, taskId, agentId, memberTransition, changedAt);
        WorkItemChange workItemChange = prepareWorkItem(
                tenantId, clientId, taskId, workItemId, workItemTransition, changedAt);
        String assigneeAgentId = workItemChange.current().getAssigneeAgentId();
        if (!StringUtil.isBlank(assigneeAgentId) && !agentId.equals(assigneeAgentId)) {
            throw invalidRequest("Work item assignee does not match the transitioning member");
        }

        requireSingleCasUpdate(memberDao.updateByVersion(
                tenantId, clientId, taskId, agentId,
                memberChange.expectedVersion(), memberChange.update()));
        requireSingleCasUpdate(workItemDao.updateByVersion(
                tenantId, clientId, workItemId,
                workItemChange.expectedVersion(), workItemChange.update()));

        AgentTaskMemberWorkItemStateDTO result = new AgentTaskMemberWorkItemStateDTO();
        result.setMember(memberChange.result());
        result.setWorkItem(workItemChange.result());
        return result;
    }

    private MemberChange prepareMember(
            String tenantId, String clientId, String taskId, String agentId,
            AgentTaskStateTransitionDTO transition, long changedAt) {
        RequiredTransition required = requireTransition(transition);
        AgentTaskMemberEntity current = memberDao.findByTaskAndAgent(
                tenantId, clientId, taskId, agentId);
        if (current == null) {
            throw notFound("Task member was not found in the requested scope");
        }
        requireCurrentVersion(current.getVersion(), required.expectedVersion());
        AgentTaskMemberStatus currentStatus = persistedMemberStatus(current.getMemberStatus());
        AgentTaskMemberStatus targetStatus = requestedMemberStatus(required.targetStatus());
        requireTransition(currentStatus.canTransitionTo(targetStatus), currentStatus.value(), targetStatus.value());

        AgentTaskMemberDTO update = copyMember(current);
        update.setMemberStatus(targetStatus.value());
        if (targetStatus == AgentTaskMemberStatus.ACCEPTED && update.getAcceptedAt() == null) {
            update.setAcceptedAt(changedAt);
        }
        if (targetStatus == AgentTaskMemberStatus.WORKING && update.getStartedAt() == null) {
            update.setStartedAt(changedAt);
        }
        if (targetStatus.isTerminal() && update.getCompletedAt() == null) {
            update.setCompletedAt(changedAt);
        }
        update.setFailureReason(memberFailureReason(targetStatus, required.failureReason()));
        AgentTaskStateDTO result = state(AGGREGATE_MEMBER, taskId, agentId, null,
                targetStatus.value(), required.expectedVersion() + 1, changedAt);
        return new MemberChange(required.expectedVersion(), update, result);
    }

    private WorkItemChange prepareWorkItem(
            String tenantId, String clientId, String expectedTaskId, String workItemId,
            AgentTaskStateTransitionDTO transition, long changedAt) {
        RequiredTransition required = requireTransition(transition);
        AgentTaskWorkItemEntity current = workItemDao.findByWorkItemId(
                tenantId, clientId, workItemId);
        if (current == null) {
            throw notFound("Task work item was not found in the requested scope");
        }
        if (expectedTaskId != null && !expectedTaskId.equals(current.getTaskId())) {
            throw invalidRequest("Work item does not belong to the requested task");
        }
        requireCurrentVersion(current.getVersion(), required.expectedVersion());
        AgentTaskWorkItemStatus currentStatus = persistedWorkItemStatus(current.getStatus());
        AgentTaskWorkItemStatus targetStatus = requestedWorkItemStatus(required.targetStatus());
        requireTransition(currentStatus.canTransitionTo(targetStatus), currentStatus.value(), targetStatus.value());
        if (currentStatus.requiresClaimProtocol(targetStatus)) {
            throw new AgentTaskStateException(Reason.RESERVED_FOR_CLAIM_PROTOCOL,
                    "Claim-sensitive work item transition requires the B04 claim protocol");
        }

        AgentTaskWorkItemDTO update = copyWorkItem(current);
        update.setStatus(targetStatus.value());
        if (targetStatus == AgentTaskWorkItemStatus.SUBMITTED && update.getSubmittedAt() == null) {
            update.setSubmittedAt(changedAt);
        }
        if (targetStatus == AgentTaskWorkItemStatus.COMPLETED && update.getCompletedAt() == null) {
            update.setCompletedAt(changedAt);
        }
        if (currentStatus == AgentTaskWorkItemStatus.SUBMITTED
                && targetStatus == AgentTaskWorkItemStatus.READY) {
            update.setSubmittedAt(null);
            update.setCompletedAt(null);
            update.setResultArtifactId(null);
        }
        AgentTaskStateDTO result = state(AGGREGATE_WORK_ITEM, current.getTaskId(), null, workItemId,
                targetStatus.value(), required.expectedVersion() + 1, changedAt);
        return new WorkItemChange(current, required.expectedVersion(), update, result);
    }

    private RequiredTransition requireTransition(AgentTaskStateTransitionDTO transition) {
        if (transition == null || StringUtil.isBlank(transition.getTargetStatus())
                || transition.getExpectedVersion() == null
                || transition.getExpectedVersion() < 0
                || transition.getExpectedVersion() == Long.MAX_VALUE) {
            throw invalidRequest("targetStatus and a nonnegative incrementable expectedVersion are required");
        }
        return new RequiredTransition(
                transition.getTargetStatus().trim(),
                transition.getExpectedVersion(),
                trimToNull(transition.getFailureReason()));
    }

    private void requireCurrentVersion(Long currentVersion, long expectedVersion) {
        if (currentVersion == null || currentVersion < 0) {
            throw new AgentTaskStateException(
                    Reason.INVALID_PERSISTED_STATE, "Persisted state has no valid version");
        }
        if (currentVersion != expectedVersion) {
            throw conflict();
        }
    }

    private void requireSingleCasUpdate(int updated) {
        if (updated == 0) {
            throw conflict();
        }
        if (updated != 1) {
            throw new AgentTaskStateException(
                    Reason.INVALID_PERSISTED_STATE, "Scoped CAS updated an unexpected row count");
        }
    }

    private AgentTaskStatus requestedTaskStatus(String status) {
        try {
            return AgentTaskStatus.fromValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidRequest("Unknown target task status");
        }
    }

    private AgentTaskStatus persistedTaskStatus(String status) {
        try {
            return AgentTaskStatus.fromValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidPersistedStatus("task");
        }
    }

    private AgentTaskMemberStatus requestedMemberStatus(String status) {
        try {
            return AgentTaskMemberStatus.fromValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidRequest("Unknown target member status");
        }
    }

    private AgentTaskMemberStatus persistedMemberStatus(String status) {
        try {
            return AgentTaskMemberStatus.fromValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidPersistedStatus("member");
        }
    }

    private AgentTaskWorkItemStatus requestedWorkItemStatus(String status) {
        try {
            return AgentTaskWorkItemStatus.fromValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidRequest("Unknown target work item status");
        }
    }

    private AgentTaskWorkItemStatus persistedWorkItemStatus(String status) {
        try {
            return AgentTaskWorkItemStatus.fromValue(status);
        } catch (IllegalArgumentException e) {
            throw invalidPersistedStatus("work item");
        }
    }

    private String taskFailureReason(
            AgentTaskMetaEntity current, AgentTaskStatus targetStatus, String requestedReason) {
        return switch (targetStatus) {
            case BLOCKED, FAILED -> requireFailureReason(requestedReason, targetStatus.value());
            case CANCELLED -> requestedReason;
            case ARCHIVED -> current.getFailureReason();
            default -> null;
        };
    }

    private String memberFailureReason(
            AgentTaskMemberStatus targetStatus, String requestedReason) {
        return switch (targetStatus) {
            case BLOCKED, FAILED -> requireFailureReason(requestedReason, targetStatus.value());
            default -> null;
        };
    }

    private String requireFailureReason(String failureReason, String targetStatus) {
        if (StringUtil.isBlank(failureReason)) {
            throw invalidRequest("failureReason is required when transitioning to " + targetStatus);
        }
        return failureReason;
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
        update.setVersion(current.getVersion());
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
        update.setVersion(current.getVersion());
        return update;
    }

    private AgentTaskStateDTO state(
            String aggregateType, String taskId, String agentId, String workItemId,
            String status, long version, long changedAt) {
        AgentTaskStateDTO result = new AgentTaskStateDTO();
        result.setAggregateType(aggregateType);
        result.setTaskId(taskId);
        result.setAgentId(agentId);
        result.setWorkItemId(workItemId);
        result.setStatus(status);
        result.setVersion(version);
        result.setChangedAt(changedAt);
        return result;
    }

    private void requireScopeAndId(String tenantId, String clientId, String id, String idName) {
        if (StringUtil.isBlank(tenantId) || StringUtil.isBlank(clientId)) {
            throw invalidRequest("tenantId and clientId are required");
        }
        requireId(id, idName);
    }

    private void requireId(String value, String name) {
        if (StringUtil.isBlank(value)) {
            throw invalidRequest(name + " is required");
        }
    }

    private void requireTransition(boolean allowed, String currentStatus, String targetStatus) {
        if (!allowed) {
            throw new AgentTaskStateException(Reason.INVALID_TRANSITION,
                    "Transition from " + currentStatus + " to " + targetStatus + " is not allowed");
        }
    }

    private long now() {
        long value = clock.getAsLong();
        if (value < 0) {
            throw new AgentTaskStateException(
                    Reason.INVALID_PERSISTED_STATE, "State transition clock returned a negative timestamp");
        }
        return value;
    }

    private String trimToNull(String value) {
        return StringUtil.isBlank(value) ? null : value.trim();
    }

    private AgentTaskStateException invalidRequest(String message) {
        return new AgentTaskStateException(Reason.INVALID_REQUEST, message);
    }

    private AgentTaskStateException invalidPersistedStatus(String type) {
        return new AgentTaskStateException(
                Reason.INVALID_PERSISTED_STATE, "Persisted " + type + " status is unknown");
    }

    private AgentTaskStateException notFound(String message) {
        return new AgentTaskStateException(Reason.NOT_FOUND, message);
    }

    private AgentTaskStateException conflict() {
        return new AgentTaskStateException(
                Reason.VERSION_CONFLICT, "State changed concurrently; refresh and retry");
    }

    private record RequiredTransition(String targetStatus, long expectedVersion, String failureReason) {
    }

    private record MemberChange(
            long expectedVersion, AgentTaskMemberDTO update, AgentTaskStateDTO result) {
    }

    private record WorkItemChange(
            AgentTaskWorkItemEntity current, long expectedVersion,
            AgentTaskWorkItemDTO update, AgentTaskStateDTO result) {
    }
}
