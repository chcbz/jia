package cn.jia.agent.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded durable metric snapshot; maps contain only frozen status labels. */
public record AgentCommandOpsMetrics(
        Map<String, Long> deliveryByStatus,
        Map<String, Long> inboxByStatus,
        Map<String, Long> inboxByResult,
        Map<String, Long> outboxByStatus,
        double ackLatencySeconds,
        long outboxBacklog,
        long outboxOldestAgeSeconds,
        long publishFailureTotal,
        long rabbitDlqCount,
        long waitingDueCount,
        long sentUnacknowledgedCount,
        long reconnectQueueDepth,
        long expiryProximityCount,
        Map<String, Long> operationsByTypeAndOutcome,
        long capturedAt) {
    public AgentCommandOpsMetrics {
        deliveryByStatus = immutableOrdered(deliveryByStatus);
        inboxByStatus = immutableOrdered(inboxByStatus);
        inboxByResult = immutableOrdered(inboxByResult);
        outboxByStatus = immutableOrdered(outboxByStatus);
        operationsByTypeAndOutcome = immutableOrdered(operationsByTypeAndOutcome);
    }

    private static Map<String, Long> immutableOrdered(Map<String, Long> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
