package cn.jia.agent.state;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum AgentTaskMemberStatus {
    INVITED("invited"),
    ACCEPTED("accepted"),
    WORKING("working"),
    BLOCKED("blocked"),
    DONE("done"),
    REJECTED("rejected"),
    FAILED("failed"),
    LEFT("left");

    private static final Map<String, AgentTaskMemberStatus> BY_VALUE = valuesByValue();

    private final String value;

    AgentTaskMemberStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean canTransitionTo(AgentTaskMemberStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case INVITED -> EnumSet.of(ACCEPTED, REJECTED).contains(target);
            case ACCEPTED -> EnumSet.of(WORKING, LEFT).contains(target);
            case WORKING -> EnumSet.of(DONE, BLOCKED, FAILED, LEFT).contains(target);
            case BLOCKED -> EnumSet.of(WORKING, FAILED, LEFT).contains(target);
            case DONE, REJECTED, FAILED, LEFT -> false;
        };
    }

    public boolean isTerminal() {
        return this == DONE || this == REJECTED || this == FAILED || this == LEFT;
    }

    public static AgentTaskMemberStatus fromValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        AgentTaskMemberStatus status = BY_VALUE.get(normalized);
        if (status == null) {
            throw new IllegalArgumentException("Unknown task member status");
        }
        return status;
    }

    /**
     * Parses a persisted status value with exact match only (no normalization).
     * Non-canonical persisted values (different case, whitespace) are rejected.
     */
    public static AgentTaskMemberStatus fromPersistedValue(String value) {
        AgentTaskMemberStatus status = BY_VALUE.get(value);
        if (status == null) {
            throw new IllegalArgumentException("Unknown task member status");
        }
        return status;
    }


    private static Map<String, AgentTaskMemberStatus> valuesByValue() {
        return java.util.Arrays.stream(values())
                .collect(Collectors.toUnmodifiableMap(AgentTaskMemberStatus::value, Function.identity()));
    }
}
