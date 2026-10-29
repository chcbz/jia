package cn.jia.oauth.security;

import cn.jia.user.entity.CustomUserDetails;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
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
    private final ClientTenantLookup clientTenants;
    private final FixedTenantAdmissionPolicy tenantAdmission;

    public AccountSecurityTokenCustomizer(AccountSecurityService accountSecurityService) {
        this(accountSecurityService, ignored -> TenantClaimPolicy.defaultSingleTenant(),
                new FixedTenantAdmissionPolicy(TenantClaimPolicy.defaultSingleTenant(), false, null, null));
    }

    public AccountSecurityTokenCustomizer(AccountSecurityService accountSecurityService, String tenantId) {
        String exactTenant = TenantClaimPolicy.exact(tenantId);
        this.accountSecurityService = accountSecurityService;
        this.clientTenants = ignored -> exactTenant;
        this.tenantAdmission = new FixedTenantAdmissionPolicy(exactTenant, false, null, null);
    }

    public AccountSecurityTokenCustomizer(AccountSecurityService accountSecurityService,
            ClientTenantLookup clientTenants, FixedTenantAdmissionPolicy tenantAdmission) {
        this.accountSecurityService = java.util.Objects.requireNonNull(accountSecurityService);
        this.clientTenants = java.util.Objects.requireNonNull(clientTenants);
        this.tenantAdmission = java.util.Objects.requireNonNull(tenantAdmission);
    }

    @Override
    public void customize(JwtEncodingContext context) {
        String clientId = context.getRegisteredClient() == null
                ? null : context.getRegisteredClient().getClientId();
        String tenantId;
        try {
            tenantId = tenantAdmission.requireClientTenant(clientId, clientTenants.findTenantId(clientId));
        } catch (RuntimeException invalidClientMapping) {
            throw invalidGrant();
        }
        context.getClaims()
                .claim("client_id", clientId)
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

    @FunctionalInterface
    public interface ClientTenantLookup {
        String findTenantId(String clientId);
    }

    private static OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
    }
}
