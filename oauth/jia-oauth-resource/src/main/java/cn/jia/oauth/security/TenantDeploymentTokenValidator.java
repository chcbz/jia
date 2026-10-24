package cn.jia.oauth.security;

import cn.jia.oauth.config.ResourceServerConfig.TenantDeploymentProperties;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Deployment allowlist is independent of the user's current tenant grant. */
public final class TenantDeploymentTokenValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN);
    private final TenantAdmissionPolicy admissionPolicy;

    public TenantDeploymentTokenValidator(TenantDeploymentProperties properties) {
        this(TenantAdmissionPolicy.from(properties));
    }

    public TenantDeploymentTokenValidator(TenantAdmissionPolicy admissionPolicy) {
        this.admissionPolicy = admissionPolicy;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            if (admissionPolicy.admitsDeploymentToken(token)) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException ignored) {
            // Malformed or incomplete bindings cannot enter an isolated deployment.
        }
        return OAuth2TokenValidatorResult.failure(INVALID);
    }
}
