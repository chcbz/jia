package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskContextPackDTO;

public interface AgentTaskContextPackService {
    /**
     * Trusted in-process entry point; caller must establish actor identity before invoking.
     * HTTP binds actor to exact authenticated JWT sub/name, never a request-nominated actor.
     * Ownership/member/visibility ACL still applies inside the generator's collaborators.
     */
    AgentTaskContextPackDTO generate(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String expectedVersion);
}
