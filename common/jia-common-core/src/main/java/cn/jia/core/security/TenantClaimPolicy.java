package cn.jia.core.security;

import java.util.Map;

/**
 * Shared issuer/resource/chat policy. A signed canonical business partition is required;
 * neither a user identity, an alias-only claim nor a default deployment is a substitute.
 */
public final class TenantClaimPolicy {
    public static final String CANONICAL_CLAIM = "tenant_id";
    public static final String ALIAS_CLAIM = "tenantId";
    public static final String VERSION_CLAIM = "tenant_claim_version";
    public static final String CURRENT_VERSION = "1";

    private TenantClaimPolicy() { }

    public static String resolve(Map<String, ?> claims) {
        if (claims == null) throw invalid();
        String canonical = claim(claims, CANONICAL_CLAIM);
        String alias = claim(claims, ALIAS_CLAIM);
        if (canonical != null && alias != null && !canonical.equals(alias)) throw invalid();
        String version = claim(claims, VERSION_CLAIM);
        if (version != null && !CURRENT_VERSION.equals(version)) throw invalid();
        // An optional duplicate field must agree, but never supplies missing authority.
        if (canonical == null) throw invalid();
        return canonical;
    }

    public static String exact(String value) {
        if (value == null || value.isBlank() || value.length() > 50 || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }

    private static String claim(Map<String, ?> claims, String name) {
        Object value = claims.get(name);
        if (value == null) return null;
        if (!(value instanceof String text)) throw invalid();
        return exact(text);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid tenant claim scope");
    }
}
