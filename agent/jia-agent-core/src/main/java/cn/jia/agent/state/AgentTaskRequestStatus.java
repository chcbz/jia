package cn.jia.agent.state;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Strict persisted state machine for task collaboration requests. */
public enum AgentTaskRequestStatus {
    OPEN("open"),
    ACKNOWLEDGED("acknowledged"),
    RESOLVED("resolved"),
    REJECTED("rejected"),
    CANCELLED("cancelled");

    private static final Map<String, AgentTaskRequestStatus> BY_VALUE = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(AgentTaskRequestStatus::value, Function.identity()));

    private final String value;

    AgentTaskRequestStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean canTransitionTo(AgentTaskRequestStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case OPEN -> EnumSet.of(ACKNOWLEDGED, CANCELLED).contains(target);
            case ACKNOWLEDGED -> EnumSet.of(RESOLVED, REJECTED).contains(target);
            case RESOLVED, REJECTED, CANCELLED -> false;
        };
    }

    public static AgentTaskRequestStatus fromPersistedValue(String value) {
        AgentTaskRequestStatus status = BY_VALUE.get(value);
        if (status == null) {
            throw new IllegalArgumentException("Unknown persisted request status");
        }
        return status;
    }
}
