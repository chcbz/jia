package cn.jia.agent.entity.funding;

public record AgentTaskFundingCancelReceiptDTO(
        String taskId,
        String fundingStatus,
        String refundTransactionId,
        String refundedWen,
        String remainingWen,
        String taskVersion,
        String fundingVersion,
        String refundedAt) {
}
