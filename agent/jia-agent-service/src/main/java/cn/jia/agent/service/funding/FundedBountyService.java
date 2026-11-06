package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingDTO;
import java.util.List;

/** Historical projections only; current tasks use TaskBillingService. */
public interface FundedBountyService {
    AgentTaskFundingDTO findFunding(String tenantId, String clientId, String taskId);
    List<AgentSkillRequirementDTO> requiredSkills(String tenantId, String clientId, String taskId);
}
