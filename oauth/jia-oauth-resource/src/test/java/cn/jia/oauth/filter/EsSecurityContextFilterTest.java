package cn.jia.oauth.filter;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
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
                "token_kind", "user", "jiacn", "Jia-A", "username", "alice", "client_id", "web")));

        new EsSecurityContextFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
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
                "token_kind", "machine", "sub", "agent", "client_id", "agent")));

        new EsSecurityContextFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> {
                    EsContext current = EsContextHolder.getContext();
                    assertNull(current.getJiacn());
                    assertNull(current.getUsername());
                    assertNull(current.getAppcn());
                    assertEquals("agent", current.getClientId());
                });
    }

    private static JwtAuthenticationToken auth(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(), AuthorityUtils.NO_AUTHORITIES);
    }
}
