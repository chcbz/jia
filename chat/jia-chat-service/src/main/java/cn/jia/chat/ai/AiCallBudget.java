package cn.jia.chat.ai;

import java.time.Duration;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.SafeRequestTimeoutException;

/** A single invocation's immutable phase budgets, capped by its captured request deadline. */
public record AiCallBudget(Duration connect, Duration firstToken, Duration total) {
    public AiCallBudget {
        if (connect == null || firstToken == null || total == null
                || connect.isZero() || connect.isNegative()
                || firstToken.isZero() || firstToken.isNegative()
                || total.isZero() || total.isNegative()
                || connect.compareTo(firstToken) > 0
                || firstToken.compareTo(total) > 0) {
            throw new IllegalArgumentException("AI call budgets must be positive and ordered");
        }
    }

    static AiCallBudget resolve(AiProviderProperties properties, RequestDeadline requestDeadline) {
        properties.validate();
        long connectMillis = bounded(properties.getConnectBudget(), properties, requestDeadline);
        long firstTokenMillis = bounded(properties.getFirstTokenBudget(), properties, requestDeadline);
        long totalMillis = bounded(properties.getTotalBudget(), properties, requestDeadline);
        if (connectMillis <= 0 || firstTokenMillis <= 0 || totalMillis <= 0) {
            throw SafeRequestTimeoutException.deadlineBeforeWork(SafeRequestTimeoutException.Dependency.AI);
        }
        firstTokenMillis = Math.min(firstTokenMillis, totalMillis);
        connectMillis = Math.min(connectMillis, firstTokenMillis);
        return new AiCallBudget(Duration.ofMillis(connectMillis), Duration.ofMillis(firstTokenMillis),
                Duration.ofMillis(totalMillis));
    }

    private static long bounded(Duration requested, AiProviderProperties properties,
            RequestDeadline requestDeadline) {
        long requestedMillis = requested.toMillis();
        if (requestDeadline == null) {
            return requestedMillis;
        }
        return requestDeadline.boundedBudgetMillis(requestedMillis, properties.getSafetyMargin().toMillis());
    }
}
