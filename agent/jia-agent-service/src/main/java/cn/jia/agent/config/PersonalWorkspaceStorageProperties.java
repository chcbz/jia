package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Private byte storage; format support belongs to preview/read/execute capabilities. */
@ConfigurationProperties(prefix = "agent.personal-workspace-storage")
public record PersonalWorkspaceStorageProperties(boolean enabled, String rootDirectory) { }
