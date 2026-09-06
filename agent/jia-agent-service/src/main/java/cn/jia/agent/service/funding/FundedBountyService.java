package cn.jia.agent.service.funding;

import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingCancelReceiptDTO;
import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingDTO;

import java.util.List;

public interface FundedBountyService {
    AgentTaskDTO create(FundedBountyActor actor, String idempotencyKey, byte[] requestHash,
            AgentTaskCreateDTO request);

    AgentTaskFundingCancelReceiptDTO cancel(FundedBountyActor actor, String idempotencyKey,
            byte[] requestHash, String taskId, long expectedTaskVersion);

    AgentTaskFundingDTO findFunding(String tenantId, String clientId, String taskId);

    List<AgentSkillRequirementDTO> requiredSkills(String tenantId, String clientId, String taskId);

    void requireLegacyAssignmentAllowed(String tenantId, String clientId, String taskId,
            boolean automatic, int targetCount);

    void requireLegacyLifecycleAllowed(String tenantId, String clientId, String taskId);
}
