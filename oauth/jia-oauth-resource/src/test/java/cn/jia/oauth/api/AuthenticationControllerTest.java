package cn.jia.oauth.api;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticationControllerTest {

    private final AuthenticationController controller = new AuthenticationController();

    @Test
    void returnsOnlyAllowlistedIdentityFieldsWithSortedUniqueScopes() {
        Jwt jwt = jwt(Map.of(
                "sub", "user-17",
                "client_id", "public-web",
                "username", "alice",
                "jiacn", "jia-17",
                "scope", List.of("write", "openid", "write"),
                "access_token", "must-not-leak",
                "arbitrary_claim", Map.of("private", true)));

        OAuthResourceIdentityDTO identity = controller.resource(authenticated(jwt));

        assertEquals("user-17", identity.subject());
        assertEquals("public-web", identity.clientId());
        assertEquals("alice", identity.username());
        assertEquals("jia-17", identity.jiacn());
        assertEquals(List.of("openid", "write"), identity.scopes());
        assertEquals(5, OAuthResourceIdentityDTO.class.getRecordComponents().length);
    }

    @Test
    void acceptsMachineIdentityWithoutOptionalUserClaims() {
        OAuthResourceIdentityDTO identity = controller.resource(authenticated(jwt(Map.of(
                "sub", "machine-client",
                "client_id", "machine-client"))));

        assertEquals("machine-client", identity.subject());
        assertEquals("machine-client", identity.clientId());
        assertNull(identity.username());
        assertNull(identity.jiacn());
        assertEquals(List.of(), identity.scopes());
    }

    @Test
    void preservesNonblankIdentityClaimsByteForByte() {
        OAuthResourceIdentityDTO identity = controller.resource(authenticated(jwt(Map.of(
                "sub", "  user-17  ",
                "client_id", " public-web ",
                "username", " alice ",
                "jiacn", " jia-17 "))));

        assertEquals("  user-17  ", identity.subject());
        assertEquals(" public-web ", identity.clientId());
        assertEquals(" alice ", identity.username());
        assertEquals(" jia-17 ", identity.jiacn());
        assertEquals(List.of(), identity.scopes());
    }

    @Test
    void rejectsMissingOrMalformedRequiredClaims() {
        assertUnauthorized(jwt(Map.of("client_id", "client", "scope", "openid")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "scope", "openid")));
        assertUnauthorized(jwt(Map.of("sub", " ", "client_id", "client")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "\t")));
        assertUnauthorized(jwt(Map.of("sub", 17, "client_id", "client", "scope", "openid")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", List.of("client"), "scope", "openid")));
    }

    @Test
    void rejectsBlankOrMalformedOptionalClaimsAndMalformedPresentScope() {
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "username", " ")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "jiacn", List.of("jia"))));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "scope", "\t")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "scope", List.of("openid", 17))));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "scope", List.of("openid", " "))));
    }

    private void assertUnauthorized(Jwt jwt) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.resource(authenticated(jwt)));
        assertEquals(401, error.getStatusCode().value());
    }

    private static JwtAuthenticationToken authenticated(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuedAt(Instant.parse("2026-08-23T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-23T01:00:00Z"));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
