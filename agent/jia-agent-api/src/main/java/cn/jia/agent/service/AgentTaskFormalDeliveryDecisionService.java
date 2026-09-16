package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryDecisionDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;

/** Task-owner acceptance or explicit rework request for an R2 formal delivery. */
public interface AgentTaskFormalDeliveryDecisionService {
    AgentTaskFormalDeliveryViewDTO decide(
            String tenantId, String clientId, String taskId, String ownerJiacn,
            AgentTaskFormalDeliveryDecisionDTO command);
}
