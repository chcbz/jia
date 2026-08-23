package cn.jia.agent.api;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.service.AgentTaskEventAccessService.AuthorizedSubject;
import cn.jia.agent.service.AgentTaskEventReplayService.DurableEvent;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplaySignal;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncReason;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncRequired;
import cn.jia.agent.service.impl.AgentTaskWorkspaceEventValidator;
import cn.jia.agent.service.impl.AgentTaskWorkspaceEventValidator.ArtifactClaim;
import cn.jia.core.util.JsonUtil;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Fail-closed C05 wire projection. Raw durable rows never cross this boundary. */
final class AgentTaskEventProjection {
    static final int MAX_DATA_UTF8_BYTES = 16 * 1024;
    static final String TASK_EVENT = "task_event";
    static final String RESYNC_REQUIRED = "resync_required";

    private static final Set<String> ARTIFACT_VISIBILITIES = Set.of(
            "task_members", "reviewer", "private");
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private AgentTaskEventProjection() {
    }

    static Frame project(AuthorizedSubject subject, ReplaySignal signal) {
        if (subject == null || signal == null) {
            throw invalid();
        }
        if (signal instanceof DurableEvent durable) {
            return durable(subject, durable);
        }
        if (signal instanceof ResyncRequired resync) {
            return resync(subject, resync);
        }
        throw invalid();
    }

    private static Frame durable(AuthorizedSubject subject, DurableEvent event) {
        requireScope(subject, event.scope().tenantId(), event.scope().clientId(),
                event.scope().taskId());
        if (event.eventVersion() <= 0 || event.occurredAt() <= 0) {
            throw invalid();
        }
        requireExact(event.eventId(), 100);
        requireExact(event.eventType(), 64);
        requireExact(event.actorType(), 20);
        if (event.actorId() != null) {
            requireExact(event.actorId(), 100);
        }
        requireExact(event.aggregateType(), 30);
        requireExact(event.aggregateId(), 100);
        TaskEventType.requireKnown(event.eventType());
        TaskEventType.ActorType.requireKnown(event.actorType());
        TaskEventType.Aggregate.requireKnown(event.aggregateType());

        Map<String, Object> payload = normalizedPayload(event.eventJson());
        ArtifactClaim artifact = AgentTaskWorkspaceEventValidator.validate(
                event.eventType(), event.actorType(), event.actorId(),
                event.aggregateType(), event.aggregateId(), payload,
                subject.taskId());
        String version = Long.toString(event.eventVersion());
        if (artifact != null && !artifactVisible(subject, artifact)) {
            LinkedHashMap<String, Object> redacted = new LinkedHashMap<>();
            redacted.put("version", version);
            redacted.put("redacted", Boolean.TRUE);
            requireBounded(redacted);
            return new Frame(TASK_EVENT, version, redacted, false);
        }

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("version", version);
        data.put("eventId", event.eventId());
        data.put("eventType", event.eventType());
        data.put("actorType", event.actorType());
        data.put("actorId", event.actorId());
        data.put("aggregateType", event.aggregateType());
        data.put("aggregateId", event.aggregateId());
        data.put("payload", wirePayload(payload));
        data.put("occurredAt", Long.toString(event.occurredAt()));
        requireBounded(data);
        boolean revoke = (TaskEventType.MEMBER_LEFT.equals(event.eventType())
                || TaskEventType.MEMBER_REJECTED.equals(event.eventType()))
                && subject.actorAgentId().equals(event.aggregateId());
        return new Frame(TASK_EVENT, version, data, revoke);
    }

    private static Frame resync(AuthorizedSubject subject, ResyncRequired resync) {
        requireScope(subject, resync.scope().tenantId(), resync.scope().clientId(),
                resync.scope().taskId());
        if (resync.currentVersion() < 0 || resync.reason() == null) {
            throw invalid();
        }
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("currentVersion", Long.toString(resync.currentVersion()));
        data.put("reason", reason(resync.reason()));
        requireBounded(data);
        return new Frame(RESYNC_REQUIRED, null, data, true);
    }

    private static Map<String, Object> normalizedPayload(String eventJson) {
        try {
            STRICT_JSON.readTree(eventJson);
            String normalized = TaskEventPayload.normalizeAllowedJson(eventJson);
            Object parsed = JsonUtil.getMapper().readValue(normalized, Object.class);
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw invalid();
            }
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || !TaskEventPayload.isAllowedKey(key)) {
                    throw invalid();
                }
                payload.put(key, entry.getValue());
            }
            return payload;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static Map<String, Object> wirePayload(Map<String, Object> payload) {
        LinkedHashMap<String, Object> projected = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String string) {
                projected.put(entry.getKey(), string);
                continue;
            }
            if (!(value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof BigInteger)) {
                throw invalid();
            }
            final long integral;
            try {
                integral = value instanceof BigInteger integer
                        ? integer.longValueExact() : ((Number) value).longValue();
            } catch (ArithmeticException exception) {
                throw invalid();
            }
            if (integral < 0) {
                throw invalid();
            }
            projected.put(entry.getKey(), Long.toString(integral));
        }
        return projected;
    }

    private static boolean artifactVisible(
            AuthorizedSubject subject, ArtifactClaim artifact) {
        if (!ARTIFACT_VISIBILITIES.contains(artifact.visibility())) {
            throw invalid();
        }
        if (subject.coordinatorAccess()
                || subject.actorAgentId().equals(artifact.producerAgentId())) {
            return true;
        }
        return switch (artifact.visibility()) {
            case "task_members" -> true;
            case "reviewer" -> subject.reviewerAccess();
            case "private" -> false;
            default -> throw invalid();
        };
    }

    private static void requireScope(AuthorizedSubject subject,
            String tenantId, String clientId, String taskId) {
        if (!subject.tenantId().equals(tenantId)
                || !subject.clientId().equals(clientId)
                || !subject.taskId().equals(taskId)) {
            throw invalid();
        }
    }

    private static void requireExact(String value, int maxLength) {
        if (value == null || hasUnpairedSurrogate(value)
                || value.codePointCount(0, value.length()) > maxLength
                || value.codePoints().allMatch(AgentTaskEventProjection::isPadding)
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

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static String reason(ResyncReason reason) {
        return switch (reason) {
            case CURSOR_AHEAD -> "cursor_ahead";
            case HISTORY_GAP -> "history_gap";
            case RETENTION_GAP -> "retention_gap";
            case PAGE_GAP -> "page_gap";
            case REPLAY_BUDGET_EXHAUSTED -> "replay_budget_exhausted";
            case DURABLE_STATE_UNPROVABLE -> "durable_state_unprovable";
        };
    }

    private static void requireBounded(Map<String, Object> data) {
        try {
            byte[] bytes = JsonUtil.getMapper().writeValueAsBytes(data);
            if (bytes.length > MAX_DATA_UTF8_BYTES
                    || !new String(bytes, StandardCharsets.UTF_8).equals(
                            JsonUtil.getMapper().writeValueAsString(data))) {
                throw invalid();
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Task event projection is unavailable");
    }

    record Frame(String eventName, String id, Map<String, Object> data, boolean terminal) {
        Frame {
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }
}
