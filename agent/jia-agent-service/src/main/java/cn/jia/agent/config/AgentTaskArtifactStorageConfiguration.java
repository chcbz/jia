package cn.jia.agent.config;

import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.impl.DisabledAgentTaskArtifactStorage;
import cn.jia.agent.service.impl.FileSystemAgentTaskArtifactStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.LinkedHashSet;

/** Registers either the explicit private file store or a fail-closed disabled implementation. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentTaskArtifactStorageProperties.class)
public class AgentTaskArtifactStorageConfiguration {
    @Bean
    public AgentTaskArtifactStorage agentTaskArtifactStorage(
            AgentTaskArtifactStorageProperties properties) {
        if (!properties.enabled()) {
            return new DisabledAgentTaskArtifactStorage();
        }
        if (properties.rootDirectory() == null || properties.rootDirectory().isBlank()) {
            throw new IllegalStateException(
                    "Invalid agent.task-artifact-storage configuration: root-directory is required");
        }
        try {
            return new FileSystemAgentTaskArtifactStorage(
                    Path.of(properties.rootDirectory()), properties.maxContentBytes(),
                    new LinkedHashSet<>(properties.allowedMimeTypes()));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Invalid agent.task-artifact-storage configuration", failure);
        }
    }
}
