package cn.jia.common.mybatis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "cyf.tenant.mybatis")
public class TenantIsolationProperties {
    private boolean enabled;
    private Set<String> tenantTables = new LinkedHashSet<>();
    private Set<String> globalTables = new LinkedHashSet<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<String> getTenantTables() { return tenantTables; }
    public void setTenantTables(Set<String> tenantTables) {
        this.tenantTables = tenantTables == null ? new LinkedHashSet<>() : tenantTables;
    }
    public Set<String> getGlobalTables() { return globalTables; }
    public void setGlobalTables(Set<String> globalTables) {
        this.globalTables = globalTables == null ? new LinkedHashSet<>() : globalTables;
    }
}
