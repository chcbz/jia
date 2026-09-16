package cn.jia.agent.state;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * R2 formal-delivery lifecycle. This is deliberately distinct from an artifact's
 * accepted/superseded state: a shared artifact is not a submitted bounty delivery.
 */
public enum AgentTaskFormalDeliveryState {
    SUBMITTED("submitted"),
    ACCEPTED("accepted"),
    CHANGES_REQUESTED("changes_requested");

    private static final Map<String, AgentTaskFormalDeliveryState> BY_VALUE =
            java.util.Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(
                    AgentTaskFormalDeliveryState::value, Function.identity()));

    private final String value;

    AgentTaskFormalDeliveryState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean canTransitionTo(AgentTaskFormalDeliveryState target) {
        return this == SUBMITTED && (target == ACCEPTED || target == CHANGES_REQUESTED);
    }

    public static AgentTaskFormalDeliveryState fromPersistedValue(String value) {
        AgentTaskFormalDeliveryState state = BY_VALUE.get(value);
        if (state == null) {
            throw new IllegalArgumentException("Unknown formal delivery state");
        }
        return state;
    }
}
