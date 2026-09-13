package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/** Fail-closed semantic validation shared by C04 snapshots and C05 streams. */
public final class AgentTaskWorkspaceEventValidator {
    private static final Set<String> TASK_TRANSITIONS = Set.of(
            TaskEventType.TASK_ASSIGNED, TaskEventType.TASK_STARTED,
            TaskEventType.TASK_BLOCKED, TaskEventType.TASK_ARCHIVED,
            TaskEventType.TEAM_PROPOSED, TaskEventType.TASK_REVIEWING,
            TaskEventType.TASK_COMPLETED, TaskEventType.TASK_FAILED,
            TaskEventType.TASK_CANCELLED);
    private static final Set<String> MEMBER_EVENTS = Set.of(
            TaskEventType.MEMBER_INVITED, TaskEventType.MEMBER_ACCEPTED,
            TaskEventType.MEMBER_REJECTED, TaskEventType.MEMBER_BLOCKED,
            TaskEventType.MEMBER_WORKING, TaskEventType.MEMBER_DONE,
            TaskEventType.MEMBER_FAILED, TaskEventType.MEMBER_LEFT);
    private static final Set<String> WORK_ITEM_EVENTS = Set.of(
            TaskEventType.WORK_ITEM_CREATED, TaskEventType.WORK_ITEM_READY,
            TaskEventType.WORK_ITEM_CLAIMED, TaskEventType.WORK_ITEM_STARTED,
            TaskEventType.WORK_ITEM_SUBMITTED, TaskEventType.WORK_ITEM_COMPLETED,
            TaskEventType.WORK_ITEM_REQUEUED, TaskEventType.WORK_ITEM_BLOCKED,
            TaskEventType.WORK_ITEM_FAILED, TaskEventType.WORK_ITEM_CANCELLED,
            TaskEventType.WORK_ITEM_LEASE_RENEWED,
            TaskEventType.WORK_ITEM_LEASE_RELEASED);
    private static final Set<String> REQUEST_EVENTS = Set.of(
            TaskEventType.HELP_REQUESTED, TaskEventType.REVIEW_REQUESTED,
            TaskEventType.REQUEST_CREATED, TaskEventType.REQUEST_ACKNOWLEDGED,
            TaskEventType.REQUEST_RESOLVED, TaskEventType.REQUEST_REJECTED,
            TaskEventType.REQUEST_CANCELLED);
    private static final Set<String> AGENT_WORK_ITEM_EVENTS = Set.of(
            TaskEventType.WORK_ITEM_CLAIMED, TaskEventType.WORK_ITEM_LEASE_RENEWED,
            TaskEventType.WORK_ITEM_LEASE_RELEASED);
    private static final Set<String> SYSTEM_WORK_ITEM_EVENTS = Set.of(
            TaskEventType.WORK_ITEM_CREATED, TaskEventType.WORK_ITEM_READY,
            TaskEventType.WORK_ITEM_REQUEUED, TaskEventType.WORK_ITEM_BLOCKED,
            TaskEventType.WORK_ITEM_COMPLETED, TaskEventType.WORK_ITEM_FAILED);

    private AgentTaskWorkspaceEventValidator() {
    }

