package cn.jia.chat.service;

import cn.jia.core.security.TenantClaimPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves exactly the same canonical tenant scope as the resource filter. */
@Component
public class TenantScopeResolver {
    public String resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw unavailable();
        try {
            if (authentication instanceof JwtAuthenticationToken jwt) {
                return TenantClaimPolicy.resolve(jwt.getToken().getClaims());
            }
        } catch (IllegalArgumentException ignored) {
            // Fail closed below without exposing claim or principal details.
        }
        throw unavailable();
    }

    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Chat request is unavailable");
    }
}
