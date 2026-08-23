package cn.jia.oauth.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

@RestController
public class AuthenticationController {

    @GetMapping("/resource")
    public OAuthResourceIdentityDTO resource(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication) ||
                !authentication.isAuthenticated()) {
            throw invalidTokenClaims();
        }

        Jwt jwt = jwtAuthentication.getToken();
        return new OAuthResourceIdentityDTO(
                requiredString(jwt, "sub"),
                requiredString(jwt, "client_id"),
                optionalString(jwt, "username"),
                optionalString(jwt, "jiacn"),
                scopes(jwt));
    }

    private static String requiredString(Jwt jwt, String claimName) {
        String value = optionalString(jwt, claimName);
        if (value == null) {
            throw invalidTokenClaims();
        }
        return value;
    }

    private static String optionalString(Jwt jwt, String claimName) {
        Object claim = jwt.getClaims().get(claimName);
        if (claim == null) {
            return null;
        }
        if (!(claim instanceof String value)) {
            throw invalidTokenClaims();
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static List<String> scopes(Jwt jwt) {
        if (!jwt.getClaims().containsKey("scope")) {
            throw invalidTokenClaims();
        }

        Object claim = jwt.getClaims().get("scope");
        TreeSet<String> scopes = new TreeSet<>();
        if (claim instanceof String value) {
            for (String scope : value.trim().split("\\s+")) {
                if (!scope.isBlank()) {
                    scopes.add(scope);
                }
            }
        } else if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                if (!(value instanceof String scope)) {
                    throw invalidTokenClaims();
                }
                String normalized = scope.trim();
                if (!normalized.isEmpty()) {
                    scopes.add(normalized);
                }
            }
        } else {
            throw invalidTokenClaims();
        }
        return List.copyOf(scopes);
    }

    private static ResponseStatusException invalidTokenClaims() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer token identity claims are invalid");
    }
}
