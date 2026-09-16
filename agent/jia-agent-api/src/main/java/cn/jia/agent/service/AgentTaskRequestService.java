package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskRequestCreateDTO;
import cn.jia.agent.entity.AgentTaskRequestQueryDTO;
import cn.jia.agent.entity.AgentTaskRequestTransitionDTO;
import cn.jia.agent.entity.AgentTaskRequestViewDTO;

import java.util.List;

/** Request APIs require the exact authenticated owner of the task. */
public interface AgentTaskRequestService {
    @Deprecated(forRemoval = true)
    default AgentTaskRequestViewDTO create(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskRequestCreateDTO command) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskRequestViewDTO get(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default List<AgentTaskRequestViewDTO> list(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskRequestQueryDTO query) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskRequestViewDTO acknowledge(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskRequestViewDTO resolve(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskRequestViewDTO reject(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskRequestViewDTO cancel(String tenantId, String clientId, String taskId,
            String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command) { throw ownerRequired(); }

    AgentTaskRequestViewDTO create(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, AgentTaskRequestCreateDTO command);

    AgentTaskRequestViewDTO get(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String requestId);

    List<AgentTaskRequestViewDTO> list(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, AgentTaskRequestQueryDTO query);

    AgentTaskRequestViewDTO acknowledge(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command);

    AgentTaskRequestViewDTO resolve(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command);

    AgentTaskRequestViewDTO reject(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command);

    AgentTaskRequestViewDTO cancel(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String requestId, AgentTaskRequestTransitionDTO command);
    private static UnsupportedOperationException ownerRequired() {
        return new UnsupportedOperationException("strict task owner scope is required");
    }
}
