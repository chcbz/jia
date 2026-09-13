package cn.jia.agent.state;

import java.util.Locale;

/** Persisted F06 artifact outcome states. Absence of a row means draft. */
public enum AgentTaskArtifactOutcomeState {
    ACCEPTED("accepted"),
    SUPERSEDED("superseded");

    private final String value;

    AgentTaskArtifactOutcomeState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AgentTaskArtifactOutcomeState fromPersistedValue(String value) {
        if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unknown artifact outcome state: " + value);
        }
        for (AgentTaskArtifactOutcomeState state : values()) {
            if (state.value.equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown artifact outcome state: " + value);
    }
}
