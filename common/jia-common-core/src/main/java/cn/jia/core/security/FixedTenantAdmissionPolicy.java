package cn.jia.core.security;

/**
 * Narrow fixed-process admission policy. Disabled mode preserves the legacy tenant-zero
 * deployment. Enabled mode admits only the configured tenant/client pair; it never accepts
 * a request/header tenant override and never keeps a tenant-zero side lane open.
 */
public final class FixedTenantAdmissionPolicy {
    private final String legacyTenantId;
    private final boolean enabled;
    private final String tenantId;
    private final String clientId;

    public FixedTenantAdmissionPolicy(String legacyTenantId, boolean enabled,
            String tenantId, String clientId) {
        this.legacyTenantId = TenantClaimPolicy.exact(legacyTenantId);
        this.enabled = enabled;
        if (enabled) {
            this.tenantId = TenantClaimPolicy.exact(tenantId);
            this.clientId = TenantClaimPolicy.exact(clientId);
            if (this.legacyTenantId.equals(this.tenantId)) {
                throw new IllegalArgumentException("Fixed tenant must be distinct from the legacy tenant");
            }
        } else {
            this.tenantId = null;
            this.clientId = null;
        }
    }

    /** Business partition is process configuration, never an OAuth registry attribute. */
    public String tenantForClient(String registeredClientId) {
        String exactClient = TenantClaimPolicy.exact(registeredClientId);
        String businessTenant = businessTenantId();
        requireAdmitted(businessTenant, exactClient);
        return businessTenant;
    }

    public String businessTenantId() { return enabled ? tenantId : legacyTenantId; }

    public void requireAdmitted(String claimedTenantId, String claimedClientId) {
        String exactTenant = TenantClaimPolicy.exact(claimedTenantId);
        if (!enabled) {
            if (!legacyTenantId.equals(exactTenant)) {
                throw new IllegalArgumentException("Tenant/client scope is not admitted");
            }
            return;
        }
        String exactClient = TenantClaimPolicy.exact(claimedClientId);
        if (!tenantId.equals(exactTenant) || !clientId.equals(exactClient)) {
            throw new IllegalArgumentException("Tenant/client scope is not admitted");
        }
    }

    public boolean enabled() { return enabled; }
    public String tenantId() { return tenantId; }
    public String clientId() { return clientId; }
}
