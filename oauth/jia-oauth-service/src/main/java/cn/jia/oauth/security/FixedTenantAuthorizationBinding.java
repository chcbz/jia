package cn.jia.oauth.security;

import cn.jia.core.tenant.TenantId;

import java.util.Objects;

/** Server-selected fixed-tenant binding persisted with one OAuth authorization. */
public record FixedTenantAuthorizationBinding(TenantId tenantId, String clientId) {
    public FixedTenantAuthorizationBinding {
        Objects.requireNonNull(tenantId, "tenantId");
        if (clientId == null || clientId.isBlank() || !clientId.equals(clientId.strip())) {
            throw new IllegalArgumentException("clientId must be exact and non-blank");
        }
    }

    public static String attributeName() {
        return FixedTenantAuthorizationBinding.class.getName();
    }
}
