package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentTaskFundingCompleteDTO;
import cn.jia.agent.entity.funding.AgentTaskSettlementDTO;
import cn.jia.agent.entity.funding.AgentTaskSettlementReceiptDTO;

public interface FundedBountySettlementService {
    AgentTaskSettlementReceiptDTO complete(FundedBountyActor actor, String key, String taskId,
            AgentTaskFundingCompleteDTO request);
    AgentTaskSettlementDTO read(FundedBountyActor actor, String taskId);
}