    public static ArtifactClaim validate(
            String eventType,
            String actorType,
            String actorId,
            String aggregateType,
            String aggregateId,
            Map<String, Object> payload,
            String taskId) {
        EventView row = new EventView(
                eventType, actorType, actorId, aggregateType, aggregateId);
        if (TaskEventType.TASK_CREATED.equals(eventType)) {
            requireAggregate(row, TaskEventType.Aggregate.TASK, taskId);
            requireSystemActor(row, false);
            requireEqualId(payload, TaskEventPayload.Key.TASK_ID, taskId);
            requireString(payload, TaskEventPayload.Key.TASK_TYPE);
            requireString(payload, TaskEventPayload.Key.STATUS);
            requireLong(payload, TaskEventPayload.Key.RESULT_VERSION);
            requirePositiveLong(payload, TaskEventPayload.Key.CREATED_AT);
            return null;
        }
        if (TASK_TRANSITIONS.contains(eventType)) {
            requireAggregate(row, TaskEventType.Aggregate.TASK, taskId);
            requireSystemActor(row, false);
            if (payload.containsKey(TaskEventPayload.Key.TASK_ID)) {
                requireEqualId(payload, TaskEventPayload.Key.TASK_ID, taskId);
            }
            requireString(payload, TaskEventPayload.Key.FROM_STATUS);
            requireEventStatus(payload, eventType);
            requireLong(payload, TaskEventPayload.Key.RESULT_VERSION);
            if (!TaskEventType.TASK_ASSIGNED.equals(eventType)) {
                requireLong(payload, TaskEventPayload.Key.EXPECTED_VERSION);
            }
            return null;
        }
        if (TaskEventType.PROGRESS_REPORTED.equals(eventType)) {
            requireAggregate(row, TaskEventType.Aggregate.TASK, taskId);
            requireSystemActor(row, false);
            requireId(payload, TaskEventPayload.Key.NOTE_ID);
            requireString(payload, TaskEventPayload.Key.NOTE_TYPE);
            requireDigest(payload);
            return null;
        }
        if (MEMBER_EVENTS.contains(eventType)) {
            requireAggregateType(row, TaskEventType.Aggregate.MEMBER);
            requireSystemActor(row, false);
            requireEqualId(payload, TaskEventPayload.Key.AGENT_ID, row.getAggregateId());
            if (payload.containsKey(TaskEventPayload.Key.MEMBER_ID)) {
                requireEqualId(payload, TaskEventPayload.Key.MEMBER_ID, row.getAggregateId());
            }
            requireString(payload, TaskEventPayload.Key.ROLE);
            requireEventStatus(payload, eventType);
            requireLong(payload, TaskEventPayload.Key.RESULT_VERSION);
            return null;
        }
        if (WORK_ITEM_EVENTS.contains(eventType)) {
            requireAggregateType(row, TaskEventType.Aggregate.WORK_ITEM);
            requireEqualId(payload, TaskEventPayload.Key.WORK_ITEM_ID, row.getAggregateId());
            requireWorkItemActor(row, eventType);
            if (!TaskEventType.WORK_ITEM_CREATED.equals(eventType)) {
                requireEventStatus(payload, eventType);
                requireLong(payload, TaskEventPayload.Key.RESULT_VERSION);
            }
            return null;
        }
        if (REQUEST_EVENTS.contains(eventType)) {
            requireAggregateType(row, TaskEventType.Aggregate.REQUEST);
            requireAgentActor(row);
            requireEqualId(payload, TaskEventPayload.Key.REQUEST_ID, row.getAggregateId());
            String requestType = requireString(payload, TaskEventPayload.Key.REQUEST_TYPE);
            if (!Set.of("help", "clarification", "dependency", "review", "resource",
                    "reassignment", "approval").contains(requestType)) {
                throw invalid();
            }
            String targetType = requireString(payload, TaskEventPayload.Key.TARGET_TYPE);
            String targetId = requireId(payload, TaskEventPayload.Key.TARGET_ID);
            if (!"agent".equals(targetType) && !"role".equals(targetType)
                    || "role".equals(targetType) && !Set.of(
                            "coordinator", "worker", "reviewer", "observer").contains(targetId)) {
                throw invalid();
            }
            requireEventStatus(payload, eventType);
            requireLong(payload, TaskEventPayload.Key.RESULT_VERSION);
            if (TaskEventType.HELP_REQUESTED.equals(eventType) && !"help".equals(requestType)
                    || TaskEventType.REVIEW_REQUESTED.equals(eventType) && !"review".equals(requestType)
                    || TaskEventType.REQUEST_CREATED.equals(eventType)
                    && ("help".equals(requestType) || "review".equals(requestType))) {
                throw invalid();
            }
            return null;
        }
        if (TaskEventType.ARTIFACT_PUBLISHED.equals(eventType)) {
            requireAggregateType(row, TaskEventType.Aggregate.ARTIFACT);
            requireAgentActor(row);
            String artifactId = requireEqualId(
                    payload, TaskEventPayload.Key.ARTIFACT_ID, row.getAggregateId());
            String artifactType = requireString(payload, TaskEventPayload.Key.ARTIFACT_TYPE);
            long artifactVersion = requirePositiveLong(
                    payload, TaskEventPayload.Key.ARTIFACT_VERSION);
            if (artifactVersion > Integer.MAX_VALUE) {
                throw invalid();
            }
            String visibility = requireString(payload, TaskEventPayload.Key.VISIBILITY);
            if (!Set.of("task_members", "reviewer", "private").contains(visibility)) {
                throw invalid();
            }
            String workItemId = payload.containsKey(TaskEventPayload.Key.WORK_ITEM_ID)
                    ? requireId(payload, TaskEventPayload.Key.WORK_ITEM_ID) : null;
            requireDigest(payload);
            return new ArtifactClaim(artifactId, (int) artifactVersion, artifactType,
                    visibility, workItemId, row.getActorId());
        }
        if (TaskEventType.ARTIFACT_ACCEPTED.equals(eventType)
                || TaskEventType.ARTIFACT_SUPERSEDED.equals(eventType)) {
            requireAggregateType(row, TaskEventType.Aggregate.ARTIFACT);
            requireAgentActor(row);
            String artifactId = requireId(payload, TaskEventPayload.Key.ARTIFACT_ID);
            String producerAgentId = requireId(
                    payload, TaskEventPayload.Key.PRODUCER_AGENT_ID);
            String artifactType = requireString(payload, TaskEventPayload.Key.ARTIFACT_TYPE);
            long artifactVersion = requirePositiveLong(
                    payload, TaskEventPayload.Key.ARTIFACT_VERSION);
            if (artifactVersion > Integer.MAX_VALUE) {
                throw invalid();
            }
            requireAggregate(row, TaskEventType.Aggregate.ARTIFACT,
                    artifactOutcomeAggregateId(taskId, artifactId, (int) artifactVersion));
            String visibility = requireString(payload, TaskEventPayload.Key.VISIBILITY);
            if (!Set.of("task_members", "reviewer", "private").contains(visibility)) {
                throw invalid();
            }
            String workItemId = payload.containsKey(TaskEventPayload.Key.WORK_ITEM_ID)
                    ? requireId(payload, TaskEventPayload.Key.WORK_ITEM_ID) : null;
            requireId(payload, TaskEventPayload.Key.DECISION_ID);
            String from = requireString(payload, TaskEventPayload.Key.FROM_STATUS);
            String to = requireString(payload, TaskEventPayload.Key.TO_STATUS);
            long expected = requireLong(payload, TaskEventPayload.Key.EXPECTED_VERSION);
            long result = requirePositiveLong(payload, TaskEventPayload.Key.RESULT_VERSION);
            if (result != expected + 1 || !("draft".equals(from) && expected == 0
                    || "accepted".equals(from) && expected > 0)) {
                throw invalid();
            }
            if (TaskEventType.ARTIFACT_ACCEPTED.equals(eventType)) {
                if (!"draft".equals(from) || expected != 0 || result != 1
                        || !"accepted".equals(to)
                        || payload.containsKey(TaskEventPayload.Key.SUPERSEDED_BY_ARTIFACT_ID)
                        || payload.containsKey(
                        TaskEventPayload.Key.SUPERSEDED_BY_ARTIFACT_VERSION)) {
                    throw invalid();
                }
            } else {
                if (!"superseded".equals(to)) {
                    throw invalid();
                }
                String replacementId = requireId(
                        payload, TaskEventPayload.Key.SUPERSEDED_BY_ARTIFACT_ID);
                long replacementVersion = requirePositiveLong(
                        payload, TaskEventPayload.Key.SUPERSEDED_BY_ARTIFACT_VERSION);
                if (replacementVersion > Integer.MAX_VALUE
                        || artifactId.equals(replacementId)
                        && artifactVersion == replacementVersion) {
                    throw invalid();
                }
            }
            return new ArtifactClaim(artifactId, (int) artifactVersion, artifactType,
                    visibility, workItemId, producerAgentId);
        }
        if (TaskEventType.THREAD_CREATED.equals(eventType)) {
            requireAggregateType(row, TaskEventType.Aggregate.THREAD);
            requireAgentActor(row);
            requireEqualId(payload, TaskEventPayload.Key.THREAD_ID, row.getAggregateId());
            requireString(payload, TaskEventPayload.Key.THREAD_TYPE);
            requireId(payload, TaskEventPayload.Key.CONVERSATION_ID);
            requirePositiveLong(payload, TaskEventPayload.Key.CREATED_AT);
            return null;
        }
        if (TaskEventType.MESSAGE_POSTED.equals(eventType)) {
            requireAggregateType(row, TaskEventType.Aggregate.MESSAGE);
            requireAgentActor(row);
            requireEqualId(payload, TaskEventPayload.Key.MESSAGE_ID, row.getAggregateId());
            requireString(payload, TaskEventPayload.Key.MESSAGE_TYPE);
            requireId(payload, TaskEventPayload.Key.CONVERSATION_ID);
            requireEqualId(payload, TaskEventPayload.Key.SENDER_AGENT_ID, row.getActorId());
            requirePositiveLong(payload, TaskEventPayload.Key.CREATED_AT);
            requireDigest(payload);
            return null;
        }
        if (TaskEventType.COMMAND_DELIVERY_FAILED.equals(eventType)) {
            requireAggregate(row, TaskEventType.Aggregate.TASK, taskId);
            if (TaskEventType.ActorType.SYSTEM.equals(row.getActorType())) {
                requireSystemActor(row, false);
            } else if (TaskEventType.ActorType.ROLE.equals(row.getActorType())) {
                requireActorId(row);
            } else {
                throw invalid();
            }
            requireString(payload, TaskEventPayload.Key.REASON_CODE);
            return null;
        }
        if (TaskEventType.HISTORICAL_BASELINE_IMPORTED.equals(eventType)) {
            requireAggregate(row, TaskEventType.Aggregate.TASK, taskId);
            requireSystemActor(row, true);
            if (!"c01h-b09".equals(row.getActorId())
                    || !"b09".equals(requireString(payload, TaskEventPayload.Key.SOURCE))
                    || !"c01h_b09_v1".equals(
                            requireString(payload, TaskEventPayload.Key.DECISION_CODE))) {
                throw invalid();
            }
            requireDigest(payload);
            requireLong(payload, TaskEventPayload.Key.MEMBER_COUNT);
            requireLong(payload, TaskEventPayload.Key.WORK_ITEM_COUNT);
            return null;
        }
        throw invalid();
    }

