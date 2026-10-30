package cn.jia.core.security;

/** One configured business partition per process; identity tables do not own tenants. */
public final class FixedTenantAdmissionPolicy {
    private final String tenantId;
    private final String clientId;

    /** A null client admits registered clients in this tenant; a configured client narrows admission. */
    public FixedTenantAdmissionPolicy(String tenantId, String clientId) {
        this.tenantId = TenantClaimPolicy.exact(tenantId);
        this.clientId = clientId == null ? null : TenantClaimPolicy.exact(clientId);
    }

    /** Business partition is process configuration, never an OAuth registry attribute. */
    public String tenantForClient(String registeredClientId) {
        String exactClient = TenantClaimPolicy.exact(registeredClientId);
        if (clientId != null && !clientId.equals(exactClient)) throw notAdmitted();
        return tenantId;
    }

    public String businessTenantId() { return tenantId; }
    public String clientId() { return clientId; }

    /** External scope is an admission assertion, never an override of the process partition. */
    public void requireAdmitted(String claimedTenantId, String claimedClientId) {
        if (!tenantId.equals(TenantClaimPolicy.exact(claimedTenantId))) throw notAdmitted();
        tenantForClient(claimedClientId);
    }

    private static IllegalArgumentException notAdmitted() {
        return new IllegalArgumentException("Tenant/client scope is not admitted");
    }
}
