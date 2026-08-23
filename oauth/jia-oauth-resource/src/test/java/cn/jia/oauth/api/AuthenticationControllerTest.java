package cn.jia.oauth.api;

import cn.jia.core.entity.JsonResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthenticationControllerTest {

    private final AuthenticationController controller = new AuthenticationController();

    @Test
    void returnsNormalSuccessEnvelopeWithAllowlistedIdentityAndSortedUniqueScopes() {
        Jwt jwt = jwt(Map.of(
                "sub", "user-17",
                "client_id", "public-web",
                "username", "alice",
                "jiacn", "jia-17",
                "scope", List.of("write", "openid", "write"),
                "access_token", "must-not-leak",
                "arbitrary_claim", Map.of("private", true)));

        ResponseEntity<JsonResult<OAuthResourceIdentityDTO>> response = controller.resource(authenticated(jwt));
        OAuthResourceIdentityDTO identity = successData(response);

        assertEquals("E0", response.getBody().getCode());
        assertEquals("ok", response.getBody().getMsg());
        assertEquals(200, response.getBody().getStatus());
        assertEquals("user-17", identity.subject());
        assertEquals("public-web", identity.clientId());
        assertEquals("alice", identity.username());
        assertEquals("jia-17", identity.jiacn());
        assertEquals(List.of("openid", "write"), identity.scopes());
        assertEquals(5, OAuthResourceIdentityDTO.class.getRecordComponents().length);
    }

    @Test
    void acceptsMachineIdentityWithAbsentScopeAsDeterministicEmptyList() {
        OAuthResourceIdentityDTO identity = successData(controller.resource(authenticated(jwt(Map.of(
                "sub", "machine-client",
                "client_id", "machine-client")))));

        assertEquals("machine-client", identity.subject());
        assertEquals("machine-client", identity.clientId());
        assertNull(identity.username());
        assertNull(identity.jiacn());
        assertEquals(List.of(), identity.scopes());
    }

    @Test
    void acceptsStructurallyValidScopeStringAndEmptyCollection() {
        OAuthResourceIdentityDTO stringScopes = successData(controller.resource(authenticated(jwt(Map.of(
                "sub", "machine-client",
                "client_id", "machine-client",
                "scope", "task.read  agent.execute task.read")))));
        OAuthResourceIdentityDTO emptyScopes = successData(controller.resource(authenticated(jwt(Map.of(
                "sub", "machine-client",
                "client_id", "machine-client",
                "scope", List.of())))));
        OAuthResourceIdentityDTO collectionScopes = successData(controller.resource(authenticated(jwt(Map.of(
                "sub", "machine-client",
                "client_id", "machine-client",
                "scope", List.of("scope:α", "READ", "read.write", "scope:α"))))));

        assertEquals(List.of("agent.execute", "task.read"), stringScopes.scopes());
        assertEquals(List.of(), emptyScopes.scopes());
        assertEquals(List.of("READ", "read.write", "scope:α"), collectionScopes.scopes());
    }

    @Test
    void preservesAllNonblankIdentityClaimsByteForByte() {
        OAuthResourceIdentityDTO identity = successData(controller.resource(authenticated(jwt(Map.of(
                "sub", "  user-17  ",
                "client_id", " public-web ",
                "username", " alice ",
                "jiacn", " jia-17 ")))));

        assertEquals("  user-17  ", identity.subject());
        assertEquals(" public-web ", identity.clientId());
        assertEquals(" alice ", identity.username());
        assertEquals(" jia-17 ", identity.jiacn());
        assertEquals(List.of(), identity.scopes());
    }

    @Test
    void rejectsMissingBlankOrMalformedRequiredClaimsWithDirect401() {
        assertUnauthorized(jwt(Map.of("client_id", "client")));
        assertUnauthorized(jwt(Map.of("sub", "subject")));
        assertUnauthorized(jwt(Map.of("sub", " ", "client_id", "client")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "\t")));
        assertUnauthorized(jwt(Map.of("sub", 17, "client_id", "client")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", List.of("client"))));
    }

    @Test
    void rejectsBlankOrMalformedOptionalClaimsAndMalformedPresentScopeWithDirect401() {
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "username", " ")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "jiacn", List.of("jia"))));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "scope", "\t")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "scope", 17)));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "client", "scope", List.of("openid", 17))));
        for (String malformedScope : List.of(
                "",
                " ",
                " read ",
                "read write",
                "read\twrite",
                "read\nwrite")) {
            assertUnauthorized(jwt(Map.of(
                    "sub", "subject",
                    "client_id", "client",
                    "scope", List.of("openid", malformedScope))));
        }
    }

    @Test
    void rejectsEveryUnicodeWhiteSpaceCodePointInsideCollectionScopeMembers() {
        int[] unicodeWhiteSpaceCodePoints = {
                0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
                0x0020, 0x0085, 0x00A0, 0x1680,
                0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
                0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
                0x2028, 0x2029, 0x202F, 0x205F, 0x3000
        };

        for (int codePoint : unicodeWhiteSpaceCodePoints) {
            String malformedScope = "read" + Character.toString(codePoint) + "write";
            assertUnauthorized(jwt(Map.of(
                    "sub", "subject",
                    "client_id", "client",
                    "scope", List.of("openid", malformedScope))));
        }
    }

    private static OAuthResourceIdentityDTO successData(
            ResponseEntity<JsonResult<OAuthResourceIdentityDTO>> response) {
        assertEquals(200, response.getStatusCode().value());
        return response.getBody().getData();
    }

    private void assertUnauthorized(Jwt jwt) {
        ResponseEntity<JsonResult<OAuthResourceIdentityDTO>> response = controller.resource(authenticated(jwt));
        assertEquals(401, response.getStatusCode().value());
        assertNull(response.getBody());
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
