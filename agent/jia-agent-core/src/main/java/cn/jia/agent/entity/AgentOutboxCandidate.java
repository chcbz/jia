package cn.jia.agent.entity;

/** Unlocked bounded discovery hint; every field is revalidated under ordered row locks. */
public record AgentOutboxCandidate(
        long outboxId,
        String tenantId,
        String dataDomainId,
        String executionPoolId,
        long admissionEpoch,
        String producerDeploymentId,
        String moduleId,
        long migrationEpoch,
        int protocolVersion,
        String clientId,
        long deliveryId,
        long eligibleAt,
        long outboxVersion,
        String outboxStatus) {

    /** Legacy construction remains source-compatible but is never a valid v2 candidate. */
    public AgentOutboxCandidate(long outboxId, String tenantId, String clientId,
            long deliveryId, long eligibleAt, long outboxVersion, String outboxStatus) {
        this(outboxId, tenantId, null, null, -1, null, null, -1, 1,
                clientId, deliveryId, eligibleAt, outboxVersion, outboxStatus);
    }
}
