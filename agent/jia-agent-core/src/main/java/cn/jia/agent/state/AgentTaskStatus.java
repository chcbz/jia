package cn.jia.agent.state;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum AgentTaskStatus {
    OPEN("open"),
    PLANNING("planning"),
    ASSIGNED("assigned"),
    RUNNING("running"),
    REVIEWING("reviewing"),
    BLOCKED("blocked"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ARCHIVED("archived");

    private static final Map<String, AgentTaskStatus> BY_VALUE = valuesByValue();

    private final String value;

    AgentTaskStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean canTransitionTo(AgentTaskStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case OPEN -> EnumSet.of(PLANNING, ASSIGNED, CANCELLED).contains(target);
            case PLANNING -> EnumSet.of(ASSIGNED, CANCELLED).contains(target);
            case ASSIGNED -> EnumSet.of(RUNNING, BLOCKED, CANCELLED).contains(target);
            case RUNNING -> EnumSet.of(REVIEWING, BLOCKED, FAILED, CANCELLED).contains(target);
            case BLOCKED -> EnumSet.of(RUNNING, FAILED, CANCELLED).contains(target);
            case REVIEWING -> EnumSet.of(RUNNING, COMPLETED, FAILED, CANCELLED).contains(target);
            case COMPLETED, FAILED, CANCELLED -> target == ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    public boolean isOperationalTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == ARCHIVED;
    }

    public static AgentTaskStatus fromValue(String value) {
        return parse(value, BY_VALUE, "task");
    }

    /**
     * Parses a persisted status value with exact match only (no normalization).
     * Non-canonical persisted values (different case, whitespace) are rejected.
     */
    public static AgentTaskStatus fromPersistedValue(String value) {
        AgentTaskStatus status = BY_VALUE.get(value);
        if (status == null) {
            throw new IllegalArgumentException("Unknown task status");
        }
        return status;
    }


    private static Map<String, AgentTaskStatus> valuesByValue() {
        return java.util.Arrays.stream(values())
                .collect(Collectors.toUnmodifiableMap(AgentTaskStatus::value, Function.identity()));
    }

    private static AgentTaskStatus parse(
            String value, Map<String, AgentTaskStatus> values, String stateType) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        AgentTaskStatus status = values.get(normalized);
        if (status == null) {
            throw new IllegalArgumentException("Unknown " + stateType + " status");
        }
        return status;
    }
}
