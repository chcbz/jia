package cn.jia.core.deadline;

import java.io.Serial;
import java.util.Objects;

/**
 * A bounded failure that may only be raised before durable work starts or when the caller guarantees cancellation is
 * safe. It intentionally has no unknown-write-state variant and carries no provider message or response body.
 */
public final class SafeRequestTimeoutException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Failure failure;
    private final Dependency dependency;
    private final WorkState workState;
    private final boolean retryable;

    private SafeRequestTimeoutException(Failure failure, Dependency dependency, WorkState workState) {
        super(failure.safeMessage());
        this.failure = Objects.requireNonNull(failure, "failure");
        this.dependency = Objects.requireNonNull(dependency, "dependency");
        this.workState = Objects.requireNonNull(workState, "workState");
        this.retryable = false;
    }

    public static SafeRequestTimeoutException deadlineBeforeWork(Dependency dependency) {
        return new SafeRequestTimeoutException(Failure.REQUEST_DEADLINE_EXCEEDED, dependency, WorkState.NOT_STARTED);
    }

    public static SafeRequestTimeoutException dependencyTimedOutSafely(Dependency dependency) {
        return new SafeRequestTimeoutException(Failure.DEPENDENCY_TIMEOUT, dependency, WorkState.SAFE_TO_CANCEL);
    }

    public static SafeRequestTimeoutException dependencyUnavailableBeforeWork(Dependency dependency) {
        return new SafeRequestTimeoutException(Failure.DEPENDENCY_UNAVAILABLE, dependency, WorkState.NOT_STARTED);
    }

    public Failure failure() {
        return failure;
    }

    public Dependency dependency() {
        return dependency;
    }

    public WorkState workState() {
        return workState;
    }

    /** Fail-closed default: callers must not infer that a write is safe to retry from a timeout classification. */
    public boolean retryable() {
        return retryable;
    }

    public enum WorkState {
        NOT_STARTED,
        SAFE_TO_CANCEL
    }

    public enum Dependency {
        NONE,
        DATABASE,
        REDIS,
        LDAP,
        ELASTICSEARCH,
        RABBITMQ,
        HTTP,
        AI,
        SMS,
        WECHAT,
        UNKNOWN
    }

    public enum Failure {
        REQUEST_DEADLINE_EXCEEDED(504, "E504", "请求处理超时"),
        DEPENDENCY_TIMEOUT(504, "E504_DEPENDENCY", "依赖调用超时"),
        DEPENDENCY_UNAVAILABLE(503, "E503_DEPENDENCY", "依赖服务不可用");

        private final int httpStatus;
        private final String code;
        private final String safeMessage;

        Failure(int httpStatus, String code, String safeMessage) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.safeMessage = safeMessage;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String code() {
            return code;
        }

        public String safeMessage() {
            return safeMessage;
        }
    }
}
