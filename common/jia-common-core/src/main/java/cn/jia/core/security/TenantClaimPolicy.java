package cn.jia.core.security;

import cn.jia.core.mybatis.TenantScopeHelper;

import java.util.Map;

/** Shared issuer/resource/chat policy for canonical and legacy tenant JWT claims. */
public final class TenantClaimPolicy {
    public static final String CANONICAL_CLAIM = "tenant_id";
    public static final String ALIAS_CLAIM = "tenantId";
    public static final String VERSION_CLAIM = "tenant_claim_version";
    public static final String CURRENT_VERSION = "1";

    private TenantClaimPolicy() { }

    public static String resolve(Map<String, ?> claims, String legacySingleTenantId) {
        if (claims == null) throw invalid();
        String canonical = claim(claims, CANONICAL_CLAIM);
        String alias = claim(claims, ALIAS_CLAIM);
        if (canonical != null && alias != null && !canonical.equals(alias)) throw invalid();
        String version = claim(claims, VERSION_CLAIM);
        if (version != null && !CURRENT_VERSION.equals(version)) throw invalid();
        String declared = canonical != null ? canonical : alias;
        if (declared != null) return exact(declared);
        if (version != null) throw invalid();
        Object kind = claims.get("token_kind");
        boolean legacyUser = "user".equals(kind)
                || kind == null && claims.get("jiacn") instanceof String jiacn && !jiacn.isBlank();
        if (!legacyUser) throw invalid();
        return exact(legacySingleTenantId);
    }

    public static String defaultSingleTenant() { return TenantScopeHelper.DEFAULT_TENANT; }

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
