package cn.jia.oauth.api;

import cn.jia.core.entity.JsonResult;
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

@RestController
public class AuthenticationController {

    @GetMapping("/resource")
    public ResponseEntity<JsonResult<OAuthResourceIdentityDTO>> resource(Authentication authentication) {
        try {
            if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication) ||
                    !authentication.isAuthenticated()) {
                throw InvalidIdentityClaimsException.INSTANCE;
            }

            Jwt jwt = jwtAuthentication.getToken();
            OAuthResourceIdentityDTO identity = new OAuthResourceIdentityDTO(
                    requiredString(jwt, "sub"),
                    requiredString(jwt, "client_id"),
                    optionalString(jwt, "username"),
                    optionalString(jwt, "jiacn"),
                    scopes(jwt));
            return ResponseEntity.ok(JsonResult.success(identity));
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

    private static boolean containsWhitespace(String value) {
        return value.codePoints().anyMatch(AuthenticationController::isUnicodeWhiteSpace);
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
}
