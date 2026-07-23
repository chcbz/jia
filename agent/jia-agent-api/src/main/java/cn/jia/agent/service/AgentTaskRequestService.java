package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskRequestCreateDTO;
import cn.jia.agent.entity.AgentTaskRequestQueryDTO;
import cn.jia.agent.entity.AgentTaskRequestTransitionDTO;
import cn.jia.agent.entity.AgentTaskRequestViewDTO;

import java.util.List;

public interface AgentTaskRequestService {
    AgentTaskRequestViewDTO create(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskRequestCreateDTO command);

    AgentTaskRequestViewDTO get(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId);

    List<AgentTaskRequestViewDTO> list(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskRequestQueryDTO query);

    AgentTaskRequestViewDTO acknowledge(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command);

    AgentTaskRequestViewDTO resolve(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command);

    AgentTaskRequestViewDTO reject(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command);

    AgentTaskRequestViewDTO cancel(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command);
}