    public static String artifactOutcomeAggregateId(
            String taskId, String artifactId, int artifactVersion) {
        requireIdValue(taskId);
        requireIdValue(artifactId);
        if (artifactVersion < 1) {
            throw invalid();
        }
        String seed = "f06-artifact-version" + '\u0000' + taskId + '\u0000'
                + artifactId + '\u0000' + artifactVersion;
        return "av_" + TaskEventPayload.ContentDigest.fromUtf8(seed).sha256();
    }

    private static void requireEventStatus(Map<String, Object> payload, String eventType) {
        String expected = switch (eventType) {
            case TaskEventType.TASK_ASSIGNED -> "assigned";
            case TaskEventType.TASK_STARTED -> "running";
            case TaskEventType.TASK_BLOCKED -> "blocked";
            case TaskEventType.TASK_ARCHIVED -> "archived";
            case TaskEventType.TEAM_PROPOSED -> "planning";
            case TaskEventType.TASK_REVIEWING -> "reviewing";
            case TaskEventType.TASK_COMPLETED -> "completed";
            case TaskEventType.TASK_FAILED -> "failed";
            case TaskEventType.TASK_CANCELLED -> "cancelled";
            case TaskEventType.MEMBER_INVITED -> "invited";
            case TaskEventType.MEMBER_ACCEPTED -> "accepted";
            case TaskEventType.MEMBER_REJECTED -> "rejected";
            case TaskEventType.MEMBER_BLOCKED -> "blocked";
            case TaskEventType.MEMBER_WORKING -> "working";
            case TaskEventType.MEMBER_DONE -> "done";
            case TaskEventType.MEMBER_FAILED -> "failed";
            case TaskEventType.MEMBER_LEFT -> "left";
            case TaskEventType.WORK_ITEM_READY, TaskEventType.WORK_ITEM_REQUEUED -> "ready";
            case TaskEventType.WORK_ITEM_CLAIMED -> "claimed";
            case TaskEventType.WORK_ITEM_STARTED -> "running";
            case TaskEventType.WORK_ITEM_SUBMITTED -> "submitted";
            case TaskEventType.WORK_ITEM_COMPLETED -> "completed";
            case TaskEventType.WORK_ITEM_BLOCKED -> "blocked";
            case TaskEventType.WORK_ITEM_FAILED -> "failed";
            case TaskEventType.WORK_ITEM_CANCELLED -> "cancelled";
            case TaskEventType.WORK_ITEM_LEASE_RENEWED -> null;
            case TaskEventType.WORK_ITEM_LEASE_RELEASED -> null;
            case TaskEventType.HELP_REQUESTED, TaskEventType.REVIEW_REQUESTED,
                    TaskEventType.REQUEST_CREATED -> "open";
            case TaskEventType.REQUEST_ACKNOWLEDGED -> "acknowledged";
            case TaskEventType.REQUEST_RESOLVED -> "resolved";
            case TaskEventType.REQUEST_REJECTED -> "rejected";
            case TaskEventType.REQUEST_CANCELLED -> "cancelled";
            default -> throw invalid();
        };
        String actual = requireString(payload, TaskEventPayload.Key.TO_STATUS);
        if (TaskEventType.WORK_ITEM_LEASE_RENEWED.equals(eventType)) {
            if (!"claimed".equals(actual) && !"running".equals(actual)) {
                throw invalid();
            }
        } else if (TaskEventType.WORK_ITEM_LEASE_RELEASED.equals(eventType)) {
            if (!"ready".equals(actual) && !"failed".equals(actual)) {
                throw invalid();
            }
        } else if (!expected.equals(actual)) {
            throw invalid();
        }
    }

