package cn.jia.oauth.security;

import cn.jia.core.tenant.TenantId;
import cn.jia.oauth.config.ResourceServerConfig.TenantDeploymentProperties;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashSet;
import java.util.Set;

/** Deployment allowlist is independent of the user's current tenant grant. */
public final class TenantDeploymentTokenValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN);
    private final boolean required;
    private final Set<String> allowed;

    public TenantDeploymentTokenValidator(TenantDeploymentProperties properties) {
        if (properties == null || properties.getAllowedTenants() == null) {
            throw new IllegalStateException("Tenant deployment configuration is missing");
        }
        required = properties.isRequireBoundIdentity();
        allowed = new HashSet<>();
        for (String value : properties.getAllowedTenants()) {
            try {
                if (!allowed.add(TenantId.of(value).value())) {
                    throw new IllegalArgumentException("Duplicate tenant ID");
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Invalid tenant deployment allowlist", exception);
            }
        }
        if (required != !allowed.isEmpty()) {
            throw new IllegalStateException("Tenant allowlist requires strict bound identity mode");
        }
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!required) return OAuth2TokenValidatorResult.success();
        try {
            var tenant = AccountSecurityJwtClaims.tenantBinding(token);
            if ("user".equals(token.getClaimAsString("token_kind")) && tenant.isPresent()
                    && allowed.contains(tenant.get().tenantId().value())) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException ignored) {
            // Malformed or incomplete bindings cannot enter an isolated deployment.
        }
        return OAuth2TokenValidatorResult.failure(INVALID);
    }
}
