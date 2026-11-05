package cn.jia.agent.entity.funding;

import java.util.List;

/** Immutable successful GROSS_INCLUSIVE settlement; every numeric wire value is canonical text. */
public record AgentTaskSettlementReceiptDTO(String taskId, String status, String quoteId, String agentId,
        String escrowId, String grossBountyAmountWen, String actualComputeWen, String platformFeeWen,
        String agentPayoutWen, String refundedWen, String remainingWen, String taskVersion,
        String fundingVersion, String escrowVersion, String settledAt, List<String> transactionIds) {
    public AgentTaskSettlementReceiptDTO { transactionIds = List.copyOf(transactionIds); }
}
