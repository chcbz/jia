package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryDecisionDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;

/** Task-owner acceptance or explicit rework request for an R2 formal delivery. */
public interface AgentTaskFormalDeliveryDecisionService {
    AgentTaskFormalDeliveryViewDTO decide(
            String tenantId, String clientId, String taskId, String ownerJiacn,
            AgentTaskFormalDeliveryDecisionDTO command);
    /** Internal scheduler use only: caller proves the expired relationship under the task root.
     * No HTTP endpoint accepts this authority; settlement and events use the normal decision transaction. */
    AgentTaskFormalDeliveryViewDTO acceptForHostingExpiry(
            String tenantId, String clientId, String taskId, String ownerJiacn,
            AgentTaskFormalDeliveryDecisionDTO command);
}
