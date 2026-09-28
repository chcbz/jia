package cn.jia.chat.service;

import cn.jia.core.security.TenantClaimPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves exactly the same canonical tenant scope as the resource filter. */
@Component
public class TenantScopeResolver {
    private final String legacySingleTenantId;

    public TenantScopeResolver(@Value("${cyf.security.legacy-single-tenant-id:0}") String legacySingleTenantId) {
        this.legacySingleTenantId = TenantClaimPolicy.exact(legacySingleTenantId);
    }

    public static TenantScopeResolver legacySingleTenant() {
        return new TenantScopeResolver(TenantClaimPolicy.defaultSingleTenant());
    }

    public String resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw unavailable();
        try {
            if (authentication instanceof JwtAuthenticationToken jwt) {
                return TenantClaimPolicy.resolve(jwt.getToken().getClaims(), legacySingleTenantId);
            }
            Object principal = authentication.getPrincipal();
            if (principal != null) {
                Object value = principal.getClass().getMethod("getTenantId").invoke(principal);
                return TenantClaimPolicy.exact(value instanceof String text ? text : null);
            }
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            // Fail closed below without exposing claim or principal details.
        }
        throw unavailable();
    }

    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Chat request is unavailable");
    }
}
