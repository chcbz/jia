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
    private final boolean requireBoundIdentity;

    public AccountSecurityJwtValidator(AccountSecurityService accountSecurityService) {
        this(accountSecurityService, null, true);
    }

    public AccountSecurityJwtValidator(AccountSecurityService accountSecurityService,
                                       TenantAuthorizationVerifier tenantAuthorizationVerifier) {
        this(accountSecurityService, tenantAuthorizationVerifier, true);
    }

    public AccountSecurityJwtValidator(AccountSecurityService accountSecurityService,
                                       TenantAuthorizationVerifier tenantAuthorizationVerifier,
                                       boolean requireBoundIdentity) {
        this.accountSecurityService = accountSecurityService;
        this.tenantAuthorizationVerifier = tenantAuthorizationVerifier;
        this.requireBoundIdentity = requireBoundIdentity;
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
                Optional<TenantAuthorizationAttributes> bound = AccountSecurityJwtClaims.tenantBinding(jwt);
                if (bound.isEmpty() || tenantAuthorizationVerifier == null
                        || !tenantAuthorizationVerifier.isCurrent(bound.get(), identity.userId(),
                        jwt.getClaim("client_id"))) {
                    return failure();
                }
            } else {
                // Legacy user JWTs have no trustworthy area/owner binding. A
                // strict multi-area deployment must reject them. Compatibility
                // deployments remain tenant-zero-only and preserve their
                // existing account/auth-epoch validation until cutover.
                if (requireBoundIdentity) return failure();
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
