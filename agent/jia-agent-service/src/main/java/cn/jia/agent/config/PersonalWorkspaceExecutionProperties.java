package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Explicit runtime delivery allow-list. Upload support never implicitly opens execution. */
@ConfigurationProperties(prefix = "agent.personal-workspace-execution")
public record PersonalWorkspaceExecutionProperties(List<String> allowedMimeTypes) {
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public PersonalWorkspaceExecutionProperties {
        allowedMimeTypes = allowedMimeTypes == null ? List.of(DOCX) : List.copyOf(allowedMimeTypes);
    }
}
