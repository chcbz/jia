package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentWorkItemReassignmentEventValidatorTest {
    private static final String WORK = "work-1";
    private static final String COORD = "agt_cccccccccccccccccccccccccccccccc";
    private static final String PREVIOUS = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TARGET = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void exactExpiredRunningToClaimedEventIsAccepted() throws Exception {
        Map<String, Object> payload = payload("running", PREVIOUS, TARGET, 4, 5);
        assertDoesNotThrow(() -> AgentTaskWorkspaceEventValidator.validate(
                TaskEventType.WORK_ITEM_REASSIGNED, TaskEventType.ActorType.AGENT, COORD,
                TaskEventType.Aggregate.WORK_ITEM, WORK, payload, "task-1"));
    }

    @Test
    void targetReuseVersionSkipAndUnexpectedPayloadFailClosedWithoutChangingF06Rules() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AgentTaskWorkspaceEventValidator.validate(
                TaskEventType.WORK_ITEM_REASSIGNED, TaskEventType.ActorType.AGENT, COORD,
                TaskEventType.Aggregate.WORK_ITEM, WORK,
                payload("claimed", PREVIOUS, PREVIOUS, 4, 5), "task-1"));
        assertThrows(IllegalArgumentException.class, () -> AgentTaskWorkspaceEventValidator.validate(
                TaskEventType.WORK_ITEM_REASSIGNED, TaskEventType.ActorType.AGENT, COORD,
                TaskEventType.Aggregate.WORK_ITEM, WORK,
                payload("claimed", PREVIOUS, TARGET, 4, 6), "task-1"));
        Map<String, Object> extra = payload("claimed", PREVIOUS, TARGET, 4, 5);
        extra.put(TaskEventPayload.Key.REASON_CODE, "lease_expired");
        assertThrows(IllegalArgumentException.class, () -> AgentTaskWorkspaceEventValidator.validate(
                TaskEventType.WORK_ITEM_REASSIGNED, TaskEventType.ActorType.AGENT, COORD,
                TaskEventType.Aggregate.WORK_ITEM, WORK, extra, "task-1"));

        Map<String, Object> f06 = Map.of(
                TaskEventPayload.Key.ARTIFACT_ID, "artifact-1",
                TaskEventPayload.Key.PRODUCER_AGENT_ID, PREVIOUS,
                TaskEventPayload.Key.ARTIFACT_TYPE, "result",
                TaskEventPayload.Key.ARTIFACT_VERSION, 1L,
                TaskEventPayload.Key.VISIBILITY, "task_members",
                TaskEventPayload.Key.DECISION_ID, "decision-1",
                TaskEventPayload.Key.FROM_STATUS, "draft",
                TaskEventPayload.Key.TO_STATUS, "accepted",
                TaskEventPayload.Key.EXPECTED_VERSION, 0L,
                TaskEventPayload.Key.RESULT_VERSION, 1L);
        String aggregate = AgentTaskWorkspaceEventValidator.artifactOutcomeAggregateId(
                "task-1", "artifact-1", 1);
        assertDoesNotThrow(() -> AgentTaskWorkspaceEventValidator.validate(
                TaskEventType.ARTIFACT_ACCEPTED, TaskEventType.ActorType.AGENT, COORD,
                TaskEventType.Aggregate.ARTIFACT, aggregate, f06, "task-1"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(
            String from, String previous, String target, long expected, long result)
            throws Exception {
        String json = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, WORK)
                .put(TaskEventPayload.Key.COORDINATOR_AGENT_ID, COORD)
                .put(TaskEventPayload.Key.PREVIOUS_AGENT_ID, previous)
                .put(TaskEventPayload.Key.TARGET_AGENT_ID, target)
                .put(TaskEventPayload.Key.FROM_STATUS, from)
                .put(TaskEventPayload.Key.TO_STATUS, "claimed")
                .put(TaskEventPayload.Key.EXPECTED_VERSION, expected)
                .put(TaskEventPayload.Key.RESULT_VERSION, result)
                .put(TaskEventPayload.Key.ATTEMPT_COUNT, 1)
                .put(TaskEventPayload.Key.MAX_ATTEMPTS, 3)
                .put(TaskEventPayload.Key.PREVIOUS_LEASE_EXPIRES_AT, 100)
                .put(TaskEventPayload.Key.LEASE_EXPIRES_AT, 200)
                .put(TaskEventPayload.Key.REASSIGNMENT_ID, "rsn-1")
                .put(TaskEventPayload.Key.SOURCE_COMMAND_ID, "cmd-old")
                .put(TaskEventPayload.Key.COMMAND_ID, "cmd-new")
                .put(TaskEventPayload.Key.REQUEST_DIGEST, "1".repeat(64))
                .put(TaskEventPayload.Key.LEASE_FENCE_SHA256, "2".repeat(64))
                .toJson();
        return JsonUtil.getMapper().readValue(json, Map.class);
    }
}
