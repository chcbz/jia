package cn.jia.agent.entity;

import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded transport disposition. lastError is a non-sensitive ASCII reason code, never payload text. */
public record AgentInboxDisposition(Type type, Long nextRetryAt, String errorCode) {
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_.:-]{0,127}");

    public enum Type { SENT, WAITING_AGENT, RETRY, FAILED, EXPIRED, DEAD }

    public AgentInboxDisposition {
        Objects.requireNonNull(type, "type");
        if (type == Type.SENT) {
            if (nextRetryAt != null || errorCode != null) {
                throw new IllegalArgumentException("SENT cannot carry retry or error data");
            }
        } else {
            if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
                throw new IllegalArgumentException("errorCode must be a bounded ASCII reason code");
            }
            boolean retrying = type == Type.WAITING_AGENT || type == Type.RETRY;
            if (retrying != (nextRetryAt != null)) {
                throw new IllegalArgumentException("Only WAITING_AGENT and RETRY require nextRetryAt");
            }
            if (nextRetryAt != null && nextRetryAt <= 0) {
                throw new IllegalArgumentException("nextRetryAt must be positive");
            }
        }
    }

    public static AgentInboxDisposition sent() {
        return new AgentInboxDisposition(Type.SENT, null, null);
    }
}
