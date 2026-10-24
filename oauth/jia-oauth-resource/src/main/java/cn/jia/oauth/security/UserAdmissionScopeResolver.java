package cn.jia.oauth.security;

import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.UserAdmissionScope;
import cn.jia.oauth.config.ResourceServerConfig.TenantDeploymentProperties;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Constructs user admission only from the signature-validated resource security context. */
@Component
public final class UserAdmissionScopeResolver {
    private static final TenantId LEGACY_TENANT = TenantId.of("0");
    private static final DataDomainId CYF_DOMAIN = DataDomainId.of("cyf");

    private final TenantAdmissionPolicy admissionPolicy;
    private final AccountSecurityService accountSecurityService;

    /** Existing direct callers retain strict platform-bundle semantics. */
    public UserAdmissionScopeResolver() {
        this(TenantAdmissionPolicy.platformValidatorDefault(true));
    }

    public UserAdmissionScopeResolver(TenantDeploymentProperties properties) {
        this(TenantAdmissionPolicy.from(properties));
    }

    @Autowired
    public UserAdmissionScopeResolver(TenantDeploymentProperties properties,
                                      ObjectProvider<AccountSecurityService> accountSecurityServiceProvider) {
        this(TenantAdmissionPolicy.from(properties), accountSecurityServiceProvider.getIfAvailable());
    }

    public UserAdmissionScopeResolver(TenantAdmissionPolicy admissionPolicy) {
        this(admissionPolicy, null);
    }

    public UserAdmissionScopeResolver(TenantAdmissionPolicy admissionPolicy,
                                      AccountSecurityService accountSecurityService) {
        this.admissionPolicy = admissionPolicy;
        this.accountSecurityService = accountSecurityService;
    }

    public UserAdmissionScope resolve(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw denied();
        }
        try {
            Jwt jwt = jwtAuthentication.getToken();
            AccountSecurityJwtClaims claims = AccountSecurityJwtClaims.parse(jwt);
            Object claimedClient = jwt.getClaims().get("client_id");
            if (!(claimedClient instanceof String client)) throw denied();
            if (claims.tokenClass() == AccountSecurityJwtClaims.TokenClass.LEGACY_USER) {
                return resolveLegacyUser(claims, client);
            }
            if (claims.tokenClass() != AccountSecurityJwtClaims.TokenClass.USER) throw denied();
            if (admissionPolicy.isFixedTenant()) {
                var tenantId = admissionPolicy.requireFixedUserTenant(jwt);
                return UserAdmissionScope.basic(new BusinessScope(tenantId,
                                admissionPolicy.fixedDataDomainId(), claims.jiacn(), client),
                        claims.userId(), claims.authEpoch());
            }
            TenantAuthorizationAttributes selected = AccountSecurityJwtClaims.tenantBinding(jwt).orElseThrow(
                    UserAdmissionScopeResolver::denied);
            return new UserAdmissionScope(
                    new BusinessScope(selected.tenantId(), selected.dataDomainId(), claims.jiacn(), client),
                    claims.userId(), claims.authEpoch(),
                    Long.parseLong(selected.tenantSessionEpoch()),
                    Long.parseLong(selected.membershipRevision()),
                    Long.parseLong(selected.clientTenantGrantRevision()));
        } catch (RuntimeException invalid) {
            throw denied();
        }
    }

    private UserAdmissionScope resolveLegacyUser(AccountSecurityJwtClaims claims, String client) {
        if (!admissionPolicy.allowsLegacyUser() || accountSecurityService == null) throw denied();
        AccountSecuritySnapshot account = accountSecurityService
                .findUniqueByExactJiacn(claims.jiacn()).orElseThrow(UserAdmissionScopeResolver::denied);
        if (!account.isAuthenticatable()
                || account.userId() <= 0
                || !account.jiacn().equals(claims.jiacn())
                || account.authEpoch() != 0) {
            throw denied();
        }
        return UserAdmissionScope.basic(
                new BusinessScope(LEGACY_TENANT, CYF_DOMAIN, account.jiacn(), client),
                account.userId(), account.authEpoch());
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Authenticated tenant user scope is unavailable");
    }
}
