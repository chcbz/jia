package cn.jia.chat.archive.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Frozen H05A persisted-event names and sensitive payload allowlists. */
final class ArchiveQuestionEventCatalog {
    static final String QUEUED = "QUESTION_QUEUED";
    static final String RUNNING = "QUESTION_RUNNING";
    static final String DELTA = "ANSWER_DELTA";
    static final String SUCCEEDED = "QUESTION_SUCCEEDED";
    static final String FAILED_RETRYABLE = "QUESTION_FAILED_RETRYABLE";
    static final String FAILED_FINAL = "QUESTION_FAILED_FINAL";
    static final String RETRY_QUEUED = "QUESTION_RETRY_QUEUED";

    private static final Map<String, Set<String>> PAYLOAD_KEYS = Map.of(
            QUEUED, Set.of("status", "responder", "retryCount"),
            RUNNING, Set.of("status", "attempt", "retryCount"),
            DELTA, Set.of("delta"),
            SUCCEEDED, Set.of("status"),
            FAILED_RETRYABLE, Set.of("status", "retryCount", "lastErrorCode"),
            FAILED_FINAL, Set.of("status", "retryCount", "lastErrorCode"),
            RETRY_QUEUED, Set.of("status", "responder", "retryCount"));

    private ArchiveQuestionEventCatalog() { }

    static void validate(String type, Map<String, Object> payload) {
        Set<String> allowed = PAYLOAD_KEYS.get(type);
        require(allowed != null && payload != null && payload.keySet().equals(allowed),
                "Unknown question event or payload fields");
        switch (type) {
            case QUEUED, RETRY_QUEUED -> {
                require("QUEUED".equals(payload.get("status")), "Invalid queued status");
                require(validRetryCount(payload.get("retryCount")), "Invalid queued retry count");
                require(fixedResponder(payload.get("responder")), "Invalid queued responder");
            }
            case RUNNING -> {
                require("RUNNING".equals(payload.get("status")), "Invalid running status");
                require(integer(payload.get("attempt"), 1, 3), "Invalid provider attempt");
                require(validRetryCount(payload.get("retryCount")), "Invalid running retry count");
            }
            case DELTA -> {
                Object delta = payload.get("delta");
                require(delta instanceof String value && !value.isEmpty()
                                && value.getBytes(StandardCharsets.UTF_8).length <= 4096,
                        "Invalid answer delta");
            }
            case SUCCEEDED -> require("SUCCEEDED".equals(payload.get("status")),
                    "Invalid succeeded status");
            case FAILED_RETRYABLE -> validateFailure(payload, "FAILED_RETRYABLE");
            case FAILED_FINAL -> validateFailure(payload, "FAILED_FINAL");
            default -> throw new IllegalStateException("Unknown question event type");
        }
    }

    private static void validateFailure(Map<String, Object> payload, String status) {
        require(status.equals(payload.get("status")), "Invalid failure status");
        require(validRetryCount(payload.get("retryCount")), "Invalid failure retry count");
        Object code = payload.get("lastErrorCode");
        require(code instanceof String value && value.matches("[A-Z][A-Z0-9_]{0,63}"),
                "Invalid failure error code");
    }

    private static boolean fixedResponder(Object value) {
        if (!(value instanceof Map<?, ?> responder)) return false;
        return responder.size() == 3
                && ArchiveQuestionServiceImpl.RESPONDER_ID.equals(responder.get("id"))
                && ArchiveQuestionServiceImpl.RESPONDER_NAME.equals(responder.get("displayName"))
                && ArchiveQuestionServiceImpl.RESPONDER_MODE.equals(responder.get("mode"));
    }

    private static boolean validRetryCount(Object value) {
        return integer(value, 0, 2);
    }

    private static boolean integer(Object value, int minimum, int maximum) {
        if (!(value instanceof Number number)) return false;
        long parsed = number.longValue();
        return parsed >= minimum && parsed <= maximum && number.doubleValue() == parsed;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
