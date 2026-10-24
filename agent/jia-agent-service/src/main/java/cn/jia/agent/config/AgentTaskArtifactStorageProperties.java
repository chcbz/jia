package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Private immutable byte storage; format support is owned by preview and execution adapters. */
@ConfigurationProperties(prefix = "agent.task-artifact-storage")
public record AgentTaskArtifactStorageProperties(boolean enabled, String rootDirectory) { }
