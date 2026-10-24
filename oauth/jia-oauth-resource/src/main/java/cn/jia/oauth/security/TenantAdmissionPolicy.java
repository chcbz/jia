package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.security.TenantClaimPolicy;
import cn.jia.oauth.config.ResourceServerConfig.TenantDeploymentProperties;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Server-owned admission mode; request fields never select or replace this policy. */
public final class TenantAdmissionPolicy {
    public enum Mode {
        PLATFORM,
        FIXED_TENANT
    }

    private static final DataDomainId CYF_DOMAIN = DataDomainId.of("cyf");

    private final Mode mode;
    private final TenantId fixedTenantId;
    private final boolean requireBoundIdentity;
    private final Set<TenantId> allowedPlatformTenants;

    private TenantAdmissionPolicy(Mode mode, TenantId fixedTenantId, boolean requireBoundIdentity,
                                  Set<TenantId> allowedPlatformTenants) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.fixedTenantId = fixedTenantId;
        this.requireBoundIdentity = requireBoundIdentity;
        this.allowedPlatformTenants = Set.copyOf(allowedPlatformTenants);
    }

    public static TenantAdmissionPolicy from(TenantDeploymentProperties properties) {
        if (properties == null || properties.getAdmissionMode() == null) {
            throw new IllegalStateException("Tenant admission mode is missing");
        }
        List<String> configuredAllowed = properties.getAllowedTenants();
        if (configuredAllowed == null) {
            throw new IllegalStateException("Tenant deployment allowlist is missing");
        }
        Set<TenantId> allowed = canonicalTenants(configuredAllowed);
        if (properties.getAdmissionMode() == Mode.FIXED_TENANT) {
            if (properties.isRequireBoundIdentity() || !allowed.isEmpty()) {
                throw new IllegalStateException(
                        "Fixed-tenant mode cannot be combined with platform deployment switches");
            }
            String configuredTenant = properties.getFixedTenantId();
            try {
                if (configuredTenant == null || configuredTenant.isBlank()
                        || !configuredTenant.equals(configuredTenant.strip())) {
                    throw new IllegalArgumentException("missing or non-exact tenant");
                }
                return new TenantAdmissionPolicy(Mode.FIXED_TENANT, TenantId.of(configuredTenant),
                        false, Set.of());
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Fixed tenant ID must be one canonical tenant", exception);
            }
        }
        if (properties.getFixedTenantId() != null && !properties.getFixedTenantId().isBlank()) {
            throw new IllegalStateException("Platform mode cannot configure a fixed tenant ID");
        }
        if (properties.isRequireBoundIdentity() != !allowed.isEmpty()) {
            throw new IllegalStateException("Tenant allowlist requires strict bound identity mode");
        }
        return new TenantAdmissionPolicy(Mode.PLATFORM, null,
                properties.isRequireBoundIdentity(), allowed);
    }

    static TenantAdmissionPolicy platformValidatorDefault(boolean requireBoundIdentity) {
        return new TenantAdmissionPolicy(Mode.PLATFORM, null, requireBoundIdentity, Set.of());
    }

    public boolean isFixedTenant() {
        return mode == Mode.FIXED_TENANT;
    }

    public boolean requiresIsolatedResourceLane() {
        return isFixedTenant() || requireBoundIdentity;
    }

    public boolean requiresCentralTrust() {
        return requiresIsolatedResourceLane();
    }

    public boolean requiresTenantAuthorizationVerifierAtStartup() {
        return mode == Mode.PLATFORM && requireBoundIdentity;
    }

    public boolean allowsLegacyUser() {
        return mode == Mode.PLATFORM && !requireBoundIdentity;
    }

    public DataDomainId fixedDataDomainId() {
        if (!isFixedTenant()) throw new IllegalStateException("Fixed tenant mode is required");
        return CYF_DOMAIN;
    }

    public TenantId requireFixedUserTenant(Jwt jwt) {
        if (!isFixedTenant()) throw new IllegalStateException("Fixed tenant mode is required");
        AccountSecurityJwtClaims identity = AccountSecurityJwtClaims.parse(jwt);
        if (identity.tokenClass() != AccountSecurityJwtClaims.TokenClass.USER) {
            throw new IllegalArgumentException("A fixed-tenant user token is required");
        }
        Object canonicalClaim = jwt.getClaims().get(TenantClaimPolicy.CANONICAL_CLAIM);
        if (!(canonicalClaim instanceof String canonicalText)) {
            throw new IllegalArgumentException("Canonical tenant_id claim is required");
        }
        TenantId canonicalTenant = TenantId.of(canonicalText);
        TenantId claimed = AccountSecurityJwtClaims.tenantId(jwt)
                .orElseThrow(() -> new IllegalArgumentException("Tenant claim is required"));
        if (!canonicalTenant.equals(claimed) || !fixedTenantId.equals(canonicalTenant)) {
            throw new IllegalArgumentException("Token tenant is not admitted by this deployment");
        }
        var platformBinding = AccountSecurityJwtClaims.tenantBinding(jwt);
        if (platformBinding.isPresent() && !CYF_DOMAIN.equals(platformBinding.get().dataDomainId())) {
            throw new IllegalArgumentException("Token data domain conflicts with the fixed deployment");
        }
        return claimed;
    }

    public boolean isCurrentUserAuthorization(Jwt jwt, AccountSecurityJwtClaims identity,
                                              TenantAuthorizationVerifier verifier) {
        if (identity.tokenClass() != AccountSecurityJwtClaims.TokenClass.USER) return false;
        if (isFixedTenant()) {
            return fixedTenantId.equals(requireFixedUserTenant(jwt));
        }
        var bound = AccountSecurityJwtClaims.tenantBinding(jwt);
        return bound.isPresent() && verifier != null
                && verifier.isCurrent(bound.get(), identity.userId(), jwt.getClaim("client_id"));
    }

    public boolean admitsDeploymentToken(Jwt jwt) {
        if (isFixedTenant()) {
            requireFixedUserTenant(jwt);
            return true;
        }
        if (!requireBoundIdentity) return true;
        AccountSecurityJwtClaims identity = AccountSecurityJwtClaims.parse(jwt);
        var binding = AccountSecurityJwtClaims.tenantBinding(jwt);
        return identity.tokenClass() == AccountSecurityJwtClaims.TokenClass.USER
                && binding.isPresent() && allowedPlatformTenants.contains(binding.get().tenantId());
    }

    private static Set<TenantId> canonicalTenants(List<String> configured) {
        Set<TenantId> result = new LinkedHashSet<>();
        for (String value : configured) {
            try {
                TenantId tenant = TenantId.of(value);
                if (!result.add(tenant)) throw new IllegalArgumentException("duplicate tenant");
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Invalid tenant deployment allowlist", exception);
            }
        }
        return result;
    }
}
