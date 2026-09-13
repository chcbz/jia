package cn.jia.agent.entity;

import java.util.List;

/** Bounded scanner result. Cursor and deferral observations are not mutation fences or authority. */
public record AgentCommandReissueScanResult(
        int examined,
        int reissued,
        long lastVisitedDeliveryId,
        List<AgentCommandRecoveryDeferral> deferrals) {
    public AgentCommandReissueScanResult(int examined, int reissued, long lastVisitedDeliveryId) {
        this(examined, reissued, lastVisitedDeliveryId, List.of());
    }

    public AgentCommandReissueScanResult {
        deferrals = List.copyOf(deferrals);
        if (deferrals.size() > 100 || deferrals.size() > examined) {
            throw new IllegalArgumentException("recovery deferral observations exceed scan bound");
        }
    }
}
