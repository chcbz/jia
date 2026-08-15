package cn.jia.agent.common;

import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

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
