package cn.jia.agent.common;

import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskEventPayloadTest {

    @Test
    void builderEmitsOnlyBoundedMetadataAndUtf8Digest() {
        String body = "机密正文";
        TaskEventPayload.ContentDigest digest = TaskEventPayload.ContentDigest.fromUtf8(body);

        String json = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.FROM_STATUS, "working")
                .put(TaskEventPayload.Key.TO_STATUS, "done")
                .put(TaskEventPayload.Key.EXPECTED_VERSION, 7L)
                .put(TaskEventPayload.Key.RESULT_VERSION, 8L)
                .putContentDigest(digest)
                .toJson();

        Map<String, Object> payload = JsonUtil.jsonToMap(json);
        assertEquals("working", payload.get("fromStatus"));
        assertEquals("done", payload.get("toStatus"));
        assertEquals(7L, ((Number) payload.get("expectedVersion")).longValue());
        assertEquals(8L, ((Number) payload.get("resultVersion")).longValue());
        assertEquals(12L, ((Number) payload.get("contentByteLength")).longValue());
        assertEquals("33444ac5d717fb9107c511bc16adb2b4590ad6b9a96ad8db1514aff1fcbcbb82",
                payload.get("contentSha256"));
        assertFalse(json.contains(body));
    }

    @Test
    void idFieldsAllowCredentialLikeSubstringsWithoutRelaxingSensitiveContentGuards() {
        String legalId = "id-authorization-api_key-api-key";
        List<String> idKeys = List.of(
                TaskEventPayload.Key.TASK_ID,
                TaskEventPayload.Key.AGENT_ID,
                TaskEventPayload.Key.MEMBER_ID,
                TaskEventPayload.Key.WORK_ITEM_ID,
                TaskEventPayload.Key.ASSIGNEE_AGENT_ID,
                TaskEventPayload.Key.REQUEST_ID,
                TaskEventPayload.Key.TARGET_ID,
                TaskEventPayload.Key.ARTIFACT_ID,
                TaskEventPayload.Key.THREAD_ID,
                TaskEventPayload.Key.CONVERSATION_ID,
                TaskEventPayload.Key.MESSAGE_ID,
                TaskEventPayload.Key.SENDER_AGENT_ID,
                TaskEventPayload.Key.NOTE_ID);
        TaskEventPayload.Builder builder = TaskEventPayload.builder();
        idKeys.forEach(key -> builder.put(key, legalId));

        Map<String, Object> payload = JsonUtil.jsonToMap(builder.toJson());
        idKeys.forEach(key -> assertEquals(legalId, payload.get(key)));
        for (String sensitiveMetadata : List.of(
                "api_key_secret", "credential_secret",
                "authorization_header", "access_token")) {
            assertThrows(IllegalArgumentException.class,
                    () -> TaskEventPayload.builder().put(
                            TaskEventPayload.Key.SOURCE, sensitiveMetadata));
        }
    }

    @Test
    void idFieldsUseUnicodeCodePointBoundsAndRejectInvalidScalars() {
        String supplementary = new String(Character.toChars(0x1f642));
        String exactlyOneHundredCodePoints = "a" + supplementary.repeat(99);
        String oneHundredOneCodePoints = "a" + supplementary.repeat(100);

        String json = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.AGENT_ID, exactlyOneHundredCodePoints)
                .toJson();
        assertEquals(exactlyOneHundredCodePoints,
                JsonUtil.jsonToMap(json).get(TaskEventPayload.Key.AGENT_ID));
        assertThrows(IllegalArgumentException.class, () -> TaskEventPayload.builder()
                .put(TaskEventPayload.Key.AGENT_ID, oneHundredOneCodePoints));
        assertThrows(IllegalArgumentException.class, () -> TaskEventPayload.builder()
                .put(TaskEventPayload.Key.AGENT_ID, "agent-\ud800"));
        assertThrows(IllegalArgumentException.class, () -> TaskEventPayload.builder()
                .put(TaskEventPayload.Key.AGENT_ID, "agent-\udc00"));
    }

    @Test
    void idFieldsRemainByteExactWithoutUnicodeNormalization() {
        String composed = "agent-\u00e9";
        String decomposed = "agent-e\u0301";

        Map<String, Object> payload = JsonUtil.jsonToMap(TaskEventPayload.builder()
                .put(TaskEventPayload.Key.AGENT_ID, composed)
                .put(TaskEventPayload.Key.SENDER_AGENT_ID, decomposed)
                .toJson());

        assertEquals(composed, payload.get(TaskEventPayload.Key.AGENT_ID));
        assertEquals(decomposed, payload.get(TaskEventPayload.Key.SENDER_AGENT_ID));
        assertFalse(composed.equals(decomposed));
    }

    @Test
    void rawNormalizationUsesTheSameUnicodeScalarAndCodePointContract() {
        String supplementary = new String(Character.toChars(0x1f642));
        String exact = "a" + supplementary.repeat(99);
        String normalized = TaskEventPayload.normalizeAllowedJson(
                JsonUtil.toJson(Map.of(TaskEventPayload.Key.AGENT_ID, exact)));
        assertEquals(exact, JsonUtil.jsonToMap(normalized).get(TaskEventPayload.Key.AGENT_ID));

        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.normalizeAllowedJson(JsonUtil.toJson(Map.of(
                        TaskEventPayload.Key.AGENT_ID, "a" + supplementary.repeat(100)))));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.normalizeAllowedJson(
                        "{\"agentId\":\"agent-\ud800\"}"));
    }

    @Test
    void builderRejectsSensitiveUnknownAndUnboundedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.builder().put("leaseToken", "raw-token"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.builder().put("authorization", "Bearer secret"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.builder().put("messageText", "full text"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.builder().put(
                        TaskEventPayload.Key.REASON_CODE, "x".repeat(65)));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.builder().put(
                        TaskEventPayload.Key.REASON_CODE, "full reason text"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.builder().put(
                        TaskEventPayload.Key.EXPECTED_VERSION, -1L));
    }

    @Test
    void rawPayloadNormalizationRejectsContentCredentialsNestedValuesAndOversize() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.normalizeAllowedJson("{\"content\":\"secret\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.normalizeAllowedJson("{\"leaseToken\":\"secret\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.normalizeAllowedJson("{\"authorization\":\"Bearer x\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.normalizeAllowedJson("{\"reasonCode\":{\"nested\":true}}"));
        assertThrows(IllegalArgumentException.class,
                () -> TaskEventPayload.normalizeAllowedJson(
                        "{\"reasonCode\":\"" + "x".repeat(5000) + "\"}"));
    }

    @Test
    void normalizationProducesCanonicalAllowedObjectRatherThanPersistingRawJson() {
        String normalized = TaskEventPayload.normalizeAllowedJson(
                " { \"toStatus\" : \"done\", \"fromStatus\" : \"working\" } ");

        assertEquals("{\"fromStatus\":\"working\",\"toStatus\":\"done\"}", normalized);
        assertTrue(TaskEventPayload.isAllowedKey(TaskEventPayload.Key.MESSAGE_ID));
        assertFalse(TaskEventPayload.isAllowedKey("cookie"));
    }
}
