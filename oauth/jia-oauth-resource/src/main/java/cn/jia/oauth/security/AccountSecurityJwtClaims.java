package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.security.TenantClaimPolicy;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public record AccountSecurityJwtClaims(TokenClass tokenClass, long userId, String jiacn, long authEpoch) {
    private static final Pattern CANONICAL_UID = Pattern.compile("[1-9][0-9]*");
    private static final Pattern CANONICAL_TENANT = Pattern.compile("0|[1-9][0-9]{0,49}");
    private static final Pattern CANONICAL_DOMAIN = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern CANONICAL_REVISION = Pattern.compile("0|[1-9][0-9]{0,18}");

    public enum TokenClass {
        USER,
        LEGACY_USER,
        MACHINE,
        LEGACY_MACHINE
    }

    /** Parse the canonical tenant claim without requiring platform authorization versions. */
    public static Optional<TenantId> tenantId(Jwt jwt) {
        Map<String, Object> claims = jwt.getClaims();
        validateTenantClaims(claims);
        Optional<String> resolved = resolvedTenantClaim(claims);
        if (resolved.isEmpty()) return Optional.empty();
        try {
            return Optional.of(TenantId.of(resolved.get()));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    /** Parse an all-or-nothing platform authorization bundle when one is present. */
    public static Optional<TenantAuthorizationAttributes> tenantBinding(Jwt jwt) {
        Map<String, Object> claims = jwt.getClaims();
        validateTenantClaims(claims);
        if (!hasPlatformBundle(claims)) return Optional.empty();
        try {
            return Optional.of(new TenantAuthorizationAttributes(
                    tenantId(jwt).orElseThrow(AccountSecurityJwtClaims::invalid),
                    DataDomainId.of(requireString(claims, "data_domain_id")),
                    requireString(claims, "tenant_name"),
                    TenantAuthorizationAttributes.TenantKind.valueOf(requireString(claims, "tenant_kind")),
                    requireString(claims, "tenant_session_epoch"),
                    requireString(claims, "membership_revision"),
                    requireString(claims, "client_tenant_grant_revision")));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    public static AccountSecurityJwtClaims parse(Jwt jwt) {
        Map<String, Object> claims = jwt.getClaims();
        validateTenantClaims(claims);
        if (claims.containsKey("token_kind")) {
            Object kind = claims.get("token_kind");
            if (!(kind instanceof String value)) {
                throw invalid();
            }
            if (hasAnyTenantClaims(claims) && !"user".equals(value)) {
                throw invalid();
            }
            if ("user".equals(value)) {
                requireString(claims, "sub");
                requireString(claims, "client_id");
                requireString(claims, "username");
                String uid = requireString(claims, "uid");
                String jiacn = requireString(claims, "jiacn");
                long userId = parseUid(uid);
                long authEpoch = parseEpoch(claims, true);
                return new AccountSecurityJwtClaims(TokenClass.USER, userId, jiacn, authEpoch);
            }
            if ("machine".equals(value)) {
                rejectPresent(claims, "uid", "jiacn", "username", "auth_epoch");
                String subject = requireString(claims, "sub");
                String clientId = requireString(claims, "client_id");
                if (!subject.equals(clientId)) {
                    throw invalid();
                }
                return new AccountSecurityJwtClaims(TokenClass.MACHINE, 0, null, 0);
            }
            throw invalid();
        }

        if (claims.containsKey("jiacn")) {
            if (hasAnyTenantClaims(claims)) {
                throw invalid();
            }
            rejectPresent(claims, "uid", "auth_epoch");
            requireString(claims, "sub");
            requireString(claims, "client_id");
            String jiacn = requireString(claims, "jiacn");
            optionalString(claims, "username");
            return new AccountSecurityJwtClaims(TokenClass.LEGACY_USER, 0, jiacn, 0);
        }

        rejectPresent(claims, "uid", "username", "auth_epoch");
        if (hasAnyTenantClaims(claims)) {
            throw invalid();
        }
        String subject = requireString(claims, "sub");
        String clientId = requireString(claims, "client_id");
        if (!subject.equals(clientId)) {
            throw invalid();
        }
        return new AccountSecurityJwtClaims(TokenClass.LEGACY_MACHINE, 0, null, 0);
    }

    private static boolean hasAnyTenantClaims(Map<String, Object> claims) {
        return hasTenantDeclaration(claims) || hasPlatformClaims(claims);
    }

    private static boolean hasTenantDeclaration(Map<String, Object> claims) {
        return claims.containsKey(TenantClaimPolicy.CANONICAL_CLAIM)
                || claims.containsKey(TenantClaimPolicy.ALIAS_CLAIM)
                || claims.containsKey(TenantClaimPolicy.VERSION_CLAIM);
    }

    private static boolean hasPlatformClaims(Map<String, Object> claims) {
        return claims.containsKey("data_domain_id") || claims.containsKey("tenant_name")
                || claims.containsKey("tenant_kind") || claims.containsKey("tenant_session_epoch")
                || claims.containsKey("membership_revision") || claims.containsKey("client_tenant_grant_revision");
    }

    private static boolean hasPlatformBundle(Map<String, Object> claims) {
        return hasTenantDeclaration(claims) && claims.containsKey("data_domain_id")
                && claims.containsKey("tenant_name") && claims.containsKey("tenant_kind")
                && claims.containsKey("tenant_session_epoch") && claims.containsKey("membership_revision")
                && claims.containsKey("client_tenant_grant_revision");
    }

    private static void validateTenantClaims(Map<String, Object> claims) {
        if (!hasAnyTenantClaims(claims)) return;
        Optional<String> tenant = resolvedTenantClaim(claims);
        if (tenant.isEmpty() || !CANONICAL_TENANT.matcher(tenant.get()).matches()) {
            throw invalid();
        }
        if (!hasPlatformClaims(claims)) return;
        if (!hasPlatformBundle(claims)
                || !CANONICAL_DOMAIN.matcher(requireString(claims, "data_domain_id")).matches()
                || !("NORMAL".equals(requireString(claims, "tenant_kind"))
                || "TEST".equals(requireString(claims, "tenant_kind")))
                || !validRevision(requireString(claims, "tenant_session_epoch"))
                || !validRevision(requireString(claims, "membership_revision"))
                || !validRevision(requireString(claims, "client_tenant_grant_revision"))) {
            throw invalid();
        }
        requireString(claims, "tenant_name");
    }

    private static Optional<String> resolvedTenantClaim(Map<String, Object> claims) {
        if (!hasTenantDeclaration(claims)) return Optional.empty();
        try {
            // A declaration is present, so TenantClaimPolicy cannot take its
            // legacy missing-claim fallback. It only reconciles canonical/alias
            // values and enforces the supported claim version here.
            return Optional.of(TenantClaimPolicy.resolve(claims, "__missing_tenant_is_invalid__"));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static boolean validRevision(String value) {
        if (!CANONICAL_REVISION.matcher(value).matches()) return false;
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static long parseUid(String uid) {
        if (!CANONICAL_UID.matcher(uid).matches()) {
            throw invalid();
        }
        try {
            return Long.parseLong(uid);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static long parseEpoch(Map<String, Object> claims, boolean required) {
        if (!claims.containsKey("auth_epoch")) {
            if (required) {
                throw invalid();
            }
            return 0;
        }
        Object value = claims.get("auth_epoch");
        long epoch;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            epoch = ((Number) value).longValue();
        } else if (value instanceof BigInteger integer && integer.bitLength() < Long.SIZE) {
            epoch = integer.longValue();
        } else {
            throw invalid();
        }
        if (epoch < 0) {
            throw invalid();
        }
        return epoch;
    }

    private static String requireString(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw invalid();
        }
        return string;
    }

    private static void optionalString(Map<String, Object> claims, String name) {
        if (claims.containsKey(name)) {
            requireString(claims, name);
        }
    }

    private static void rejectPresent(Map<String, Object> claims, String... names) {
        for (String name : names) {
            if (claims.containsKey(name)) {
                throw invalid();
            }
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid token identity");
    }
}
