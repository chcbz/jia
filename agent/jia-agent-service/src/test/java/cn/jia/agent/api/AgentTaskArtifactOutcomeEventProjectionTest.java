package cn.jia.agent.api;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.service.AgentTaskEventAccessService.AuthorizedSubject;
import cn.jia.agent.service.AgentTaskEventReplayService.DurableEvent;
import cn.jia.agent.service.AgentTaskEventReplayService.TaskScope;
import cn.jia.agent.service.impl.AgentTaskWorkspaceEventValidator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTaskArtifactOutcomeEventProjectionTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String VIEWER = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String PRODUCER = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void privateOutcomeEventsPreserveReplayContinuityWithoutLeakingMetadata() {
        AuthorizedSubject worker = new AuthorizedSubject(
                TENANT, CLIENT, TASK, VIEWER, "worker", "other-coordinator");

        for (String eventType : new String[]{
                TaskEventType.ARTIFACT_ACCEPTED, TaskEventType.ARTIFACT_SUPERSEDED}) {
            AgentTaskEventProjection.Frame frame = AgentTaskEventProjection.project(
                    worker, event(eventType, "private"));
            assertEquals("17", frame.id());
            assertEquals(Map.of("version", "17", "redacted", true), frame.data());
            assertFalse(frame.terminal());
        }
    }

    @Test
    void outcomeAggregateBindsTheExactArtifactVersion() {
        AuthorizedSubject coordinator = new AuthorizedSubject(
                TENANT, CLIENT, TASK, VIEWER, "worker", VIEWER);
        DurableEvent valid = event(TaskEventType.ARTIFACT_ACCEPTED, "task_members");
        DurableEvent wrongAggregate = new DurableEvent(valid.scope(), valid.eventVersion(),
                valid.eventId(), valid.eventType(), valid.actorType(), valid.actorId(),
                valid.aggregateType(),
                AgentTaskWorkspaceEventValidator.artifactOutcomeAggregateId(
                        TASK, "artifact-private", 2),
                valid.eventJson(), valid.occurredAt());

        assertThrows(IllegalArgumentException.class,
                () -> AgentTaskEventProjection.project(coordinator, wrongAggregate));
    }

    @Test
    void producerReviewerAndCoordinatorVisibilityUsesExistingArtifactAcl() {
        AuthorizedSubject producer = new AuthorizedSubject(
                TENANT, CLIENT, TASK, PRODUCER, "worker", "other-coordinator");
        AuthorizedSubject reviewer = new AuthorizedSubject(
                TENANT, CLIENT, TASK, VIEWER, "reviewer", "other-coordinator");
        AuthorizedSubject coordinator = new AuthorizedSubject(
                TENANT, CLIENT, TASK, VIEWER, "worker", VIEWER);

        assertFalse(AgentTaskEventProjection.project(
                producer, event(TaskEventType.ARTIFACT_ACCEPTED, "private"))
                .data().containsKey("redacted"));
        assertFalse(AgentTaskEventProjection.project(
                reviewer, event(TaskEventType.ARTIFACT_SUPERSEDED, "reviewer"))
                .data().containsKey("redacted"));
        assertFalse(AgentTaskEventProjection.project(
                coordinator, event(TaskEventType.ARTIFACT_ACCEPTED, "private"))
                .data().containsKey("redacted"));
    }

    private DurableEvent event(String eventType, String visibility) {
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.ARTIFACT_ID, "artifact-private")
                .put(TaskEventPayload.Key.ARTIFACT_TYPE, "analysis")
                .put(TaskEventPayload.Key.ARTIFACT_VERSION, 1L)
                .put(TaskEventPayload.Key.PRODUCER_AGENT_ID, PRODUCER)
                .put(TaskEventPayload.Key.VISIBILITY, visibility)
                .put(TaskEventPayload.Key.FROM_STATUS, "draft")
                .put(TaskEventPayload.Key.TO_STATUS,
                        eventType.equals(TaskEventType.ARTIFACT_ACCEPTED)
                                ? "accepted" : "superseded")
                .put(TaskEventPayload.Key.EXPECTED_VERSION, 0L)
                .put(TaskEventPayload.Key.RESULT_VERSION, 1L)
                .put(TaskEventPayload.Key.DECISION_ID, "decision-1");
        if (eventType.equals(TaskEventType.ARTIFACT_SUPERSEDED)) {
            payload.put(TaskEventPayload.Key.SUPERSEDED_BY_ARTIFACT_ID, "artifact-new")
                    .put(TaskEventPayload.Key.SUPERSEDED_BY_ARTIFACT_VERSION, 1L);
        }
        return new DurableEvent(new TaskScope(TENANT, CLIENT, TASK),
                17L, "evt-outcome", eventType, "agent", VIEWER,
                "artifact", AgentTaskWorkspaceEventValidator.artifactOutcomeAggregateId(
                        TASK, "artifact-private", 1), payload.toJson(), 1234L);
    }
}
