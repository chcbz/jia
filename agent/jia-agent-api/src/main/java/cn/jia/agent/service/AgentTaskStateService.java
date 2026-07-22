package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskMemberWorkItemStateDTO;
import cn.jia.agent.entity.AgentTaskStateDTO;
import cn.jia.agent.entity.AgentTaskStateTransitionDTO;

public interface AgentTaskStateService {
    AgentTaskStateDTO transitionTask(String tenantId, String clientId, String taskId,
            AgentTaskStateTransitionDTO transition);

    AgentTaskStateDTO transitionMember(String tenantId, String clientId, String taskId, String agentId,
            AgentTaskStateTransitionDTO transition);

    AgentTaskStateDTO transitionWorkItem(String tenantId, String clientId, String workItemId,
            AgentTaskStateTransitionDTO transition);

    AgentTaskMemberWorkItemStateDTO transitionMemberAndWorkItem(
            String tenantId, String clientId, String taskId, String agentId, String workItemId,
            AgentTaskStateTransitionDTO memberTransition,
            AgentTaskStateTransitionDTO workItemTransition);
}
