package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskArtifactContentDTO;

/** ACL-protected content boundary scoped by the exact task owner. */
public interface AgentTaskArtifactContentService {
    AgentTaskArtifactContentDTO readContent(String tenantId, String clientId, String ownerJiacn,
            String taskId, String actorAgentId, String artifactId, int artifactVersion);

    /** Browser task-owner read after JWT ownership has been authenticated by the adapter. */
    AgentTaskArtifactContentDTO readContentForTaskOwner(String tenantId, String clientId,
            String ownerJiacn, String taskId, String artifactId, int artifactVersion);
    /** Owner-authorized, verified artifact descriptor. Caller must close it. */
    StreamContent openContentForTaskOwner(String tenantId,String clientId,String ownerJiacn,
            String taskId,String artifactId,int artifactVersion);
    record StreamContent(String artifactId,int artifactVersion,String sha256,long byteLength,
            String contentMimeType,java.io.InputStream content) implements AutoCloseable {
        @Override public void close() throws java.io.IOException {content.close();}
    }
}
