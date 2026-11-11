package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

/** Operator routes, never a user-supplied URL. SSL material belongs to Spring SSL bundles. */
@ConfigurationProperties(prefix = "agent.hosting-rent.control")
public record HostingControlRemoteProperties(String localHostId, Map<String, Endpoint> remotes) {
    public HostingControlRemoteProperties { remotes = remotes == null ? Map.of() : Map.copyOf(remotes); }
    public record Endpoint(String address, int port, String sslBundle, String tenantId, String clientId, String ownerJiacn) {
        public boolean configured() {
            return text(address) && port > 0 && port <= 65535 && text(sslBundle)
                    && text(tenantId) && text(clientId) && text(ownerJiacn);
        }
        public boolean allows(String tenant, String client, String owner) {
            return configured() && tenantId.equals(tenant) && clientId.equals(client)
                    && text(owner) && !"*".equals(owner) && ("*".equals(ownerJiacn) || ownerJiacn.equals(owner));
        }
    }
    private static boolean text(String value) { return value != null && !value.isBlank() && value.equals(value.strip()); }
}
