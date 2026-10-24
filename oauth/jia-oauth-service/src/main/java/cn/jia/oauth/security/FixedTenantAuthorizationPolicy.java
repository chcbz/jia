package cn.jia.oauth.security;

import cn.jia.core.tenant.TenantId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Trusted server configuration mapping exact OAuth client IDs to a fixed tenant. */
public final class FixedTenantAuthorizationPolicy {
    private final TenantIssuanceProperties.Mode mode;
    private final TenantId fixedTenantId;
    private final Map<String, TenantId> clientTenants;

    private FixedTenantAuthorizationPolicy(TenantIssuanceProperties.Mode mode, TenantId fixedTenantId,
            Map<String, TenantId> clientTenants) {
        this.mode = mode;
        this.fixedTenantId = fixedTenantId;
        this.clientTenants = Map.copyOf(clientTenants);
    }

    public static FixedTenantAuthorizationPolicy from(TenantIssuanceProperties properties) {
        return from(properties, true);
    }

    public static FixedTenantAuthorizationPolicy from(TenantIssuanceProperties properties,
            boolean authorizationServerEnabled) {
        Objects.requireNonNull(properties, "properties");
        if (!authorizationServerEnabled) {
            return new FixedTenantAuthorizationPolicy(TenantIssuanceProperties.Mode.PLATFORM, null, Map.of());
        }
        if (properties.getAdmissionMode() == null || properties.getFixedClientTenants() == null
                || properties.getAllowedTenants() == null) {
            throw new IllegalStateException("Tenant issuance configuration is incomplete");
        }
        if (properties.getAdmissionMode() == TenantIssuanceProperties.Mode.PLATFORM) {
            if (properties.getFixedTenantId() != null && !properties.getFixedTenantId().isBlank()
                    || !properties.getFixedClientTenants().isEmpty()) {
                throw new IllegalStateException("Platform mode cannot configure fixed tenant issuance");
            }
            return new FixedTenantAuthorizationPolicy(TenantIssuanceProperties.Mode.PLATFORM, null, Map.of());
        }
        if (properties.isRequireBoundIdentity() || !properties.getAllowedTenants().isEmpty()) {
            throw new IllegalStateException("Fixed-tenant mode cannot use platform admission switches");
        }
        TenantId fixed = canonicalTenant(properties.getFixedTenantId());
        if (properties.getFixedClientTenants().isEmpty()) {
            throw new IllegalStateException("Fixed-tenant mode requires at least one exact client mapping");
        }
        Map<String, TenantId> mappings = new LinkedHashMap<>();
        properties.getFixedClientTenants().forEach((clientId, tenantText) -> {
            if (clientId == null || clientId.isBlank() || !clientId.equals(clientId.strip())) {
                throw new IllegalStateException("Fixed tenant client IDs must be exact and non-blank");
            }
            TenantId tenant = canonicalTenant(tenantText);
            if (!fixed.equals(tenant) || mappings.put(clientId, tenant) != null) {
                throw new IllegalStateException("Every fixed client mapping must target the configured fixed tenant");
            }
        });
        return new FixedTenantAuthorizationPolicy(TenantIssuanceProperties.Mode.FIXED_TENANT, fixed, mappings);
    }

    public boolean isFixedTenant() {
        return mode == TenantIssuanceProperties.Mode.FIXED_TENANT;
    }

    public TenantId fixedTenantId() {
        if (!isFixedTenant()) throw new IllegalStateException("Fixed tenant mode is required");
        return fixedTenantId;
    }

    public FixedTenantAuthorizationBinding bind(String clientId) {
        TenantId tenant = clientTenants.get(clientId);
        if (tenant == null) throw TenantAuthorizationRequestProvider.invalidRequest();
        return new FixedTenantAuthorizationBinding(tenant, clientId);
    }

    public boolean permits(FixedTenantAuthorizationBinding binding, String clientId) {
        if (!isFixedTenant() || binding == null || !binding.clientId().equals(clientId)) return false;
        TenantId current = clientTenants.get(clientId);
        return current != null && current.equals(binding.tenantId()) && fixedTenantId.equals(binding.tenantId());
    }

    private static TenantId canonicalTenant(String value) {
        try {
            if (value == null || value.isBlank() || !value.equals(value.strip())) {
                throw new IllegalArgumentException("missing or non-exact tenant");
            }
            return TenantId.of(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Fixed tenant IDs must be canonical", exception);
        }
    }
}
