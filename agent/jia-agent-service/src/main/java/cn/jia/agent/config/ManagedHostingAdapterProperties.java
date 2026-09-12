package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

/** Operator-only private channel. Blank settings and the independent rent switch fail closed. */
@ConfigurationProperties(prefix = "agent.hosting-rent.managed")
public record ManagedHostingAdapterProperties(String socketPath, Long runnerUid, String tenantId,
        String clientId, String ownerJiacn, Integer timeoutMs) {
    public ManagedHostingAdapterProperties { timeoutMs = timeoutMs == null ? 2000 : timeoutMs; }
    public boolean configured() {
        boolean absolute;
        try { absolute = socketPath != null && Path.of(socketPath).isAbsolute() && socketPath.length() <= 100; }
        catch (java.nio.file.InvalidPathException invalid) { return false; }
        return absolute && runnerUid != null && runnerUid >= 0 && tenantId != null && !tenantId.isBlank()
                && clientId != null && !clientId.isBlank() && ownerJiacn != null && !ownerJiacn.isBlank()
                && timeoutMs >= 100 && timeoutMs <= 20000;
    }
}
