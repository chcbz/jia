package cn.jia.oauth.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigInteger;
import java.util.Map;
import java.util.regex.Pattern;

public record AccountSecurityJwtClaims(TokenClass tokenClass, long userId, String jiacn, long authEpoch) {
    private static final Pattern CANONICAL_UID = Pattern.compile("[1-9][0-9]*");

    public enum TokenClass {
        USER,
        LEGACY_USER,
        MACHINE,
        LEGACY_MACHINE
    }

    public static AccountSecurityJwtClaims parse(Jwt jwt) {
        Map<String, Object> claims = jwt.getClaims();
        if (claims.containsKey("token_kind")) {
            Object kind = claims.get("token_kind");
            if (!(kind instanceof String value)) {
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
            rejectPresent(claims, "uid", "auth_epoch");
            requireString(claims, "sub");
            requireString(claims, "client_id");
            String jiacn = requireString(claims, "jiacn");
            optionalString(claims, "username");
            return new AccountSecurityJwtClaims(TokenClass.LEGACY_USER, 0, jiacn, 0);
        }

        rejectPresent(claims, "uid", "username", "auth_epoch");
        String subject = requireString(claims, "sub");
        String clientId = requireString(claims, "client_id");
        if (!subject.equals(clientId)) {
            throw invalid();
        }
        return new AccountSecurityJwtClaims(TokenClass.LEGACY_MACHINE, 0, null, 0);
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
