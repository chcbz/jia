package cn.jia.oauth.api;

import cn.jia.core.entity.JsonResult;
import cn.jia.oauth.config.ResourceServerConfig.TenantDeploymentProperties;
import cn.jia.oauth.security.TenantAdmissionPolicy;
import cn.jia.oauth.security.UserAdmissionScopeResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthenticationControllerTest {

    private final OAuthResourceIdentityController controller = new OAuthResourceIdentityController();

    @Test
    void returnsTopLevelAllowlistedIdentityWithSortedUniqueScopes() {
        Jwt jwt = jwt(Map.of(
                "sub", "user-17",
                "client_id", "public-web",
                "username", "alice",
                "jiacn", "jia-17",
                "scope", List.of("write", "openid", "write"),
                "access_token", "must-not-leak",
                "arbitrary_claim", Map.of("private", true)));

        ResponseEntity<JsonResult<OAuthResourceIdentityDTO>> response =
                controller.resource(authenticated(jwt));
        OAuthResourceIdentityDTO identity = successData(response);

        assertEquals("user-17", identity.subject());
        assertEquals("public-web", identity.clientId());
        assertEquals("alice", identity.username());
        assertEquals("jia-17", identity.jiacn());
        assertEquals(List.of("openid", "write"), identity.scopes());
        assertEquals(13, OAuthResourceIdentityDTO.class.getRecordComponents().length);
        assertNull(identity.tenantId());
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
    void returnsTheCompleteTenantIdentityBundleWithNoStore() {
        ResponseEntity<JsonResult<OAuthResourceIdentityDTO>> response =
                controller.resource(authenticated(jwt(tenantUserClaims())));

        OAuthResourceIdentityDTO identity = successData(response);
        assertEquals("1", identity.tenantId());
        assertEquals("cyf", identity.dataDomainId());
        assertEquals("华南测试区", identity.tenantName());
        assertEquals("TEST", identity.tenantKind());
        assertEquals("3", identity.tenantSessionEpoch());
        assertEquals("8", identity.membershipRevision());
        assertEquals("4", identity.clientTenantGrantRevision());
        assertEquals("https://auth.example", identity.issuer());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
    }

    @Test
    void fixedTenantReturnsOnlyTheVerifiedBasicIdentityWithoutPlatformVersions() {
        ResponseEntity<JsonResult<OAuthResourceIdentityDTO>> response = fixedController().resource(
                authenticated(jwt(fixedTenantUserClaims("1"))));

        OAuthResourceIdentityDTO identity = successData(response);
        assertEquals("user-17", identity.subject());
        assertEquals("public-web", identity.clientId());
        assertEquals("alice", identity.username());
        assertEquals("jia-17", identity.jiacn());
        assertEquals(List.of("openid", "write"), identity.scopes());
        assertEquals("https://issuer.example", identity.issuer());
        assertEquals("1", identity.tenantId());
        assertNull(identity.dataDomainId());
        assertNull(identity.tenantName());
        assertNull(identity.tenantKind());
        assertNull(identity.tenantSessionEpoch());
        assertNull(identity.membershipRevision());
        assertNull(identity.clientTenantGrantRevision());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
    }

    @Test
    void fixedTenantRejectsOtherMissingMachineAndConflictingTenantIdentity() {
        assertUnauthorized(fixedController(), jwt(fixedTenantUserClaims("0")));

        Map<String, Object> missingTenant = fixedTenantUserClaims("1");
        missingTenant.remove("tenant_id");
        assertUnauthorized(fixedController(), jwt(missingTenant));

        assertUnauthorized(fixedController(), jwt(Map.of(
                "token_kind", "machine",
                "sub", "machine-client",
                "client_id", "machine-client",
                "tenant_id", "1",
                "iss", "https://issuer.example")));

        Map<String, Object> conflictingAlias = fixedTenantUserClaims("1");
        conflictingAlias.put("tenantId", "0");
        assertUnauthorized(fixedController(), jwt(conflictingAlias));

        Map<String, Object> conflictingPlatformBundle = tenantUserClaims();
        conflictingPlatformBundle.put("data_domain_id", "other");
        assertUnauthorized(fixedController(), jwt(conflictingPlatformBundle));
    }

    @Test
    void rejectsAPartialOrMalformedTenantIdentityBundle() {
        assertUnauthorized(jwt(Map.of("sub", "user", "client_id", "web", "tenant_id", "1")));
        Map<String, Object> missingDomain = tenantUserClaims();
        missingDomain.remove("data_domain_id");
        assertUnauthorized(jwt(missingDomain));
        Map<String, Object> missingIssuer = tenantUserClaims();
        missingIssuer.remove("iss");
        assertUnauthorized(jwt(missingIssuer));
        Map<String, Object> malformedDomain = tenantUserClaims();
        malformedDomain.put("data_domain_id", "CyF");
        assertUnauthorized(jwt(malformedDomain));
        Map<String, Object> machineWithTenant = tenantUserClaims();
        machineWithTenant.put("token_kind", "machine");
        assertUnauthorized(jwt(machineWithTenant));
        Map<String, Object> legacyUserWithTenant = tenantUserClaims();
        legacyUserWithTenant.remove("token_kind");
        assertUnauthorized(jwt(legacyUserWithTenant));
        Map<String, Object> missingUid = tenantUserClaims();
        missingUid.remove("uid");
        assertUnauthorized(jwt(missingUid));
        Map<String, Object> overflowRevision = tenantUserClaims();
        overflowRevision.put("membership_revision", "9223372036854775808");
        assertUnauthorized(jwt(overflowRevision));
        assertUnauthorized(jwt(Map.of("sub", "user", "client_id", "web", "tenant_id", "01",
                "tenant_name", "test", "tenant_kind", "TEST", "tenant_session_epoch", "1",
                "membership_revision", "1", "client_tenant_grant_revision", "1")));
        assertUnauthorized(jwt(Map.of("sub", "user", "client_id", "web", "tenant_id", "1",
                "tenant_name", "test", "tenant_kind", "OTHER", "tenant_session_epoch", "1",
                "membership_revision", "1", "client_tenant_grant_revision", "1")));
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
    void rejectsMissingBlankOrMalformedRequiredClaimsWithErrorEnvelope401() {
        assertUnauthorized(jwt(Map.of("client_id", "client")));
        assertUnauthorized(jwt(Map.of("sub", "subject")));
        assertUnauthorized(jwt(Map.of("sub", " ", "client_id", "client")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", "\t")));
        assertUnauthorized(jwt(Map.of("sub", 17, "client_id", "client")));
        assertUnauthorized(jwt(Map.of("sub", "subject", "client_id", List.of("client"))));
    }

    @Test
    void rejectsBlankOrMalformedOptionalClaimsAndMalformedPresentScopeWithErrorEnvelope401() {
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

    private static Map<String, Object> tenantUserClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "user-17");
        claims.put("client_id", "public-web");
        claims.put("token_kind", "user");
        claims.put("uid", "17");
        claims.put("jiacn", "jia-17");
        claims.put("username", "alice");
        claims.put("auth_epoch", 0L);
        claims.put("tenant_id", "1");
        claims.put("data_domain_id", "cyf");
        claims.put("tenant_name", "华南测试区");
        claims.put("tenant_kind", "TEST");
        claims.put("tenant_session_epoch", "3");
        claims.put("membership_revision", "8");
        claims.put("client_tenant_grant_revision", "4");
        claims.put("iss", "https://auth.example");
        return claims;
    }

    private static Map<String, Object> fixedTenantUserClaims(String tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("token_kind", "user");
        claims.put("uid", "17");
        claims.put("auth_epoch", 4L);
        claims.put("sub", "user-17");
        claims.put("client_id", "public-web");
        claims.put("username", "alice");
        claims.put("jiacn", "jia-17");
        claims.put("scope", List.of("write", "openid", "write"));
        claims.put("tenant_id", tenantId);
        claims.put("iss", "https://issuer.example");
        return claims;
    }

    private static OAuthResourceIdentityController fixedController() {
        TenantDeploymentProperties properties = new TenantDeploymentProperties();
        properties.setAdmissionMode(TenantAdmissionPolicy.Mode.FIXED_TENANT);
        properties.setFixedTenantId("1");
        TenantAdmissionPolicy policy = TenantAdmissionPolicy.from(properties);
        return new OAuthResourceIdentityController(properties, new UserAdmissionScopeResolver(policy));
    }

    private static OAuthResourceIdentityDTO successData(
            ResponseEntity<JsonResult<OAuthResourceIdentityDTO>> response) {
        assertEquals(200, response.getStatusCode().value());
        JsonResult<OAuthResourceIdentityDTO> body = response.getBody();
        assertNotNull(body);
        assertEquals("E0", body.getCode());
        assertEquals("ok", body.getMsg());
        assertNotNull(body.getData());
        return body.getData();
    }

    private void assertUnauthorized(Jwt jwt) {
        assertUnauthorized(controller, jwt);
    }

    private void assertUnauthorized(OAuthResourceIdentityController target, Jwt jwt) {
        ResponseEntity<JsonResult<OAuthResourceIdentityDTO>> response =
                target.resource(authenticated(jwt));
        assertEquals(401, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getProtocolVersion());
        assertEquals("E401", response.getBody().getCode());
        assertEquals("Authentication required", response.getBody().getMsg());
        assertNull(response.getBody().getData());
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
