package cn.jia.agent.common;

import cn.jia.core.util.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * C01B bounded metadata payload contract for {@code agent_task_event.event_json}.
 *
 * <p>The contract is intentionally scalar-only and fail-closed. Callers cannot add
 * arbitrary metadata, child snapshots, bodies, lease tokens, credentials, or nested
 * values. Raw JSON supplied through the C01 writer is parsed, validated and rewritten
 * into canonical key order before persistence.
 */
public final class TaskEventPayload {
    public static final int MAX_JSON_UTF8_BYTES = 4096;
    public static final int MAX_KEYS = 32;

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern METADATA_TOKEN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");
    private static final Set<String> SENSITIVE_METADATA_MARKERS = Set.of(
            "authorization", "bearer ", "basic ", "api-key", "api_key",
            "cookie", "credential", "header", "private key", "token");

    private static final Set<String> ID_KEYS = Set.of(
            Key.TASK_ID, Key.AGENT_ID, Key.MEMBER_ID, Key.WORK_ITEM_ID,
            Key.ASSIGNEE_AGENT_ID, Key.REQUEST_ID, Key.TARGET_ID,
            Key.ARTIFACT_ID, Key.THREAD_ID, Key.CONVERSATION_ID,
            Key.MESSAGE_ID, Key.SENDER_AGENT_ID, Key.NOTE_ID);

    private static final Set<String> STRING_KEYS = Set.of(
            Key.FROM_STATUS, Key.TO_STATUS, Key.STATUS, Key.REASON_CODE,
            Key.ROLE, Key.SOURCE, Key.DECISION_CODE,
            Key.TASK_TYPE, Key.REQUEST_TYPE, Key.TARGET_TYPE,
            Key.ARTIFACT_TYPE, Key.VISIBILITY, Key.THREAD_TYPE,
            Key.MESSAGE_TYPE, Key.NOTE_TYPE);

    private static final Set<String> LONG_KEYS = Set.of(
            Key.EXPECTED_VERSION, Key.RESULT_VERSION, Key.ARTIFACT_VERSION,
            Key.ATTEMPT_COUNT, Key.MAX_ATTEMPTS,
            Key.CONTENT_BYTE_LENGTH,
            Key.MEMBER_COUNT, Key.WORK_ITEM_COUNT,
            Key.COMPLETED_WORK_ITEM_COUNT, Key.FAILED_WORK_ITEM_COUNT,
            Key.CREATED_AT, Key.UPDATED_AT, Key.ASSIGNED_AT, Key.STARTED_AT,
            Key.COMPLETED_AT, Key.ACKNOWLEDGED_AT, Key.RESOLVED_AT,
            Key.CANCELLED_AT, Key.PUBLISHED_AT,
            Key.PREVIOUS_LEASE_EXPIRES_AT, Key.LEASE_EXPIRES_AT);

    private static final Set<String> ALLOWED_KEYS;

    static {
        TreeMap<String, Boolean> keys = new TreeMap<>();
        ID_KEYS.forEach(key -> keys.put(key, Boolean.TRUE));
        STRING_KEYS.forEach(key -> keys.put(key, Boolean.TRUE));
        LONG_KEYS.forEach(key -> keys.put(key, Boolean.TRUE));
        keys.put(Key.CONTENT_SHA256, Boolean.TRUE);
        ALLOWED_KEYS = Collections.unmodifiableSet(keys.keySet());
    }

    private TaskEventPayload() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static boolean isAllowedKey(String key) {
        return key != null && ALLOWED_KEYS.contains(key);
    }

