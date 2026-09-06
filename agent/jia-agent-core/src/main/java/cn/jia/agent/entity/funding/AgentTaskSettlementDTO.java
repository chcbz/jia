package cn.jia.agent.entity.funding;

/** Held/refunded reads do not manufacture compute or payout amounts. Receipt exists only after capture. */
public record AgentTaskSettlementDTO(String taskId, String status, String settlementPolicy,
        String escrowId, String grossBountyAmountMicro, String remainingMicro, String taskVersion,
        String fundingVersion, AgentTaskSettlementReceiptDTO settlement,
        AgentTaskFundingCancelReceiptDTO cancellation) { }
