package cn.jia.oauth.filter;

import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class MinimalGreyTenantRouteFilterTest {
    @AfterEach void clear() { EsContextHolder.clearContext(); }
    @Test void disabledNormalDeploymentDoesNotApplyRouteGate() throws Exception {
        EsContextHolder.getContext().setTenantId("0");
        var request = new MockHttpServletRequest("POST", "/agent/tasks");
        var response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        new MinimalGreyTenantRouteFilter(false, "").doFilter(request, response, (a,b) -> called.set(true));
        assertTrue(called.get());
    }
    @Test void fixedModeRejectsUnauthenticatedAndTenantZeroBusinessNamespaces() throws Exception {
        for (String[] route : new String[][]{{"GET","/wx/mp/checksignature"},{"GET","/wx/mp/oauth2/access_token"},
                {"GET","/agent/map"},{"GET","/economy/wallet"},{"GET","/login/register"},
                {"POST","/login/resetPassword"}}) assertDenied(route[0], route[1]);
        for (String[] route : new String[][]{{"GET","/login/index.html"},{"GET","/login/css/login.css"},
                {"POST","/login"},{"GET","/oauth2/authorize"},{"POST","/oauth2/token"},
                {"GET","/.well-known/openid-configuration"},{"GET","/oauth/confirm_access"},
                {"GET","/actuator/health"},{"POST","/agent/runtime/v1/enroll"},
                {"POST","/agent/runtime/v1/session"},{"POST","/agent/runtime/v1/heartbeat"}})
            assertPassed(route[0], route[1]);
        EsContextHolder.getContext().setTenantId("0");
        assertDenied("GET", "/agent/map");
        assertDenied("POST", "/agent/runtime/v1/installations");
        assertDenied("GET", "/ws/agent/channel");
    }
    @Test void tenantOneAllowsMinimalSurfaceAndRejectsLegacySideEffects() throws Exception {
        EsContextHolder.getContext().setTenantId("1");
        for (String[] route : new String[][]{{"GET","/resource"},{"GET","/agent/map"},{"POST","/agent/roster"},
                {"GET","/agent/personas/catalog"},{"POST","/agent/personas/wuyong/bind"},
                {"GET","/agent/agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},{"PUT","/agent/agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/status"},
                {"POST","/agent/runtime/v1/installations"},{"GET","/agent/runtime/v1/installations/rti_1"},
                {"POST","/agent/runtime/v1/installations/rti_1/revoke"},{"POST","/agent/runtime/v1/enroll"},
                {"POST","/agent/runtime/v1/session"},{"POST","/agent/runtime/v1/heartbeat"},
                {"POST","/agent/runtime/v1/commands/msg-1/acks"},{"GET","/ws/agent/channel"},
                {"POST","/chat/stream"},{"GET","/chat/conversation/events"}})
            assertPassed(route[0], route[1]);
        for (String[] route : new String[][]{{"POST","/agent/tasks"},{"GET","/agent/tasks/task-1"},
                {"POST","/agent/personal-workspace/files"},{"GET","/economy/wallet"},{"POST","/chat/library/documents"},
                {"GET","/agent/stats"},{"GET","/agent/runtime/v1/enroll"},
                {"GET","/agent/runtime/v1/session"},{"POST","/agent/runtime/v1/commands/msg-1/retry"}})
            assertDenied(route[0], route[1]);
    }
    private void assertPassed(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path); var response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        new MinimalGreyTenantRouteFilter(true, "1").doFilter(request, response, (a,b) -> called.set(true));
        assertTrue(called.get(), method + " " + path);
    }
    private void assertDenied(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path); var response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        new MinimalGreyTenantRouteFilter(true, "1").doFilter(request, response, (a,b) -> called.set(true));
        assertFalse(called.get()); assertEquals(403, response.getStatus(), method + " " + path);
    }
}
