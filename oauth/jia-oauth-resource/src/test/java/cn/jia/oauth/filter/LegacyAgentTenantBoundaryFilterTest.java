package cn.jia.oauth.filter;

import cn.jia.oauth.config.ResourceServerConfig.TenantDeploymentProperties;
import cn.jia.oauth.security.TenantAdmissionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void fixedTenantLocalUserCanReachOnlyExactMigratedBusinessMethods() throws Exception {
        LegacyAgentTenantBoundaryFilter filter = new LegacyAgentTenantBoundaryFilter(fixedTenantPolicy());
        SecurityContextHolder.getContext().setAuthentication(fixedUser("1"));
        for (Endpoint endpoint : fixedTenantBusinessEndpoints()) {
            MockHttpServletRequest request = endpoint.request();
            MockFilterChain downstream = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), downstream);
            assertNotNull(downstream.getRequest(), request.getMethod() + " " + request.getRequestURI());
        }
    }

    @Test
    void fixedTenantRouteMatchingRejectsWrongMethodsSimilarPathsAndUnmigratedCapabilities() throws Exception {
        LegacyAgentTenantBoundaryFilter filter = new LegacyAgentTenantBoundaryFilter(fixedTenantPolicy());
        SecurityContextHolder.getContext().setAuthentication(fixedUser("1"));
        for (Endpoint endpoint : List.of(
                new Endpoint("GET", "/chat/stream"),
                new Endpoint("GET", "/agent/roster"),
                new Endpoint("POST", "/agent/map"),
                new Endpoint("POST", "/agent/hall/overview"),
                new Endpoint("POST", "/agent/hall/items"),
                new Endpoint("GET", "/agent/hall/overview/extra"),
                new Endpoint("GET", "/agent/maps"),
                new Endpoint("GET", "/agent/tasks/task-1/cancel"),
                new Endpoint("POST", "/agent/tasks"),
                new Endpoint("POST", "/agent/tasks/task-1/funding/cancel"),
                new Endpoint("POST", "/agent/personas/coach/hosting-rent/quotes"),
                new Endpoint("POST", "/agent/tasks/task-1/point-and-start-cost-consents"),
                new Endpoint("POST", "/agent/hall/drafts/draft-1/submit"),
                new Endpoint("GET", "/chat/conversations/conversation-1/requests/request-1/inspection-outcome"),
                new Endpoint("POST", "/agent/runtime/v1/installations"),
                new Endpoint("POST", "/agent/runtime/v2/registrations"),
                new Endpoint("GET", "/ws/chat"))) {
            assertFixedTenantDenied(filter, endpoint);
        }
    }

    @Test
    void fixedTenantBusinessPathRejectsForeignWrongClientMachineAndConflictingDomain() throws Exception {
        LegacyAgentTenantBoundaryFilter filter = new LegacyAgentTenantBoundaryFilter(fixedTenantPolicy());
        for (JwtAuthenticationToken authentication : List.of(
                fixedUser("0"), fixedUser("1", "other-client"), machine(),
                fixedUserWithPlatformDomain("other"))) {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            assertFixedTenantDenied(filter,
                    new Endpoint("POST", "/chat/conversation/list"));
        }

        SecurityContextHolder.clearContext();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain downstream = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("POST", "/chat/conversation/list"),
                response, downstream);
        assertEquals(401, response.getStatus());
        assertNull(downstream.getRequest());
    }

    @Test
    void fixedTenantDoesNotConvertDownstreamFailureIntoIdentityDenial() {
        LegacyAgentTenantBoundaryFilter filter = new LegacyAgentTenantBoundaryFilter(fixedTenantPolicy());
        SecurityContextHolder.getContext().setAuthentication(fixedUser("1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalStateException.class, () -> filter.doFilter(
                new MockHttpServletRequest("POST", "/chat/conversation/update"), response,
                (request, servletResponse) -> { throw new IllegalStateException("downstream failed"); }));
        assertEquals(200, response.getStatus());
    }

    private static List<Endpoint> fixedTenantBusinessEndpoints() {
        return List.of(
                new Endpoint("POST", "/chat/stream"),
                new Endpoint("POST", "/chat/stop_stream"),
                new Endpoint("DELETE", "/chat/conversation/delete"),
                new Endpoint("GET", "/chat/conversation/content"),
                new Endpoint("GET", "/chat/conversation/events"),
                new Endpoint("POST", "/chat/conversation/list"),
                new Endpoint("POST", "/chat/conversation/update"),
                new Endpoint("POST", "/chat/library/search"),
                new Endpoint("POST", "/chat/library/documents"),
                new Endpoint("GET", "/chat/capabilities"),
                new Endpoint("GET", "/chat/requests/request-1"),
                new Endpoint("GET", "/chat/turns/turn-1"),
                new Endpoint("POST", "/chat/turns/turn-1/cancel"),
                new Endpoint("POST", "/chat/requests/request-1/cancel"),
                new Endpoint("POST", "/chat/conversations/conversation-1/interactions/discussion"),
                new Endpoint("GET", "/chat/conversations/conversation-1/requests/request-1/typed-outcome"),
                new Endpoint("GET", "/agent/map"),
                new Endpoint("POST", "/agent/roster"),
                new Endpoint("GET", "/agent/personas/catalog"),
                new Endpoint("GET", "/agent/hall/overview"),
                new Endpoint("GET", "/agent/hall/items"),
                new Endpoint("POST", "/agent/tasks/search"),
                new Endpoint("POST", "/agent/tasks/status-counts"),
                new Endpoint("GET", "/agent/tasks/task-1"),
                new Endpoint("POST", "/agent/tasks/task-1/cancel"),
                new Endpoint("POST", "/agent/tasks/creation-operations"),
                new Endpoint("GET", "/agent/tasks/creation-operations/request"),
                new Endpoint("POST", "/agent/tasks/creation-operations/v2"),
                new Endpoint("GET", "/agent/tasks/creation-operations/v2/request"),
                new Endpoint("POST", "/agent/hall/drafts"),
                new Endpoint("GET", "/agent/hall/drafts"),
                new Endpoint("GET", "/agent/hall/drafts/draft-1"),
                new Endpoint("PUT", "/agent/hall/drafts/draft-1"),
                new Endpoint("POST", "/agent/hall/drafts/draft-1/discard"),
                new Endpoint("GET", "/agent/personal-workspace/files"),
                new Endpoint("POST", "/agent/personal-workspace/files"),
                new Endpoint("GET", "/agent/personal-workspace/files/file-1"),
                new Endpoint("PATCH", "/agent/personal-workspace/files/file-1"),
                new Endpoint("GET", "/agent/personal-workspace/files/file-1/versions"),
                new Endpoint("POST", "/agent/personal-workspace/files/file-1/versions"),
                new Endpoint("GET", "/agent/personal-workspace/files/file-1/versions/1/content"),
                new Endpoint("GET", "/agent/personal-workspace/files/file-1/versions/1/preview"),
                new Endpoint("GET", "/agent/personal-workspace/files/file-1/versions/1/preview/parts/part-1"),
                new Endpoint("GET", "/agent/personal-workspace/files/file-1/usage"),
                new Endpoint("POST", "/agent/personal-workspace/files/file-1/trash"),
                new Endpoint("POST", "/agent/personal-workspace/files/file-1/restore"),
                new Endpoint("GET", "/agent/personal-workspace/operations/operation-1"),
                new Endpoint("GET", "/agent/conversations/conversation-1/file-links"),
                new Endpoint("POST", "/agent/conversations/conversation-1/file-links"),
                new Endpoint("DELETE", "/agent/conversations/conversation-1/file-links/relation-1"),
                new Endpoint("GET", "/agent/tasks/task-1/file-links"),
                new Endpoint("POST", "/agent/tasks/task-1/file-links"),
                new Endpoint("DELETE", "/agent/tasks/task-1/file-links/relation-1"));
    }

    private static void assertFixedTenantDenied(LegacyAgentTenantBoundaryFilter filter,
            Endpoint endpoint) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain downstream = new MockFilterChain();
        filter.doFilter(endpoint.request(), response, downstream);
        assertEquals(403, response.getStatus(), endpoint.toString());
        assertEquals("private, no-store", response.getHeader("Cache-Control"), endpoint.toString());
        assertNull(downstream.getRequest(), endpoint.toString());
    }

    private static JwtAuthenticationToken jwt(String area, String domain) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("user-a");
        if (area != null) builder.claim("tenant_id", area);
        if (domain != null) builder.claim("data_domain_id", domain);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    private static TenantAdmissionPolicy fixedTenantPolicy() {
        TenantDeploymentProperties properties = new TenantDeploymentProperties();
        properties.setAdmissionMode(TenantAdmissionPolicy.Mode.FIXED_TENANT);
        properties.setFixedTenantId("1");
        return TenantAdmissionPolicy.from(properties);
    }

    private static JwtAuthenticationToken fixedUser(String tenantId) {
        return fixedUser(tenantId, "cyf-local");
    }

    private static JwtAuthenticationToken fixedUser(String tenantId, String clientId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "alice");
        claims.put("client_id", clientId);
        claims.put("token_kind", "user");
        claims.put("uid", "17");
        claims.put("jiacn", "Jia-A");
        claims.put("username", "alice");
        claims.put("auth_epoch", 4L);
        claims.put("tenant_id", tenantId);
        return jwt(claims);
    }

    private static JwtAuthenticationToken fixedUserWithPlatformDomain(String domain) {
        Map<String, Object> claims = new HashMap<>(fixedUser("1").getToken().getClaims());
        claims.put("data_domain_id", domain);
        claims.put("tenant_name", "华南测试区");
        claims.put("tenant_kind", "TEST");
        claims.put("tenant_session_epoch", "2");
        claims.put("membership_revision", "3");
        claims.put("client_tenant_grant_revision", "4");
        return jwt(claims);
    }

    private static JwtAuthenticationToken machine() {
        return jwt(Map.of("sub", "agent-client", "client_id", "agent-client",
                "token_kind", "machine"));
    }

    private static JwtAuthenticationToken jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
        claims.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    private record Endpoint(String method, String path) {
        MockHttpServletRequest request() {
            return new MockHttpServletRequest(method, path);
        }
    }
}