    /**
     * Parse, validate and canonicalize an externally assembled payload.
     *
     * @return a canonical JSON object with sorted keys
     */
    public static String normalizeAllowedJson(String eventJson) {
        requireJsonSize(eventJson);
        final Object parsed;
        try {
            parsed = JsonUtil.getMapper().readValue(eventJson, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("eventJson must be a valid bounded JSON object", e);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("eventJson must be a JSON object");
        }
        if (map.size() > MAX_KEYS) {
            throw new IllegalArgumentException("eventJson has too many keys; max=" + MAX_KEYS);
        }

        Builder builder = builder();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("eventJson keys must be strings");
            }
            Object value = entry.getValue();
            if (value instanceof String stringValue) {
                builder.put(key, stringValue);
            } else if (isIntegralNumber(value)) {
                builder.put(key, exactLong((Number) value, key));
            } else {
                throw new IllegalArgumentException(
                        "eventJson key " + key + " must contain a bounded string or integer");
            }
        }
        return builder.toJson();
    }

    private static boolean isIntegralNumber(Object value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger;
    }

    private static long exactLong(Number value, String key) {
        if (value instanceof java.math.BigInteger integer) {
            try {
                return integer.longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("eventJson integer is out of range: " + key, e);
            }
        }
        return value.longValue();
    }

    private static void requireJsonSize(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("eventJson is required");
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_JSON_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "eventJson exceeds " + MAX_JSON_UTF8_BYTES + " UTF-8 bytes");
        }
    }

    private static void requireAllowedKey(String key) {
        if (!isAllowedKey(key)) {
            throw new IllegalArgumentException("event payload key is not allowed: " + key);
        }
    }

    private static void requireCleanString(String key, String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("event payload string is invalid: " + key);
        }
        int maxLength = ID_KEYS.contains(key) ? 100 : 64;
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "event payload string exceeds " + maxLength + " chars: " + key);
        }
        if (ID_KEYS.contains(key)) {
            return;
        }
        if (!METADATA_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "event payload metadata must be a bounded code token: " + key);
        }
        String lowercase = value.toLowerCase(java.util.Locale.ROOT);
        if (SENSITIVE_METADATA_MARKERS.stream().anyMatch(lowercase::contains)) {
            throw new IllegalArgumentException("event payload contains sensitive material: " + key);
        }
    }

    private static void requireLong(String key, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("event payload integer must not be negative: " + key);
        }
    }

    public static final class Builder {
        private final TreeMap<String, Object> values = new TreeMap<>();

        private Builder() {
        }

        public Builder put(String key, String value) {
            requireAllowedKey(key);
            if (Key.CONTENT_SHA256.equals(key)) {
                if (value == null || !SHA_256.matcher(value).matches()) {
                    throw new IllegalArgumentException(
                            "contentSha256 must be lowercase SHA-256 hex");
                }
            } else {
                if (!ID_KEYS.contains(key) && !STRING_KEYS.contains(key)) {
                    throw new IllegalArgumentException(
                            "event payload key does not accept a string: " + key);
                }
                requireCleanString(key, value);
            }
            putBounded(key, value);
            return this;
        }

        public Builder put(String key, long value) {
            requireAllowedKey(key);
            if (!LONG_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "event payload key does not accept an integer: " + key);
            }
            requireLong(key, value);
            putBounded(key, value);
            return this;
        }

        public Builder putContentDigest(ContentDigest digest) {
            if (digest == null) {
                throw new IllegalArgumentException("content digest is required");
            }
            return put(Key.CONTENT_BYTE_LENGTH, digest.byteLength())
                    .put(Key.CONTENT_SHA256, digest.sha256());
        }

        public String toJson() {
            String json = JsonUtil.toJson(values);
            if (json == null) {
                throw new IllegalStateException("Failed to serialize bounded event payload");
            }
            requireJsonSize(json);
            return json;
        }

        private void putBounded(String key, Object value) {
            if (!values.containsKey(key) && values.size() >= MAX_KEYS) {
                throw new IllegalArgumentException("event payload has too many keys; max=" + MAX_KEYS);
            }
            values.put(key, value);
        }
    }

    /** SHA-256 and UTF-8 byte length without retaining source content. */
    public record ContentDigest(long byteLength, String sha256) {
        public ContentDigest {
            if (byteLength < 0) {
                throw new IllegalArgumentException("content byte length must not be negative");
            }
            if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 must be lowercase SHA-256 hex");
            }
        }

        public static ContentDigest fromUtf8(String content) {
            if (content == null) {
                throw new IllegalArgumentException("content must not be null");
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
                return new ContentDigest(bytes.length, HexFormat.of().formatHex(hash));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }
    }

    /** Frozen C01B payload key catalog. */
    public static final class Key {
        private Key() {
        }

        public static final String FROM_STATUS = "fromStatus";
        public static final String TO_STATUS = "toStatus";
        public static final String STATUS = "status";
        public static final String REASON_CODE = "reasonCode";
        public static final String EXPECTED_VERSION = "expectedVersion";
        public static final String RESULT_VERSION = "resultVersion";

        public static final String TASK_ID = "taskId";
        public static final String TASK_TYPE = "taskType";
        public static final String AGENT_ID = "agentId";
        public static final String MEMBER_ID = "memberId";
        public static final String WORK_ITEM_ID = "workItemId";
        public static final String ASSIGNEE_AGENT_ID = "assigneeAgentId";
        public static final String ROLE = "role";
        public static final String ATTEMPT_COUNT = "attemptCount";
        public static final String MAX_ATTEMPTS = "maxAttempts";

        public static final String REQUEST_ID = "requestId";
        public static final String REQUEST_TYPE = "requestType";
        public static final String TARGET_TYPE = "targetType";
        public static final String TARGET_ID = "targetId";

        public static final String ARTIFACT_ID = "artifactId";
        public static final String ARTIFACT_TYPE = "artifactType";
        public static final String ARTIFACT_VERSION = "artifactVersion";
        public static final String VISIBILITY = "visibility";

        public static final String THREAD_ID = "threadId";
        public static final String THREAD_TYPE = "threadType";
        public static final String CONVERSATION_ID = "conversationId";
        public static final String MESSAGE_ID = "messageId";
        public static final String MESSAGE_TYPE = "messageType";
        public static final String SENDER_AGENT_ID = "senderAgentId";

        public static final String NOTE_ID = "noteId";
        public static final String NOTE_TYPE = "noteType";
        public static final String CONTENT_BYTE_LENGTH = "contentByteLength";
        public static final String CONTENT_SHA256 = "contentSha256";

        public static final String SOURCE = "source";
        public static final String DECISION_CODE = "decisionCode";
        public static final String MEMBER_COUNT = "memberCount";
        public static final String WORK_ITEM_COUNT = "workItemCount";
        public static final String COMPLETED_WORK_ITEM_COUNT = "completedWorkItemCount";
        public static final String FAILED_WORK_ITEM_COUNT = "failedWorkItemCount";

        public static final String CREATED_AT = "createdAt";
        public static final String UPDATED_AT = "updatedAt";
        public static final String ASSIGNED_AT = "assignedAt";
        public static final String STARTED_AT = "startedAt";
        public static final String COMPLETED_AT = "completedAt";
        public static final String ACKNOWLEDGED_AT = "acknowledgedAt";
        public static final String RESOLVED_AT = "resolvedAt";
        public static final String CANCELLED_AT = "cancelledAt";
        public static final String PUBLISHED_AT = "publishedAt";
        public static final String PREVIOUS_LEASE_EXPIRES_AT = "previousLeaseExpiresAt";
        public static final String LEASE_EXPIRES_AT = "leaseExpiresAt";
    }
}
