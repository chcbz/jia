package cn.jia.oauth.api;

import cn.jia.oauth.security.AccountSecurityJwtClaims;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

@RestController
public class OAuthResourceIdentityController {
    private static final Pattern TENANT_ID = Pattern.compile("0|[1-9][0-9]{0,49}");
    private static final Pattern DATA_DOMAIN_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern EPOCH = Pattern.compile("0|[1-9][0-9]{0,18}");

    @GetMapping("/resource")
    public ResponseEntity<OAuthResourceIdentityDTO> resource(Authentication authentication) {
        try {
            if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication) ||
                    !authentication.isAuthenticated()) {
                throw InvalidIdentityClaimsException.INSTANCE;
            }

            Jwt jwt = jwtAuthentication.getToken();
            TenantIdentity tenant = tenantIdentity(jwt);
            OAuthResourceIdentityDTO identity = new OAuthResourceIdentityDTO(
                    requiredString(jwt, "sub"),
                    requiredString(jwt, "client_id"),
                    optionalString(jwt, "username"),
                    optionalString(jwt, "jiacn"),
                    scopes(jwt),
                    tenant.tenantId() == null ? optionalString(jwt, "iss") : requiredString(jwt, "iss"),
                    tenant.tenantId(),
                    tenant.dataDomainId(),
                    tenant.tenantName(),
                    tenant.tenantKind(),
                    tenant.tenantSessionEpoch(),
                    tenant.membershipRevision(),
                    tenant.clientTenantGrantRevision());
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(identity);
        } catch (InvalidIdentityClaimsException ignored) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    private static String requiredString(Jwt jwt, String claimName) {
        if (!jwt.getClaims().containsKey(claimName)) {
            throw InvalidIdentityClaimsException.INSTANCE;
        }
        Object claim = jwt.getClaims().get(claimName);
        if (!(claim instanceof String value) || value.isBlank()) {
            throw InvalidIdentityClaimsException.INSTANCE;
        }
        return value;
    }

    private static String optionalString(Jwt jwt, String claimName) {
        if (!jwt.getClaims().containsKey(claimName)) {
            return null;
        }
        Object claim = jwt.getClaims().get(claimName);
        if (!(claim instanceof String value) || value.isBlank()) {
            throw InvalidIdentityClaimsException.INSTANCE;
        }
        return value;
    }

    private static List<String> scopes(Jwt jwt) {
        if (!jwt.getClaims().containsKey("scope")) {
            return List.of();
        }

        Object claim = jwt.getClaims().get("scope");
        TreeSet<String> scopes = new TreeSet<>();
        if (claim instanceof String value) {
            if (value.isBlank()) {
                throw InvalidIdentityClaimsException.INSTANCE;
            }
            for (String scope : value.strip().split("\\s+")) {
                if (scope.isBlank()) {
                    throw InvalidIdentityClaimsException.INSTANCE;
                }
                scopes.add(scope);
            }
        } else if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                if (!(value instanceof String scope) || scope.isEmpty() || containsWhitespace(scope)) {
                    throw InvalidIdentityClaimsException.INSTANCE;
                }
                scopes.add(scope);
            }
        } else {
            throw InvalidIdentityClaimsException.INSTANCE;
        }
        return List.copyOf(scopes);
    }

    /**
     * New tenant-aware tokens carry the entire identity bundle. Old tokens keep
     * their existing /resource shape during the explicitly controlled migration
     * window, but a partially supplied bundle is always rejected.
     */
    private static TenantIdentity tenantIdentity(Jwt jwt) {
        boolean present = jwt.getClaims().keySet().stream().anyMatch(name -> name.equals("tenant_id")
                || name.equals("data_domain_id") || name.equals("tenant_name") || name.equals("tenant_kind")
                || name.equals("tenant_session_epoch") || name.equals("membership_revision")
                || name.equals("client_tenant_grant_revision"));
        if (!present) {
            return TenantIdentity.EMPTY;
        }
        try {
            if (AccountSecurityJwtClaims.parse(jwt).tokenClass() != AccountSecurityJwtClaims.TokenClass.USER) {
                throw InvalidIdentityClaimsException.INSTANCE;
            }
        } catch (IllegalArgumentException exception) {
            throw InvalidIdentityClaimsException.INSTANCE;
        }
        String tenantId = requiredString(jwt, "tenant_id");
        String dataDomainId = requiredString(jwt, "data_domain_id");
        String tenantName = requiredString(jwt, "tenant_name");
        String tenantKind = requiredString(jwt, "tenant_kind");
        String sessionEpoch = requiredString(jwt, "tenant_session_epoch");
        String membershipRevision = requiredString(jwt, "membership_revision");
        String grantRevision = requiredString(jwt, "client_tenant_grant_revision");
        if (!TENANT_ID.matcher(tenantId).matches() || !DATA_DOMAIN_ID.matcher(dataDomainId).matches() || !validRevision(sessionEpoch)
                || !validRevision(membershipRevision) || !validRevision(grantRevision)
                || !("NORMAL".equals(tenantKind) || "TEST".equals(tenantKind))) {
            throw InvalidIdentityClaimsException.INSTANCE;
        }
        return new TenantIdentity(tenantId, dataDomainId, tenantName, tenantKind, sessionEpoch, membershipRevision, grantRevision);
    }

    private static boolean validRevision(String value) {
        if (!EPOCH.matcher(value).matches()) return false;
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean containsWhitespace(String value) {
        return value.codePoints().anyMatch(OAuthResourceIdentityController::isUnicodeWhiteSpace);
    }

    private static boolean isUnicodeWhiteSpace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000D ||
                codePoint == 0x0020 ||
                codePoint == 0x0085 ||
                codePoint == 0x00A0 ||
                codePoint == 0x1680 ||
                codePoint >= 0x2000 && codePoint <= 0x200A ||
                codePoint == 0x2028 ||
                codePoint == 0x2029 ||
                codePoint == 0x202F ||
                codePoint == 0x205F ||
                codePoint == 0x3000;
    }

    private static final class InvalidIdentityClaimsException extends RuntimeException {
        private static final InvalidIdentityClaimsException INSTANCE = new InvalidIdentityClaimsException();

        private InvalidIdentityClaimsException() {
            super(null, null, false, false);
        }
    }

    private record TenantIdentity(String tenantId, String dataDomainId, String tenantName, String tenantKind,
                                  String tenantSessionEpoch, String membershipRevision,
                                  String clientTenantGrantRevision) {
        private static final TenantIdentity EMPTY = new TenantIdentity(null, null, null, null, null, null, null);
    }
}
