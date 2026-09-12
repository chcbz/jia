package cn.jia.core.redis;

import cn.jia.core.deadline.RequestDeadlinePropagation;
import cn.jia.core.deadline.SafeRequestTimeoutException;

/**
 * Bounds Redis work to the common request deadline before a command, connection acquisition, or subscription setup
 * begins. The client-wide Redis timeout is intentionally fixed to this value, so a reduced residual budget is
 * rejected before work instead of allowing a configured Redis wait to outlive the request.
 */
final class RedisRequestBudget {
    static final long COMMAND_TIMEOUT_MILLIS = 1_500L;
    static final long SAFETY_MARGIN_MILLIS = 100L;

    private RedisRequestBudget() {
    }

    static long requireCommandBudget() {
        long bounded = RequestDeadlinePropagation.requireBudgetBeforeNewWork(
                COMMAND_TIMEOUT_MILLIS,
                SAFETY_MARGIN_MILLIS,
                SafeRequestTimeoutException.Dependency.REDIS);
        if (bounded < COMMAND_TIMEOUT_MILLIS) {
            throw SafeRequestTimeoutException.deadlineBeforeWork(SafeRequestTimeoutException.Dependency.REDIS);
        }
        return bounded;
    }
}
