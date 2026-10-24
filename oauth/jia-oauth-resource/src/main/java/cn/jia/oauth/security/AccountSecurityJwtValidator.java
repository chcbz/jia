package cn.jia.oauth.security;

import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

public final class AccountSecurityJwtValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID_TOKEN = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN);

    private final AccountSecurityService accountSecurityService;

    private final TenantAuthorizationVerifier tenantAuthorizationVerifier;
    private final TenantAdmissionPolicy admissionPolicy;

    public AccountSecurityJwtValidator(AccountSecurityService accountSecurityService) {
        this(accountSecurityService, null, TenantAdmissionPolicy.platformValidatorDefault(true));
    }

    public AccountSecurityJwtValidator(AccountSecurityService accountSecurityService,
                                       TenantAuthorizationVerifier tenantAuthorizationVerifier) {
        this(accountSecurityService, tenantAuthorizationVerifier,
                TenantAdmissionPolicy.platformValidatorDefault(true));
    }

    public AccountSecurityJwtValidator(AccountSecurityService accountSecurityService,
                                       TenantAuthorizationVerifier tenantAuthorizationVerifier,
                                       boolean requireBoundIdentity) {
        this(accountSecurityService, tenantAuthorizationVerifier,
                TenantAdmissionPolicy.platformValidatorDefault(requireBoundIdentity));
    }

    public AccountSecurityJwtValidator(AccountSecurityService accountSecurityService,
                                       TenantAuthorizationVerifier tenantAuthorizationVerifier,
                                       TenantAdmissionPolicy admissionPolicy) {
        this.accountSecurityService = accountSecurityService;
        this.tenantAuthorizationVerifier = tenantAuthorizationVerifier;
        this.admissionPolicy = admissionPolicy;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            AccountSecurityJwtClaims identity = AccountSecurityJwtClaims.parse(jwt);
            if (identity.tokenClass() == AccountSecurityJwtClaims.TokenClass.MACHINE
                    || identity.tokenClass() == AccountSecurityJwtClaims.TokenClass.LEGACY_MACHINE) {
                return OAuth2TokenValidatorResult.success();
            }

            if (accountSecurityService == null) {
                return failure();
            }
            Optional<AccountSecuritySnapshot> snapshot;
            if (identity.tokenClass() == AccountSecurityJwtClaims.TokenClass.USER) {
                snapshot = accountSecurityService.findByUserId(identity.userId());
            } else {
                snapshot = accountSecurityService.findUniqueByExactJiacn(identity.jiacn());
            }
            if (snapshot.isEmpty()) {
                return failure();
            }
            AccountSecuritySnapshot account = snapshot.get();
            boolean valid = identity.tokenClass() == AccountSecurityJwtClaims.TokenClass.USER
                    ? account.matches(identity.userId(), identity.jiacn(), identity.authEpoch())
                    : account.isAuthenticatable()
                    && account.jiacn().equals(identity.jiacn())
                    && account.authEpoch() == 0;
            if (!valid) return failure();
            if (identity.tokenClass() == AccountSecurityJwtClaims.TokenClass.USER) {
                if (!admissionPolicy.isCurrentUserAuthorization(jwt, identity,
                        tenantAuthorizationVerifier)) {
                    return failure();
                }
            } else {
                // Legacy user JWTs have no trustworthy area/owner binding. A
                // strict multi-area deployment must reject them. Compatibility
                // deployments remain tenant-zero-only and preserve their
                // existing account/auth-epoch validation until cutover.
                if (!admissionPolicy.allowsLegacyUser()) return failure();
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException exception) {
            return failure();
        }
    }

    private static OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
    }
}
