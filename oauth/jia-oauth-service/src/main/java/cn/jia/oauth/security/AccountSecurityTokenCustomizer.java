package cn.jia.oauth.security;

import cn.jia.user.entity.CustomUserDetails;
import cn.jia.core.security.TenantClaimPolicy;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.security.Principal;
import java.util.Optional;

public final class AccountSecurityTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {
    static final String TOKEN_KIND = "token_kind";
    static final String USER_TOKEN_KIND = "user";
    static final String MACHINE_TOKEN_KIND = "machine";

    private final AccountSecurityService accountSecurityService;
    private final String tenantId;

    public AccountSecurityTokenCustomizer(AccountSecurityService accountSecurityService) {
        this(accountSecurityService, TenantClaimPolicy.defaultSingleTenant());
    }

    public AccountSecurityTokenCustomizer(AccountSecurityService accountSecurityService, String tenantId) {
        this(accountSecurityService, null, tenantId);
    }

    public AccountSecurityTokenCustomizer(AccountSecurityService accountSecurityService,
            JdbcTenantAuthorizationRepository repository) {
        this(accountSecurityService, repository, TenantClaimPolicy.defaultSingleTenant());
    }

    public AccountSecurityTokenCustomizer(AccountSecurityService accountSecurityService,
            JdbcTenantAuthorizationRepository repository, String tenantId) {
        this.accountSecurityService = accountSecurityService;
        this.tenantAuthorizationRepository = repository;
        this.tenantId = TenantClaimPolicy.exact(tenantId);
    }

    private final JdbcTenantAuthorizationRepository tenantAuthorizationRepository;

    @Override
    public void customize(JwtEncodingContext context) {
        context.getClaims()
                .claim("client_id", context.getRegisteredClient().getClientId())
                .claim(TenantClaimPolicy.CANONICAL_CLAIM, tenantId)
                .claim(TenantClaimPolicy.VERSION_CLAIM, TenantClaimPolicy.CURRENT_VERSION);

        CustomUserDetails userDetails = findUserDetails(context);
        if (userDetails != null) {
            requireCurrentLoginPrincipal(userDetails);
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims()
                        .claim(TOKEN_KIND, USER_TOKEN_KIND)
                        .claim("uid", Long.toString(userDetails.getUserId()))
                        .claim("jiacn", userDetails.getJiacn())
                        .claim("username", userDetails.getUsername())
                        .claim("auth_epoch", userDetails.getLoginAuthEpoch());
                if (tenantAuthorizationRepository != null) {
                    addBoundTenantClaims(context, userDetails);
                }
            }
            return;
        }

        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        if (!AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
            throw invalidGrant();
        }
        context.getClaims().claims(claims -> {
            claims.put(TOKEN_KIND, MACHINE_TOKEN_KIND);
            claims.remove("uid");
            claims.remove("jiacn");
            claims.remove("username");
            claims.remove("auth_epoch");
        });
    }

    private void addBoundTenantClaims(JwtEncodingContext context, CustomUserDetails userDetails) {
        OAuth2Authorization authorization = context.getAuthorization();
        if (authorization == null) throw invalidGrant();
        Object value = authorization.getAttribute(TenantAuthorizationAttributes.attributeName());
        // An unselected authorization must never mint a user access token.
        if (value == null) throw invalidGrant();
        if (!(value instanceof TenantAuthorizationAttributes tenant)) {
            throw invalidGrant();
        }
        // A bound authorization is immutable. Every code exchange/refresh must
        // observe the current region, membership and client/domain grant; stale
        // versions or unavailable control data are denied, not silently renewed.
        try {
            if (!authorization.getRegisteredClientId().equals(context.getRegisteredClient().getId())
                    || !tenantAuthorizationRepository.isEligibleForIssuance(tenant, userDetails.getUserId(),
                            context.getRegisteredClient().getClientId())) {
                throw invalidGrant();
            }
        } catch (OAuth2AuthenticationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidGrant();
        }
        context.getClaims()
                .claim("tenant_id", tenant.tenantId().value())
                .claim("data_domain_id", tenant.dataDomainId().value())
                .claim("tenant_name", tenant.tenantName())
                .claim("tenant_kind", tenant.tenantKind().name())
                .claim("tenant_session_epoch", tenant.tenantSessionEpoch())
                .claim("membership_revision", tenant.membershipRevision())
                .claim("client_tenant_grant_revision", tenant.clientTenantGrantRevision());
    }

    private void requireCurrentLoginPrincipal(CustomUserDetails principal) {
        try {
            Optional<AccountSecuritySnapshot> current = accountSecurityService.findByUserId(principal.getUserId());
            if (current.isEmpty() || !current.get().matches(
                    principal.getUserId(), principal.getJiacn(), principal.getLoginAuthEpoch())) {
                throw invalidGrant();
            }
        } catch (OAuth2AuthenticationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidGrant();
        }
    }

    private static CustomUserDetails findUserDetails(JwtEncodingContext context) {
        CustomUserDetails direct = unwrap(context.getPrincipal());
        if (direct != null) {
            return direct;
        }
        OAuth2Authorization authorization = context.getAuthorization();
        if (authorization == null) {
            return null;
        }
        return unwrap(authorization.getAttribute(Principal.class.getName()));
    }

    private static CustomUserDetails unwrap(Object value) {
        Object current = value;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (current instanceof CustomUserDetails userDetails) {
                return userDetails;
            }
            if (current instanceof Authentication authentication) {
                Object principal = authentication.getPrincipal();
                if (principal == current) {
                    return null;
                }
                current = principal;
                continue;
            }
            return null;
        }
        return null;
    }

    private static OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
    }
}
