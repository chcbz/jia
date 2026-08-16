package cn.jia.agent.service;

import reactor.core.publisher.Flux;

import java.util.Objects;

/** ACL- and transport-independent durable task-event replay contract. */
public interface AgentTaskEventReplayService {

    /**
     * Subscribe live-first and replay durable events strictly after {@code afterVersion}.
     *
     * <p>The stream emits ordered durable events or exactly one terminal resync requirement.
     * Versions remain Java {@code long} values at this boundary.
     */
    Flux<ReplaySignal> replay(TaskScope scope, long afterVersion);

    /** Exact task scope. Valid identities are preserved byte-for-byte. */
    record TaskScope(String tenantId, String clientId, String taskId) {
        public TaskScope {
            tenantId = requireIdentity(tenantId, "tenantId", 50);
            clientId = requireIdentity(clientId, "clientId", 50);
            taskId = requireIdentity(taskId, "taskId", 100);
        }
    }

    sealed interface ReplaySignal permits DurableEvent, ResyncRequired {
    }

    record DurableEvent(
            TaskScope scope,
            long eventVersion,
            String eventId,
            String eventType,
            String actorType,
            String actorId,
            String aggregateType,
            String aggregateId,
            String eventJson,
            long occurredAt) implements ReplaySignal {
        public DurableEvent {
            scope = Objects.requireNonNull(scope, "task scope is required");
            if (eventVersion <= 0) {
                throw new IllegalArgumentException("eventVersion must be positive");
            }
            eventId = Objects.requireNonNull(eventId, "eventId is required");
            eventType = Objects.requireNonNull(eventType, "eventType is required");
            actorType = Objects.requireNonNull(actorType, "actorType is required");
            aggregateType = Objects.requireNonNull(aggregateType, "aggregateType is required");
            aggregateId = Objects.requireNonNull(aggregateId, "aggregateId is required");
            eventJson = Objects.requireNonNull(eventJson, "eventJson is required");
            if (occurredAt <= 0) {
                throw new IllegalArgumentException("occurredAt must be positive");
            }
        }
    }

    record ResyncRequired(
            TaskScope scope,
            long currentVersion,
            ResyncReason reason) implements ReplaySignal {
        public ResyncRequired {
            scope = Objects.requireNonNull(scope, "task scope is required");
            reason = Objects.requireNonNull(reason, "resync reason is required");
            if (currentVersion < 0) {
                throw new IllegalArgumentException("currentVersion must not be negative");
            }
        }
    }

    enum ResyncReason {
        CURSOR_AHEAD,
        HISTORY_GAP,
        RETENTION_GAP,
        PAGE_GAP,
        REPLAY_BUDGET_EXHAUSTED,
        DURABLE_STATE_UNPROVABLE
    }

    private static String requireIdentity(String value, String field, int maxLength) {
        if (value == null || value.length() > maxLength
                || value.codePoints().allMatch(AgentTaskEventReplayService::isPadding)
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " is invalid: must be non-blank, unpadded, byte-exact and at most "
                            + maxLength + " chars");
        }
        return value;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** Stable fail-closed outcome when downstream demand cannot keep replay bounded. */
    final class ReplayBackpressureException extends IllegalStateException {
        public ReplayBackpressureException() {
            super("Task event replay downstream demand is insufficient");
        }
    }
}
