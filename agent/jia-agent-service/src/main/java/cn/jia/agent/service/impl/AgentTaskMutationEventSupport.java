package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;

final class AgentTaskMutationEventSupport {
    private static final String EVENT_ID_PREFIX = "evt_";

    private AgentTaskMutationEventSupport() {
    }

    static AgentTaskEventWriteCommand command(
            String tenantId, String clientId, String taskId,
            String eventType, String actorType, String actorId,
            String aggregateType, String aggregateId,
            TaskEventPayload.Builder payload, long occurredAt, long resultVersion) {
        String seed = tenantId + '\u0000' + clientId + '\u0000' + taskId + '\u0000'
                + eventType + '\u0000' + aggregateType + '\u0000' + aggregateId + '\u0000'
                + resultVersion;
        String eventId = EVENT_ID_PREFIX
                + TaskEventPayload.ContentDigest.fromUtf8(seed).sha256();
        return new AgentTaskEventWriteCommand()
                .setTenantId(tenantId)
                .setClientId(clientId)
                .setTaskId(taskId)
                .setEventId(eventId)
                .setEventType(eventType)
                .setActorType(actorType)
                .setActorId(actorId)
                .setAggregateType(aggregateType)
                .setAggregateId(aggregateId)
                .setEventJson(payload.toJson())
                .setOccurredAt(occurredAt);
    }

    static String taskEvent(String status) {
        return switch (status) {
            case "planning" -> TaskEventType.TEAM_PROPOSED;
            case "assigned" -> TaskEventType.TASK_ASSIGNED;
            case "running" -> TaskEventType.TASK_STARTED;
            case "blocked" -> TaskEventType.TASK_BLOCKED;
            case "reviewing" -> TaskEventType.TASK_REVIEWING;
            case "completed" -> TaskEventType.TASK_COMPLETED;
            case "failed" -> TaskEventType.TASK_FAILED;
            case "cancelled" -> TaskEventType.TASK_CANCELLED;
            case "archived" -> TaskEventType.TASK_ARCHIVED;
            default -> throw new IllegalArgumentException("No canonical task event for status: " + status);
        };
    }

    static String memberEvent(String status) {
        return switch (status) {
            case "invited" -> TaskEventType.MEMBER_INVITED;
            case "accepted" -> TaskEventType.MEMBER_ACCEPTED;
            case "rejected" -> TaskEventType.MEMBER_REJECTED;
            case "blocked" -> TaskEventType.MEMBER_BLOCKED;
            case "working" -> TaskEventType.MEMBER_WORKING;
            case "done" -> TaskEventType.MEMBER_DONE;
            case "failed" -> TaskEventType.MEMBER_FAILED;
            case "left" -> TaskEventType.MEMBER_LEFT;
            default -> throw new IllegalArgumentException("No canonical member event for status: " + status);
        };
    }

    static String workItemEvent(String status) {
        return switch (status) {
            case "ready" -> TaskEventType.WORK_ITEM_READY;
            case "claimed" -> TaskEventType.WORK_ITEM_CLAIMED;
            case "running" -> TaskEventType.WORK_ITEM_STARTED;
            case "submitted" -> TaskEventType.WORK_ITEM_SUBMITTED;
            case "completed" -> TaskEventType.WORK_ITEM_COMPLETED;
            case "blocked" -> TaskEventType.WORK_ITEM_BLOCKED;
            case "failed" -> TaskEventType.WORK_ITEM_FAILED;
            case "cancelled" -> TaskEventType.WORK_ITEM_CANCELLED;
            default -> throw new IllegalArgumentException("No canonical work-item event for status: " + status);
        };
    }
}
