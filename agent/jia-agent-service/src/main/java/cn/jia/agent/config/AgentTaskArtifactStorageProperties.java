package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Startup-only F02 storage settings. Storage remains disabled when the switch is absent. */
@ConfigurationProperties(prefix = "agent.task-artifact-storage")
public record AgentTaskArtifactStorageProperties(
        boolean enabled,
        String rootDirectory,
        Long maxContentBytes,
        List<String> allowedMimeTypes) {
    public static final long DEFAULT_MAX_CONTENT_BYTES = 16L * 1024L * 1024L;
    public static final List<String> DEFAULT_ALLOWED_MIME_TYPES = List.of(
            "application/gzip",
            "application/json",
            "application/octet-stream",
            "application/pdf",
            "application/zip",
            "image/jpeg",
            "image/png",
            "text/csv",
            "text/markdown",
            "text/plain");

    public AgentTaskArtifactStorageProperties {
        maxContentBytes = maxContentBytes == null
                ? DEFAULT_MAX_CONTENT_BYTES : maxContentBytes;
        allowedMimeTypes = allowedMimeTypes == null
                ? DEFAULT_ALLOWED_MIME_TYPES : List.copyOf(allowedMimeTypes);
    }
}
