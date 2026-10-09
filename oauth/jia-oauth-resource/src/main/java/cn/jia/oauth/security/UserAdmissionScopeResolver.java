package cn.jia.oauth.security;

import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.UserAdmissionScope;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Constructs user admission only from the signature-validated resource security context. */
@Component
public final class UserAdmissionScopeResolver {
    public UserAdmissionScope resolve(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw denied();
        }
        try {
            Jwt jwt = jwtAuthentication.getToken();
            AccountSecurityJwtClaims claims = AccountSecurityJwtClaims.parse(jwt);
            if (claims.tokenClass() != AccountSecurityJwtClaims.TokenClass.USER) throw denied();
            TenantAuthorizationAttributes selected = AccountSecurityJwtClaims.tenantBinding(jwt).orElseThrow(
                    UserAdmissionScopeResolver::denied);
            Object claimedClient = jwt.getClaims().get("client_id");
            if (!(claimedClient instanceof String client)) throw denied();
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

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Authenticated tenant user scope is unavailable");
    }
}
