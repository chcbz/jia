package cn.jia.chat.service;

import cn.jia.core.mybatis.TenantScopeHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Single canonical tenant resolver for browser JWTs during the tenant-claim migration. */
@Component
public class TenantScopeResolver {
    private final String legacySingleTenantId;

    public TenantScopeResolver(@Value("${cyf.security.legacy-single-tenant-id:0}") String legacySingleTenantId) {
        this.legacySingleTenantId = exact(legacySingleTenantId);
    }


    /** Compatibility default for direct unit construction; production injection still owns configuration. */
    public static TenantScopeResolver legacySingleTenant() {
        return new TenantScopeResolver(TenantScopeHelper.DEFAULT_TENANT);
    }

    public String resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw unavailable();
        Object value = null;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            value = jwt.getToken().getClaims().get("tenant_id");
            if (value == null) value = jwt.getToken().getClaims().get("tenantId");
            if (value == null && legacyUserToken(jwt)) value = legacySingleTenantId;
        }
        if (value == null && authentication.getPrincipal() != null) {
            try { value = authentication.getPrincipal().getClass().getMethod("getTenantId").invoke(authentication.getPrincipal()); }
            catch (ReflectiveOperationException ignored) { /* fail closed below */ }
        }
        return exact(value instanceof String text ? text : null);
    }

    private boolean legacyUserToken(JwtAuthenticationToken jwt) {
        Object kind = jwt.getToken().getClaims().get("token_kind");
        return "user".equals(kind) || kind == null && jwt.getToken().getClaims().get("jiacn") instanceof String;
    }

    private static String exact(String value) {
        if (value == null || value.isBlank() || value.length() > 50 || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) throw unavailable();
        return value;
    }

    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Chat request is unavailable");
    }
}