    private static void requireWorkItemActor(EventView row, String eventType) {
        if (AGENT_WORK_ITEM_EVENTS.contains(eventType)) {
            requireAgentActor(row);
            return;
        }
        if (SYSTEM_WORK_ITEM_EVENTS.contains(eventType)) {
            requireSystemActor(row, false);
            return;
        }
        if (TaskEventType.WORK_ITEM_STARTED.equals(eventType)
                || TaskEventType.WORK_ITEM_SUBMITTED.equals(eventType)
                || TaskEventType.WORK_ITEM_CANCELLED.equals(eventType)) {
            if (TaskEventType.ActorType.AGENT.equals(row.getActorType())) {
                requireActorId(row);
            } else {
                requireSystemActor(row, false);
            }
            return;
        }
        throw invalid();
    }

    private static void requireAggregate(EventView row, String type, String id) {
        requireAggregateType(row, type);
        if (!id.equals(row.getAggregateId())) {
            throw invalid();
        }
    }

    private static void requireAggregateType(EventView row, String type) {
        if (!type.equals(row.getAggregateType())) {
            throw invalid();
        }
    }

    private static void requireAgentActor(EventView row) {
        if (!TaskEventType.ActorType.AGENT.equals(row.getActorType())) {
            throw invalid();
        }
        requireActorId(row);
    }

