package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskArtifactContentDTO;

/** ACL-protected content boundary scoped by the exact task owner. */
public interface AgentTaskArtifactContentService {
    @Deprecated(forRemoval = true)
    default AgentTaskArtifactContentDTO readContent(String tenantId, String clientId, String taskId,
            String actorAgentId, String artifactId, int artifactVersion) {
        throw new UnsupportedOperationException("strict task owner scope is required");
    }

    AgentTaskArtifactContentDTO readContent(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String artifactId, int artifactVersion);
}
