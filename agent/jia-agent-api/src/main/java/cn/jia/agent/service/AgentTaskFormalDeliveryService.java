package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskFormalDeliverySubmitDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;

/**
 * Single-agent, single-required-work-item formal-delivery transaction.
 * Artifact publication remains a separate prerequisite and this API never accepts an artifact.
 */
public interface AgentTaskFormalDeliveryService {
    AgentTaskFormalDeliveryViewDTO submit(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskFormalDeliverySubmitDTO command);
}
