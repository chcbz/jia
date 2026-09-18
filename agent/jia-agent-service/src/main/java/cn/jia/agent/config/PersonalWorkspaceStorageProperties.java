package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/** Disabled by default: deployment must select a private root and explicit supported MIME set. */
@ConfigurationProperties(prefix = "agent.personal-workspace-storage")
public record PersonalWorkspaceStorageProperties(boolean enabled, String rootDirectory,
        Long maxContentBytes, List<String> allowedMimeTypes) {
    public PersonalWorkspaceStorageProperties {
        allowedMimeTypes = allowedMimeTypes == null ? List.of() : List.copyOf(allowedMimeTypes);
    }
}
