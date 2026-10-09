package cn.jia.oauth.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyAgentTenantBoundaryFilterTest {
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void strictResourceLaneRejectsForeignRegionBeforeDispatch() throws Exception {
        for (JwtAuthenticationToken auth : List.of(jwt("1", "cyf"), jwt("00", "cyf"),
                jwt("0", "other"), jwt(null, "cyf"), jwt("0", null))) {
            for (String path : List.of("/agent/tasks/task-1/assign", "/chat/task-threads/task-1/team",
                    "/archive/v1/me/notes", "/juyiting/actions/i/dispatch",
                    "/task/create", "/job/execute", "/economy/wallet", "/point/sign",
                    "/workflow/start/definition/business", "/tip/delete", "/user/point/change",
                    "/user/info/update", "/user/me/sessions", "/phrase/private",
                    "/kefu/messages", "/media/private", "/gift/grant")) {
                SecurityContextHolder.getContext().setAuthentication(auth);
                MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
                MockHttpServletResponse response = new MockHttpServletResponse();
                MockFilterChain downstream = new MockFilterChain();
                new LegacyAgentTenantBoundaryFilter(true).doFilter(request, response, downstream);
                assertEquals(403, response.getStatus(), path);
                assertEquals("private, no-store", response.getHeader("Cache-Control"));
                assertNull(downstream.getRequest(), path);
            }
        }
    }

    @Test
    void strictResourceLaneAllowsOnlyMigratedConversationMethodsForTestAreas() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(jwt("1", "cyf"));
        for (MockHttpServletRequest request : List.of(
                new MockHttpServletRequest("POST", "/chat/stream"),
                new MockHttpServletRequest("POST", "/chat/stop_stream"),
                new MockHttpServletRequest("DELETE", "/chat/conversation/delete"),
                new MockHttpServletRequest("GET", "/chat/conversation/content"),
                new MockHttpServletRequest("GET", "/chat/conversation/events"),
                new MockHttpServletRequest("POST", "/chat/conversation/list"),
                new MockHttpServletRequest("POST", "/chat/conversation/update"),
                new MockHttpServletRequest("POST", "/chat/library/search"),
                new MockHttpServletRequest("POST", "/chat/library/documents"))) {
            MockFilterChain downstream = new MockFilterChain();
            new LegacyAgentTenantBoundaryFilter(true).doFilter(
                    request, new MockHttpServletResponse(), downstream);
            assertNotNull(downstream.getRequest(), request.getMethod() + " " + request.getRequestURI());
        }

        MockHttpServletResponse wrongMethod = new MockHttpServletResponse();
        MockFilterChain blocked = new MockFilterChain();
        new LegacyAgentTenantBoundaryFilter(true).doFilter(
                new MockHttpServletRequest("GET", "/chat/stream"), wrongMethod, blocked);
        assertEquals(403, wrongMethod.getStatus());
        assertNull(blocked.getRequest());
    }

    @Test
    void strictResourceLaneAcceptsOnlyExactCentralRegionAndPreservesOtherRoutes() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(jwt("0", "cyf"));
        MockFilterChain central = new MockFilterChain();
        new LegacyAgentTenantBoundaryFilter(true).doFilter(
                new MockHttpServletRequest("GET", "/agent/roster"),
                new MockHttpServletResponse(), central);
        assertNotNull(central.getRequest());
        for (String path : List.of("/chat/conversation/list", "/archive/v1/me/notes",
                "/juyiting/actions/task/dispatch", "/task/search", "/job/list",
                "/economy/wallet", "/point/sign", "/workflow/list/my", "/tip/list",
                "/user/point/change")) {
            MockFilterChain allowed = new MockFilterChain();
            new LegacyAgentTenantBoundaryFilter(true).doFilter(
                    new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), allowed);
            assertNotNull(allowed.getRequest(), path);
        }

        SecurityContextHolder.getContext().setAuthentication(jwt("1", "cyf"));
        MockFilterChain unrelated = new MockFilterChain();
        new LegacyAgentTenantBoundaryFilter(true).doFilter(
                new MockHttpServletRequest("GET", "/user/my"),
                new MockHttpServletResponse(), unrelated);
        assertNotNull(unrelated.getRequest());

        MockFilterChain revoke = new MockFilterChain();
        new LegacyAgentTenantBoundaryFilter(true).doFilter(
                new MockHttpServletRequest("POST", "/user/me/sessions/revoke-all"),
                new MockHttpServletResponse(), revoke);
        assertNotNull(revoke.getRequest());

        MockFilterChain compatibility = new MockFilterChain();
        new LegacyAgentTenantBoundaryFilter(false).doFilter(
                new MockHttpServletRequest("GET", "/agent/map"),
                new MockHttpServletResponse(), compatibility);
        assertNotNull(compatibility.getRequest());
    }

    @Test
    void strictExceptionsAreMethodScopedAndPublicPathsRemainPublic() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(jwt("1", "cyf"));
        for (MockHttpServletRequest request : List.of(
                new MockHttpServletRequest("POST", "/user/my"),
                new MockHttpServletRequest("GET", "/user/me/sessions/revoke-all"))) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain downstream = new MockFilterChain();
            new LegacyAgentTenantBoundaryFilter(true).doFilter(request, response, downstream);
            assertEquals(403, response.getStatus());
            assertNull(downstream.getRequest());
        }
        for (String path : List.of("/dwz/view/link", "/wx/mp/checksignature")) {
            MockFilterChain downstream = new MockFilterChain();
            new LegacyAgentTenantBoundaryFilter(true).doFilter(
                    new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), downstream);
            assertNotNull(downstream.getRequest(), path);
        }
    }

    @Test
    void strictResourceLaneRejectsAbsentOrNonJwtAuthentication() throws Exception {
        MockFilterChain downstream = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LegacyAgentTenantBoundaryFilter(true).doFilter(
                new MockHttpServletRequest("GET", "/agent/map"), response, downstream);
        assertEquals(401, response.getStatus());
        assertNull(downstream.getRequest());
    }

    @Test
    void strictModeClosesUnscopedChatWebsocketEvenForTenantZero() throws Exception {
        for (JwtAuthenticationToken auth : List.of(jwt("0", "cyf"), jwt("1", "cyf"))) {
            SecurityContextHolder.getContext().setAuthentication(auth);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain downstream = new MockFilterChain();
            new LegacyAgentTenantBoundaryFilter(true).doFilter(
                    new MockHttpServletRequest("GET", "/ws/chat"), response, downstream);
            assertEquals(403, response.getStatus());
            assertEquals("private, no-store", response.getHeader("Cache-Control"));
            assertNull(downstream.getRequest());
        }
        MockFilterChain compatibility = new MockFilterChain();
        new LegacyAgentTenantBoundaryFilter(false).doFilter(
                new MockHttpServletRequest("GET", "/ws/chat"), new MockHttpServletResponse(), compatibility);
        assertNotNull(compatibility.getRequest());
    }

    private static JwtAuthenticationToken jwt(String area, String domain) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("user-a");
        if (area != null) builder.claim("tenant_id", area);
        if (domain != null) builder.claim("data_domain_id", domain);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }
}
