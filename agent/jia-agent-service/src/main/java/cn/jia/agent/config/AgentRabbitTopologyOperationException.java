package cn.jia.agent.config;

import java.util.Objects;

/**
 * Sanitized D04 operation failure. Broker reply text and the original throwable are deliberately
 * excluded from the exception graph so callers cannot leak connection details or credentials.
 */
public final class AgentRabbitTopologyOperationException extends IllegalStateException {
    private final ErrorCode errorCode;
    private final String failureType;

    private AgentRabbitTopologyOperationException(ErrorCode errorCode, Throwable failure) {
        super(message(errorCode), null);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        failureType = failure == null ? "unknown" : failure.getClass().getName();
    }

    static AgentRabbitTopologyOperationException provisionFailed(Throwable failure) {
        return new AgentRabbitTopologyOperationException(ErrorCode.PROVISION_FAILED, failure);
    }

    static AgentRabbitTopologyOperationException passiveVerifyFailed(Throwable failure) {
        return new AgentRabbitTopologyOperationException(
                ErrorCode.PASSIVE_VERIFY_FAILED, failure);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String failureType() {
        return failureType;
    }

    private static String message(ErrorCode errorCode) {
        return "Agent Rabbit topology operation failed [" + errorCode + "]";
    }

    public enum ErrorCode {
        PROVISION_FAILED,
        PASSIVE_VERIFY_FAILED
    }
}
