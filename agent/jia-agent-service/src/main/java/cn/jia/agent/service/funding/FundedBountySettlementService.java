package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentTaskSettlementDTO;

/** Read-only original settlement/refund facts, never a settlement command. */
public interface FundedBountySettlementService {
    AgentTaskSettlementDTO read(FundedBountyActor actor, String taskId);
}
