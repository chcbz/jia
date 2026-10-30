package cn.jia.oauth.filter;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EsSecurityContextFilterTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        EsContextHolder.clearContext();
    }

    @Test
    void bearerClaimsOverwriteCookieIdentityFieldByField() throws Exception {
        EsContext cookie = EsContextHolder.getContext();
        cookie.setJiacn("cookie-user");
        cookie.setUsername("cookie-name");
        cookie.setAppcn("cookie-app");
        cookie.setClientId("cookie-client");
        SecurityContextHolder.getContext().setAuthentication(auth(Map.of(
                "token_kind", "user", "jiacn", "Jia-A", "username", "alice", "client_id", "web", "tenant_id", "0")));

        productionFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> {
                    EsContext current = EsContextHolder.getContext();
                    assertEquals("Jia-A", current.getJiacn());
                    assertEquals("alice", current.getUsername());
                    assertEquals("web", current.getClientId());
                    assertNull(current.getAppcn());
                });
    }

    @Test
    void machineBearerClearsAllCookieUserFields() throws Exception {
        EsContext cookie = EsContextHolder.getContext();
        cookie.setJiacn("cookie-user");
        cookie.setUsername("cookie-name");
        cookie.setAppcn("cookie-app");
        cookie.setClientId("cookie-client");
        SecurityContextHolder.getContext().setAuthentication(auth(Map.of(
                "token_kind", "machine", "sub", "agent", "client_id", "agent",
                "tenant_id", "0", "tenant_claim_version", "1")));

        productionFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> {
                    EsContext current = EsContextHolder.getContext();
                    assertNull(current.getJiacn());
                    assertNull(current.getUsername());
                    assertNull(current.getAppcn());
                    assertEquals("agent", current.getClientId());
                });
    }


    @Test
    void conflictingTenantClaimsFailClosedThroughSharedPolicy() {
        SecurityContextHolder.getContext().setAuthentication(auth(Map.of(
                "token_kind", "user", "jiacn", "Jia-A", "client_id", "web",
                "tenant_id", "tenant-a", "tenantId", "tenant-b")));
        var chain = mock(jakarta.servlet.FilterChain.class);
        assertThrows(jakarta.servlet.ServletException.class,
                () -> productionFilter().doFilter(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), chain));
        assertNull(EsContextHolder.getContext().getTenantId());
        verifyNoInteractions(chain);
    }

    @Test
    void matchingAliasAndCanonicalTenantAreAccepted() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(auth(Map.of(
                "token_kind", "user", "jiacn", "Jia-A", "client_id", "web",
                "tenant_id", "tenant-a", "tenantId", "tenant-a", "tenant_claim_version", "1")));
        new EsSecurityContextFilter(new FixedTenantAdmissionPolicy("tenant-a", "web"))
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                        (request, response) -> assertEquals("tenant-a", EsContextHolder.getContext().getTenantId()));
    }

    @Test
    void configuredTenantOneRequiresExactSignedClientAndIgnoresSpoofHeaders() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(auth(Map.of(
                "token_kind", "user", "jiacn", "Jia-A", "client_id", "cyf-local",
                "tenant_id", "1", "tenant_claim_version", "1")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "0");
        new EsSecurityContextFilter(new FixedTenantAdmissionPolicy("1", "cyf-local"))
                .doFilter(request, new MockHttpServletResponse(), (a, b) ->
                        assertEquals("1", EsContextHolder.getContext().getTenantId()));
        var fixed = new EsSecurityContextFilter(new FixedTenantAdmissionPolicy("1", "cyf-local"));
        SecurityContextHolder.getContext().setAuthentication(auth(Map.of(
                "token_kind", "user", "jiacn", "Jia-A", "client_id", "other",
                "tenant_id", "1", "tenant_claim_version", "1")));
        assertThrows(jakarta.servlet.ServletException.class, () ->
                fixed.doFilter(request, new MockHttpServletResponse(), mock(jakarta.servlet.FilterChain.class)));
        SecurityContextHolder.getContext().setAuthentication(auth(Map.of(
                "token_kind", "user", "jiacn", "Jia-A", "client_id", "production-client",
                "tenant_id", "0", "tenant_claim_version", "1")));
        assertThrows(jakarta.servlet.ServletException.class, () ->
                fixed.doFilter(request, new MockHttpServletResponse(), mock(jakarta.servlet.FilterChain.class)));
    }

    private static EsSecurityContextFilter productionFilter() {
        return new EsSecurityContextFilter(new FixedTenantAdmissionPolicy("0", null));
    }

    @Test
    void missingTenantAndAliasOnlyNeverBorrowCookieOrDeploymentTenant() {
        var chain = mock(jakarta.servlet.FilterChain.class);
        for (Map<String, Object> claims : java.util.List.<Map<String, Object>>of(
                Map.of("jiacn", "owner", "client_id", "web"),
                Map.of("jiacn", "owner", "client_id", "web", "tenantId", "0"))) {
            EsContextHolder.getContext().setTenantId("0");
            SecurityContextHolder.getContext().setAuthentication(auth(claims));
            assertThrows(jakarta.servlet.ServletException.class, () -> productionFilter().doFilter(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), chain));
            assertNull(EsContextHolder.getContext().getTenantId());
        }
        verifyNoInteractions(chain);
    }

    private static JwtAuthenticationToken auth(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(), AuthorityUtils.NO_AUTHORITIES);
    }
}
