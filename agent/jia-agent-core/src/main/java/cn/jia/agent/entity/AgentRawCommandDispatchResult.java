package cn.jia.agent.entity;

import java.util.Objects;

/** Result of one exact-scope raw WebSocket command dispatch attempt. */
public record AgentRawCommandDispatchResult(
        Status status,
        int matchingSessionCount,
        int sentSessionCount) {

    public enum Status { SENT, OFFLINE, SEND_FAILED, REJECTED }

    public AgentRawCommandDispatchResult {
        Objects.requireNonNull(status, "status");
        if (matchingSessionCount < 0 || sentSessionCount < 0
                || sentSessionCount > matchingSessionCount) {
            throw new IllegalArgumentException("WebSocket dispatch counts are invalid");
        }
        boolean valid = switch (status) {
            case SENT -> matchingSessionCount > 0 && sentSessionCount > 0;
            case OFFLINE -> matchingSessionCount == 0 && sentSessionCount == 0;
            case SEND_FAILED -> matchingSessionCount > 0 && sentSessionCount == 0;
            case REJECTED -> matchingSessionCount == 0 && sentSessionCount == 0;
        };
        if (!valid) {
            throw new IllegalArgumentException("WebSocket dispatch result shape is invalid");
        }
    }

    public static AgentRawCommandDispatchResult sent(int matching, int sent) {
        return new AgentRawCommandDispatchResult(Status.SENT, matching, sent);
    }

    public static AgentRawCommandDispatchResult offline() {
        return new AgentRawCommandDispatchResult(Status.OFFLINE, 0, 0);
    }

    public static AgentRawCommandDispatchResult sendFailed(int matching) {
        return new AgentRawCommandDispatchResult(Status.SEND_FAILED, matching, 0);
    }

    public static AgentRawCommandDispatchResult rejected() {
        return new AgentRawCommandDispatchResult(Status.REJECTED, 0, 0);
    }
}
