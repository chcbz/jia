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
        if (!jwt.getClaims().containsKey(claimName)) {
            return null;
        }
        Object claim = jwt.getClaims().get(claimName);
        if (!(claim instanceof String value) || value.isBlank()) {
            throw invalidTokenClaims();
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
                throw invalidTokenClaims();
            }
            for (String scope : value.trim().split("\\s+")) {
                scopes.add(scope);
            }
        } else if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                if (!(value instanceof String scope) || scope.isBlank()) {
                    throw invalidTokenClaims();
                }
                scopes.add(scope.trim());
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
