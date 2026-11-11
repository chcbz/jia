package cn.jia.agent.security;

import cn.jia.agent.api.PlatformKnowledgeUsageController;
import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.agent.service.usage.PlatformKnowledgeUsageRecorder;
import cn.jia.agent.service.usage.PlatformKnowledgeMaintenanceFixture;
import cn.jia.core.entity.JsonRequest;
import cn.jia.economy.usage.TokenUsageCost;
import cn.jia.economy.usage.UsageCostService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformKnowledgeUsageControllerTest {
    static final String PATH = "/internal/agent/hosting-leases/lease/knowledge-usage";
    static final String TOKEN = "rta1_" + "a".repeat(64);
    JsonRequest<PlatformKnowledgeUsageController.Request> body() {
        return new JsonRequest<>(1, "request", new PlatformKnowledgeUsageController.Request("handover",
                new PlatformKnowledgeUsageRecorder.Evidence("0", "extract", PlatformKnowledgeUsageRecorder.Phase.STARTED,
                        "platform", "gpt-6-luna", "standard", null)));
    }
    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("Authorization", "Bearer " + TOKEN); return request;
    }
    @Test void maintenanceRouteUsesOnlyInstallationLaneNotOrdinarySessionLane() {
        assertTrue(AgentRuntimeSecurityConfiguration.selectsRuntimeV1ClientLane(request()));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(request()));
        for (String method : new String[]{"GET", "PUT", "DELETE"})
            assertFalse(AgentRuntimeSecurityConfiguration.selectsRuntimeV1ClientLane(new MockHttpServletRequest(method, PATH)));
        for (String path : new String[]{PATH + "/execute", PATH.replace("/lease/", "/../"), PATH.replace("/lease/", "/%2f/")})
            assertFalse(AgentRuntimeSecurityConfiguration.selectsRuntimeV1ClientLane(new MockHttpServletRequest("POST", path)));
    }
    @Test void originalInstallationProvidesScopeWithoutUserSessionOrTaskSettlement() {
        var recorder = mock(PlatformKnowledgeUsageRecorder.class); var auth = mock(AgentRuntimeV1Service.class);
        var installation = new PlatformKnowledgeMaintenanceFixture("tenant", "client", "agent", "installation").installation;
        when(auth.withInstallationAuthorization(eq(TOKEN), any())).thenAnswer(call ->
                ((java.util.function.Function<cn.jia.agent.entity.AgentRuntimeV1InstallationView, ?>)call.getArgument(1)).apply(installation));
        when(recorder.record(any(), eq("lease"), eq("handover"), any())).thenReturn(
                new UsageCostService.Receipt("attempt", 1, TokenUsageCost.Status.PENDING_USAGE, null, false));
        var body = body();
        var result = new PlatformKnowledgeUsageController(recorder, auth).record("lease", body, request());
        assertEquals("1", result.getBody().getData().revision()); assertNull(result.getBody().getData().costYuan());
        verify(recorder).record(installation, "lease", "handover", body.data().observation());
        verify(auth, never()).session(any(), any(), anyLong());
    }
    @Test void browserQueryDuplicateAndOrdinarySessionCredentialsCannotReachMaintenance() {
        var recorder = mock(PlatformKnowledgeUsageRecorder.class); var auth = mock(AgentRuntimeV1Service.class);
        var controller = new PlatformKnowledgeUsageController(recorder, auth);
        var browser=request(); browser.addHeader("Origin", "https://app.test");
        var query=request(); query.setQueryString("owner=other");
        var duplicate=request(); duplicate.addHeader("Authorization", "Bearer " + TOKEN);
        var apiKey=request(); apiKey.addHeader("X-API-Key", "private");
        var session=new MockHttpServletRequest("POST", PATH); session.addHeader("Authorization", "AgentRuntime rts1_" + "b".repeat(64));
        for (var request : new MockHttpServletRequest[]{browser,query,duplicate,apiKey,session,new MockHttpServletRequest("POST",PATH)})
            assertThrows(cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException.class,
                    () -> controller.record("lease", body(), request));
        verifyNoInteractions(recorder,auth);
    }
    @Test void ordinarySessionCannotUseRemovedKnowledgeRoute() throws Exception {
        var auth=mock(AgentRuntimeAuthenticationService.class);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        new AgentRuntimeAuthenticationFilter(auth).doFilter(request(),response,(a,b)->fail("old native route admitted"));
        assertEquals(403,response.getStatus()); verifyNoInteractions(auth);
    }
}
