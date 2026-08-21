package cn.jia.agent.api;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.service.AgentTaskEventReplayService.DurableEvent;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncReason;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncRequired;
import cn.jia.agent.service.AgentTaskEventReplayService.TaskScope;
import cn.jia.agent.service.AgentTaskWorkspaceService.AuthorizedSubject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEventProjectionTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final TaskScope SCOPE = new TaskScope(TENANT, CLIENT, TASK);

    @Test
    void visibleProjectionHasOnlyFrozenFieldsExactDecimalStringsAndAllowedPayload() {
        long version = 9_007_199_254_740_993L;
        DurableEvent event = taskCreated(version, "evt-safe", """
                {"taskId":"task-1","taskType":"agent_task","status":"assigned",
                 "resultVersion":9007199254740993,"createdAt":1234}
                """);

        AgentTaskEventProjection.Frame frame = AgentTaskEventProjection.project(
                subject("worker", OTHER), event);

        assertEquals("task_event", frame.eventName());
        assertEquals("9007199254740993", frame.id());
        assertFalse(frame.terminal());
        assertEquals(Set.of("version", "eventId", "eventType", "actorType", "actorId",
                "aggregateType", "aggregateId", "payload", "occurredAt"),
                frame.data().keySet());
        assertEquals("9007199254740993", frame.data().get("version"));
        assertEquals("1234", frame.data().get("occurredAt"));
        assertNull(frame.data().get("actorId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) frame.data().get("payload");
        assertEquals(Set.of("createdAt", "resultVersion", "status", "taskId", "taskType"),
                payload.keySet());
        assertFalse(frame.data().containsKey("tenantId"));
        assertFalse(frame.data().containsKey("clientId"));
        assertFalse(frame.data().containsKey("eventJson"));
        assertFalse(frame.data().containsKey("id"));
    }

    @Test
    void hiddenArtifactPreservesContinuityWithExactlyVersionAndRedacted() {
        DurableEvent event = artifact("private", OTHER);
        AgentTaskEventProjection.Frame frame = AgentTaskEventProjection.project(
                subject("worker", OTHER), event);
        assertEquals("7", frame.id());
        assertEquals(Map.of("version", "7", "redacted", true), frame.data());
        assertFalse(frame.terminal());
    }

    @Test
    void artifactVisibilityMatchesC04ReviewerProducerCoordinatorRules() {
        assertFalse(AgentTaskEventProjection.project(
                subject("reviewer", OTHER), artifact("reviewer", OTHER))
                .data().containsKey("redacted"));
        assertFalse(AgentTaskEventProjection.project(
                subject("worker", OTHER), artifact("private", ACTOR))
                .data().containsKey("redacted"));
        assertFalse(AgentTaskEventProjection.project(
                subject("worker", ACTOR), artifact("private", OTHER))
                .data().containsKey("redacted"));
        assertFalse(AgentTaskEventProjection.project(
                subject("worker", OTHER), artifact("task_members", OTHER))
                .data().containsKey("redacted"));
    }

    @ParameterizedTest
    @EnumSource(ResyncReason.class)
    void everyResyncReasonHasNoIdExactReasonAndIsTerminal(ResyncReason reason) {
        AgentTaskEventProjection.Frame frame = AgentTaskEventProjection.project(
                subject("worker", OTHER), new ResyncRequired(SCOPE, Long.MAX_VALUE, reason));
        assertEquals("resync_required", frame.eventName());
        assertNull(frame.id());
        assertTrue(frame.terminal());
        assertEquals("9223372036854775807", frame.data().get("currentVersion"));
        assertEquals(reason.name().toLowerCase(java.util.Locale.ROOT),
                frame.data().get("reason"));
        assertEquals(Set.of("currentVersion", "reason"), frame.data().keySet());
    }

    @Test
    void ownRejectedOrLeftMemberEventClosesOnlyAfterDurableFrame() {
        for (String type : Set.of(TaskEventType.MEMBER_REJECTED, TaskEventType.MEMBER_LEFT)) {
            String status = type.equals(TaskEventType.MEMBER_REJECTED) ? "rejected" : "left";
            DurableEvent event = new DurableEvent(SCOPE, 9L, "evt-member", type,
                    "system", null, "member", ACTOR,
                    "{\"agentId\":\"" + ACTOR + "\",\"role\":\"worker\","
                            + "\"toStatus\":\"" + status + "\",\"resultVersion\":2}",
                    1234L);
            AgentTaskEventProjection.Frame frame = AgentTaskEventProjection.project(
                    subject("worker", OTHER), event);
            assertEquals("9", frame.id());
            assertTrue(frame.terminal());
            assertEquals(type, frame.data().get("eventType"));
        }
    }

    @Test
    void malformedUnknownDuplicateCrossScopeAndInvalidVisibilityFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> AgentTaskEventProjection.project(
                subject("worker", OTHER), taskCreated(1, "evt-1",
                        "{\"taskId\":\"task-1\",\"taskId\":\"other\"}")));
        assertThrows(IllegalArgumentException.class, () -> AgentTaskEventProjection.project(
                subject("worker", OTHER), taskCreated(1, "evt-1",
                        "{\"credential\":\"secret\"}")));
        assertThrows(IllegalArgumentException.class, () -> AgentTaskEventProjection.project(
                subject("worker", OTHER), taskCreated(1, "evt-1",
                        validTaskPayload() + "{\"credential\":\"secret\"}")));
        DurableEvent crossScope = new DurableEvent(
                new TaskScope(TENANT, "client-b", TASK), 1, "evt-1",
                TaskEventType.TASK_CREATED, "system", null, "task", TASK,
                validTaskPayload(), 1234);
        assertThrows(IllegalArgumentException.class, () -> AgentTaskEventProjection.project(
                subject("worker", OTHER), crossScope));
        assertThrows(IllegalArgumentException.class, () -> AgentTaskEventProjection.project(
                subject("worker", OTHER), artifact("unknown", OTHER)));
    }

    @Test
    void boundedIdentifiersCountUnicodeCodePointsAndRejectOverflow() {
        String exact = "😀".repeat(100);
        AgentTaskEventProjection.Frame frame = AgentTaskEventProjection.project(
                subject("worker", OTHER), taskCreated(1, exact, validTaskPayload()));
        assertEquals(exact, frame.data().get("eventId"));

        assertThrows(IllegalArgumentException.class, () -> AgentTaskEventProjection.project(
                subject("worker", OTHER),
                taskCreated(1, exact + "x", validTaskPayload())));
    }

    @Test
    void serializedDataCapIsEnforcedOnUtf8Bytes() throws Exception {
        Method method = AgentTaskEventProjection.class.getDeclaredMethod(
                "requireBounded", Map.class);
        method.setAccessible(true);
        LinkedHashMap<String, Object> oversized = new LinkedHashMap<>();
        oversized.put("value", "界".repeat(AgentTaskEventProjection.MAX_DATA_UTF8_BYTES));
        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class, () -> method.invoke(null, oversized));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
    }

    private static DurableEvent taskCreated(long version, String eventId, String payload) {
        return new DurableEvent(SCOPE, version, eventId,
                TaskEventType.TASK_CREATED, "system", null, "task", TASK,
                payload, 1234L);
    }

    private static DurableEvent artifact(String visibility, String producer) {
        return new DurableEvent(SCOPE, 7L, "evt-artifact",
                TaskEventType.ARTIFACT_PUBLISHED, "agent", producer,
                "artifact", "artifact-1",
                "{\"artifactId\":\"artifact-1\",\"artifactType\":\"report\","
                        + "\"artifactVersion\":1,\"visibility\":\"" + visibility + "\","
                        + "\"contentSha256\":\"" + "a".repeat(64) + "\"}",
                1234L);
    }

    private static AuthorizedSubject subject(String role, String coordinator) {
        return new AuthorizedSubject(TENANT, CLIENT, TASK, ACTOR, role, coordinator);
    }

    private static String validTaskPayload() {
        return "{\"taskId\":\"task-1\",\"taskType\":\"agent_task\","
                + "\"status\":\"assigned\",\"resultVersion\":1,\"createdAt\":1234}";
    }
}
