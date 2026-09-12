package cn.jia.agent.service;

import cn.jia.agent.entity.AgentWorkItemPlanConfirmRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanSuggestRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanViewDTO;

public interface AgentWorkItemPlanService {
    AgentWorkItemPlanViewDTO suggest(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentWorkItemPlanSuggestRequestDTO request);

    AgentWorkItemPlanViewDTO confirm(
            String tenantId, String clientId, String taskId, String actorAgentId,
            String idempotencyKey, AgentWorkItemPlanConfirmRequestDTO request);
}