    private static void requireSystemActor(EventView row, boolean allowId) {
        if (!TaskEventType.ActorType.SYSTEM.equals(row.getActorType())
                || !allowId && row.getActorId() != null) {
            throw invalid();
        }
        if (allowId) {
            requireActorId(row);
        }
    }

    private static void requireActorId(EventView row) {
        requireIdValue(row.getActorId());
    }

    private static String requireEqualId(
            Map<String, Object> payload, String key, String expected) {
        String actual = requireId(payload, key);
        if (!expected.equals(actual)) {
            throw invalid();
        }
        return actual;
    }

    private static String requireId(Map<String, Object> payload, String key) {
        String value = requireString(payload, key);
        requireIdValue(value);
        return value;
    }

    private static void requireIdValue(String value) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.codePointCount(0, value.length()) > 100
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static String requireString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String string) || string.isEmpty()) {
            throw invalid();
        }
        return string;
    }

    private static long requireLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger)) {
            throw invalid();
        }
        try {
            long number = value instanceof BigInteger integer
                    ? integer.longValueExact() : ((Number) value).longValue();
            if (number < 0) {
                throw invalid();
            }
            return number;
        } catch (ArithmeticException exception) {
            throw invalid();
        }
    }

    private static long requirePositiveLong(Map<String, Object> payload, String key) {
        long value = requireLong(payload, key);
        if (value <= 0) {
            throw invalid();
        }
        return value;
    }

    private static void requireDigest(Map<String, Object> payload) {
        String hash = requireString(payload, TaskEventPayload.Key.CONTENT_SHA256);
        if (hash.length() != 64 || !hash.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        if (payload.containsKey(TaskEventPayload.Key.CONTENT_BYTE_LENGTH)) {
            requireLong(payload, TaskEventPayload.Key.CONTENT_BYTE_LENGTH);
        }
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Persisted task event violates the canonical contract");
    }

    public record ArtifactClaim(String artifactId, int artifactVersion, String artifactType,
            String visibility, String workItemId, String producerAgentId) {
    }

    private record EventView(
            String eventType,
            String actorType,
            String actorId,
            String aggregateType,
            String aggregateId) {
        private String getEventType() {
            return eventType;
        }

        private String getActorType() {
            return actorType;
        }

        private String getActorId() {
            return actorId;
        }

        private String getAggregateType() {
            return aggregateType;
        }

        private String getAggregateId() {
            return aggregateId;
        }
    }
}
