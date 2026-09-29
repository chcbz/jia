package cn.jia.agent.entity;

/** Fenced Chat-side result for a claimed bootstrap intent. */
public record AgentTaskBountyBootstrapReconcileDTO(
        String bootstrapId,
        long expectedOutboxVersion,
        String leaseOwner,
        int claimAttempt,
        Outcome outcome,
        String conversationId,
        String initialRequestId,
        String errorCode) {

    public enum Outcome {
        /** Chat durably admitted the unique discussion/request; this is not Agent delivery. */
        ADMITTED,
        RETRYABLE_FAILURE,
        TERMINAL_FAILURE
    }
}
