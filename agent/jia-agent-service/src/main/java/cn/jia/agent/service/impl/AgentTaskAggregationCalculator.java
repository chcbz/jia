package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import cn.jia.core.util.StringUtil;

import java.util.List;

/** Pure deterministic B05 aggregate calculation over a validated locked snapshot. */
final class AgentTaskAggregationCalculator {

    Decision calculate(
            AgentTaskStatus currentStatus,
            List<AgentTaskMemberEntity> members,
            List<AgentTaskWorkItemEntity> workItems) {
        List<AgentTaskWorkItemEntity> required = workItems.stream()
                .filter(item -> Boolean.TRUE.equals(item.getRequiredItem()))
                .toList();

        int requiredSubmitted = 0;
        int requiredCompleted = 0;
        int requiredBlocked = 0;
        int requiredFailed = 0;
        String blockedWorkItemId = null;
        String failedWorkItemId = null;
        boolean allAtLeastSubmitted = !required.isEmpty();
        boolean allAcceptedCompleted = !required.isEmpty();

        for (AgentTaskWorkItemEntity item : required) {
            AgentTaskWorkItemStatus status = AgentTaskWorkItemStatus.fromPersistedValue(item.getStatus());
            boolean atLeastSubmitted = status == AgentTaskWorkItemStatus.SUBMITTED
                    || status == AgentTaskWorkItemStatus.COMPLETED;
            boolean acceptedCompleted = status == AgentTaskWorkItemStatus.COMPLETED
                    && !StringUtil.isBlank(item.getResultArtifactId())
                    && item.getCompletedAt() != null
                    && item.getCompletedAt() > 0;
            if (atLeastSubmitted) {
                requiredSubmitted++;
            }
            if (acceptedCompleted) {
                requiredCompleted++;
            }
            boolean attemptsExhausted = item.getAttemptCount() >= item.getMaxAttempts();
            if (status == AgentTaskWorkItemStatus.BLOCKED && !attemptsExhausted) {
                requiredBlocked++;
                if (blockedWorkItemId == null) {
                    blockedWorkItemId = item.getWorkItemId();
                }
            }
            if ((status == AgentTaskWorkItemStatus.FAILED
                    || status == AgentTaskWorkItemStatus.BLOCKED) && attemptsExhausted) {
                requiredFailed++;
                if (failedWorkItemId == null) {
                    failedWorkItemId = item.getWorkItemId();
                }
            }
            allAtLeastSubmitted &= atLeastSubmitted;
            allAcceptedCompleted &= acceptedCompleted;
        }

        Counts counts = new Counts(
                members.size(), workItems.size(), required.size(),
                workItems.size() - required.size(), requiredSubmitted,
                requiredCompleted, requiredBlocked, requiredFailed);

        if (currentStatus.isOperationalTerminal()) {
            return new Decision(currentStatus, "terminal_preserved", counts, null);
        }
        if (required.isEmpty()) {
            return new Decision(currentStatus, "no_required_work_items", counts, null);
        }
        if (requiredFailed > 0) {
            return new Decision(AgentTaskStatus.FAILED, "required_failed", counts, failedWorkItemId);
        }
        if (allAcceptedCompleted) {
            return new Decision(AgentTaskStatus.COMPLETED,
                    "all_required_completed_and_accepted", counts, null);
        }
        if (allAtLeastSubmitted) {
            return new Decision(AgentTaskStatus.REVIEWING,
                    "all_required_submitted", counts, null);
        }
        if (requiredBlocked > 0) {
            return new Decision(AgentTaskStatus.BLOCKED,
                    "recoverable_required_blocked", counts, blockedWorkItemId);
        }

        boolean activeWork = workItems.stream()
                .map(AgentTaskWorkItemEntity::getStatus)
                .map(AgentTaskWorkItemStatus::fromPersistedValue)
                .anyMatch(status -> status == AgentTaskWorkItemStatus.CLAIMED
                        || status == AgentTaskWorkItemStatus.RUNNING
                        || status == AgentTaskWorkItemStatus.SUBMITTED
                        || status == AgentTaskWorkItemStatus.COMPLETED);
        if (activeWork) {
            return new Decision(AgentTaskStatus.RUNNING, "active_work", counts, null);
        }
        if (currentStatus == AgentTaskStatus.REVIEWING
                || currentStatus == AgentTaskStatus.BLOCKED
                || currentStatus == AgentTaskStatus.RUNNING) {
            return new Decision(AgentTaskStatus.RUNNING,
                    "rework_or_running_preserved", counts, null);
        }
        if (currentStatus == AgentTaskStatus.ASSIGNED) {
            return new Decision(currentStatus, "assigned_preserved", counts, null);
        }
        if (!members.isEmpty()) {
            return new Decision(AgentTaskStatus.ASSIGNED, "members_assigned", counts, null);
        }
        return new Decision(currentStatus, "no_aggregate_change", counts, null);
    }

    record Decision(
            AgentTaskStatus status,
            String reason,
            Counts counts,
            String relatedWorkItemId) {
    }

    record Counts(
            int memberCount,
            int workItemCount,
            int requiredWorkItemCount,
            int optionalWorkItemCount,
            int requiredSubmittedCount,
            int requiredCompletedCount,
            int requiredBlockedCount,
            int requiredFailedCount) {
    }
}
