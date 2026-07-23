package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskAggregationCommandDTO;
import cn.jia.agent.entity.AgentTaskAggregationDTO;

/**
 * Internal orchestrator domain service. It is intentionally not exposed by a
 * controller in B05: agents update their own member/work-item state and only
 * this server-side aggregate may derive the task state.
 */
public interface AgentTaskAggregationService {
    AgentTaskAggregationDTO aggregate(
            String tenantId, String clientId, String taskId,
            AgentTaskAggregationCommandDTO command);
}
