package cn.jia.agent.entity.funding;

import java.util.List;

/** Immutable successful GROSS_INCLUSIVE settlement; every numeric wire value is canonical text. */
public record AgentTaskSettlementReceiptDTO(String taskId, String status, String quoteId, String agentId,
        String escrowId, String grossBountyAmountMicro, String actualComputeMicro, String platformFeeMicro,
        String agentPayoutMicro, String refundedMicro, String remainingMicro, String taskVersion,
        String fundingVersion, String escrowVersion, String settledAt, List<String> transactionIds) {
    public AgentTaskSettlementReceiptDTO { transactionIds = List.copyOf(transactionIds); }
}
