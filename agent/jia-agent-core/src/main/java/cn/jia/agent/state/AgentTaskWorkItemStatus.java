package cn.jia.agent.state;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum AgentTaskWorkItemStatus {
    PENDING("pending"),
    READY("ready"),
    CLAIMED("claimed"),
    RUNNING("running"),
    BLOCKED("blocked"),
    SUBMITTED("submitted"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private static final Map<String, AgentTaskWorkItemStatus> BY_VALUE = valuesByValue();

    private final String value;

    AgentTaskWorkItemStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean canTransitionTo(AgentTaskWorkItemStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case PENDING -> EnumSet.of(READY, CANCELLED).contains(target);
            case READY -> EnumSet.of(CLAIMED, CANCELLED).contains(target);
            case CLAIMED -> EnumSet.of(RUNNING, READY, CANCELLED).contains(target);
            case RUNNING -> EnumSet.of(BLOCKED, SUBMITTED, FAILED, CANCELLED).contains(target);
            case BLOCKED -> EnumSet.of(READY, FAILED, CANCELLED).contains(target);
            case SUBMITTED -> EnumSet.of(COMPLETED, READY, CANCELLED).contains(target);
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    public boolean requiresClaimProtocol(AgentTaskWorkItemStatus target) {
        return canTransitionTo(target) && (this == CLAIMED || target == CLAIMED);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public static AgentTaskWorkItemStatus fromValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        AgentTaskWorkItemStatus status = BY_VALUE.get(normalized);
        if (status == null) {
            throw new IllegalArgumentException("Unknown task work item status");
        }
        return status;
    }

    /**
     * Parses a persisted status value with exact match only (no normalization).
     * Non-canonical persisted values (different case, whitespace) are rejected.
     */
    public static AgentTaskWorkItemStatus fromPersistedValue(String value) {
        AgentTaskWorkItemStatus status = BY_VALUE.get(value);
        if (status == null) {
            throw new IllegalArgumentException("Unknown task work item status");
        }
        return status;
    }


    private static Map<String, AgentTaskWorkItemStatus> valuesByValue() {
        return java.util.Arrays.stream(values())
                .collect(Collectors.toUnmodifiableMap(AgentTaskWorkItemStatus::value, Function.identity()));
    }
}
