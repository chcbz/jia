package cn.jia.chat.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "archive.reader")
public class ArchiveReaderProperties {
    private boolean enabled;
    private List<AllowedScope> allowedScopes = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<AllowedScope> getAllowedScopes() {
        return allowedScopes;
    }

    public void setAllowedScopes(List<AllowedScope> allowedScopes) {
        this.allowedScopes = allowedScopes == null ? new ArrayList<>() : allowedScopes;
    }

    public static class AllowedScope {
        private String tenantId;
        private String clientId;

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }
    }
}
