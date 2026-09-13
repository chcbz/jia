package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskArtifactContentDTO;

/** ACL-protected internal content read boundary; no public download route is implied. */
public interface AgentTaskArtifactContentService {
    AgentTaskArtifactContentDTO readContent(String tenantId, String clientId, String taskId,
            String actorAgentId, String artifactId, int artifactVersion);
}
