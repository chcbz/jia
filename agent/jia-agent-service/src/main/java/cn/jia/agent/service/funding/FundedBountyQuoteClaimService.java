package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentTaskClaimReceiptDTO;
import cn.jia.agent.entity.funding.AgentTaskClaimRequestDTO;
import cn.jia.agent.entity.funding.AgentTaskQuoteDTO;
import cn.jia.agent.entity.funding.AgentTaskQuoteRequestDTO;

public interface FundedBountyQuoteClaimService {
    AgentTaskQuoteDTO quote(FundedBountyActor actor, String idempotencyKey, byte[] requestHash,
            String taskId, AgentTaskQuoteRequestDTO request);

    AgentTaskClaimReceiptDTO claim(FundedBountyActor actor, String idempotencyKey, byte[] requestHash,
            String taskId, AgentTaskClaimRequestDTO request);
}
