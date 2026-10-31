package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Private byte storage; formats are not a storage capability catalogue. */
@ConfigurationProperties(prefix = "agent.task-artifact-storage")
public record AgentTaskArtifactStorageProperties(boolean enabled, String rootDirectory, Long maxContentBytes) {
    public static final long DEFAULT_MAX_CONTENT_BYTES = 16L * 1024L * 1024L;
    public AgentTaskArtifactStorageProperties {
        maxContentBytes = maxContentBytes == null ? DEFAULT_MAX_CONTENT_BYTES : maxContentBytes;
    }
}
