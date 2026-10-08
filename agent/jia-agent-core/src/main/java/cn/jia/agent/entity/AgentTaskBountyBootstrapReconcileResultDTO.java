package cn.jia.agent.entity;

/** Durable state after fenced bootstrap reconciliation. */
public record AgentTaskBountyBootstrapReconcileResultDTO(
        String bootstrapId,
        String status,
        long outboxVersion,
        String conversationId,
        String initialRequestId) {
}
