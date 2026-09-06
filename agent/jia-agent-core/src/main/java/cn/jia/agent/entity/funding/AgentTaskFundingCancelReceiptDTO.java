package cn.jia.agent.entity.funding;

public record AgentTaskFundingCancelReceiptDTO(
        String taskId,
        String fundingStatus,
        String refundTransactionId,
        String refundedMicro,
        String remainingMicro,
        String taskVersion,
        String fundingVersion,
        String refundedAt) {
}
