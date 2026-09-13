package cn.jia.agent.entity;

import java.util.Set;

/**
 * Payload-free observation from the existing scoped D06 scan, after locked source validation.
 * Not persisted, not an ACK outcome or reassignment authorization. The observed message/version
 * can become stale immediately; a caller must use existing ACL/provenance/CAS entrypoints.
 * policyBoundaryAt is the evaluated backoff/deadline boundary, NOT a promise of a future retry.
 */
public record AgentCommandRecoveryDeferral(
        String tenantId,
        String clientId,
        String taskId,
        String workItemId,
        String targetAgentId,
        long deliveryId,
        String activeMessageId,
        int activeAttempt,
        long deliveryVersion,
        String reason,
        long observedAt,
        long policyBoundaryAt,
        long expiresAt) {
    public AgentCommandRecoveryDeferral {
        if (!exact(tenantId, 50) || !exact(clientId, 50) || !exact(taskId, 100)
                || (workItemId != null && !exact(workItemId, 100))
                || !exact(targetAgentId, 100) || !exact(activeMessageId, 100)
                || deliveryId <= 0 || activeAttempt <= 0 || deliveryVersion < 0
                || reason == null || !Set.of("DEFER", "MANUAL_TAKEOVER_REQUIRED",
                        "DEADLINE_EXHAUSTED").contains(reason)
                || observedAt <= 0 || observedAt >= expiresAt
                || policyBoundaryAt <= 0 || policyBoundaryAt > expiresAt) {
            throw new IllegalArgumentException("invalid recovery deferral observation");
        }
    }

    private static boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
