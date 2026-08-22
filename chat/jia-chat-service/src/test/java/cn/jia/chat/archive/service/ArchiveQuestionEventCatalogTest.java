package cn.jia.chat.archive.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveQuestionEventCatalogTest {
    @Test
    void frozenPayloadAllowlistsAcceptOnlyTypeSpecificNonRoutingFields() {
        Map<String, Object> responder = Map.of(
                "id", "archive-clerk-v1", "displayName", "案卷书吏", "mode", "fallback");
        assertDoesNotThrow(() -> ArchiveQuestionEventCatalog.validate(
                ArchiveQuestionEventCatalog.QUEUED,
                Map.of("status", "QUEUED", "responder", responder, "retryCount", 0)));
        assertDoesNotThrow(() -> ArchiveQuestionEventCatalog.validate(
                ArchiveQuestionEventCatalog.RUNNING,
                Map.of("status", "RUNNING", "attempt", 1, "retryCount", 0)));
        assertDoesNotThrow(() -> ArchiveQuestionEventCatalog.validate(
                ArchiveQuestionEventCatalog.DELTA, Map.of("delta", "忠义")));
        assertDoesNotThrow(() -> ArchiveQuestionEventCatalog.validate(
                ArchiveQuestionEventCatalog.SUCCEEDED, Map.of("status", "SUCCEEDED")));
        assertDoesNotThrow(() -> ArchiveQuestionEventCatalog.validate(
                ArchiveQuestionEventCatalog.FAILED_RETRYABLE,
                Map.of("status", "FAILED_RETRYABLE", "retryCount", 1,
                        "lastErrorCode", "QUESTION_PROVIDER_UNAVAILABLE")));

        for (String forbidden : new String[]{"question", "selectedText", "targetAgentId", "role", "ownerJiacn"}) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("delta", "x");
            payload.put(forbidden, "must-not-leak");
            assertThrows(IllegalStateException.class,
                    () -> ArchiveQuestionEventCatalog.validate(ArchiveQuestionEventCatalog.DELTA, payload));
        }
    }

    @Test
    void invalidResponderDeltaAttemptRetryAndErrorCodeFailClosed() {
        assertThrows(IllegalStateException.class, () -> ArchiveQuestionEventCatalog.validate(
                ArchiveQuestionEventCatalog.QUEUED,
                Map.of("status", "QUEUED", "responder",
                        Map.of("id", "agent-wuyong", "displayName", "吴用", "mode", "agent"),
                        "retryCount", 0)));
        assertThrows(IllegalStateException.class, () -> ArchiveQuestionEventCatalog.validate(
                ArchiveQuestionEventCatalog.DELTA, Map.of("delta", "x".repeat(4097))));
        assertThrows(IllegalStateException.class, () -> ArchiveQuestionEventCatalog.validate(
                ArchiveQuestionEventCatalog.RUNNING,
                Map.of("status", "RUNNING", "attempt", 4, "retryCount", 3)));
        assertThrows(IllegalStateException.class, () -> ArchiveQuestionEventCatalog.validate(
                ArchiveQuestionEventCatalog.FAILED_FINAL,
                Map.of("status", "FAILED_FINAL", "retryCount", 2, "lastErrorCode", "bad-code")));
    }
}
