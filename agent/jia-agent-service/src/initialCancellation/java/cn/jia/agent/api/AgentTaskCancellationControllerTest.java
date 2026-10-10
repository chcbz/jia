package cn.jia.agent.api;

import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.service.AgentTaskCancellationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentTaskCancellationControllerTest {
    AgentTaskCancellationService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(AgentTaskCancellationService.class);
        mvc=MockMvcBuilders.standaloneSetup(new AgentTaskCancellationController(service)).build();
        when(service.cancel("0","client","owner","actor","417",1))
                .thenReturn(new AgentTaskCancellationService.Receipt("417","cancelled",2));
    }
    static JwtAuthenticationToken jwt() { return jwt(Map.of("sub","actor","jiacn","owner","client_id","client"),"actor"); }
    static JwtAuthenticationToken jwt(Map<String,Object> claims,String name) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("not-a-production-token").header("alg","none")
                .claims(c -> c.putAll(claims)).build(),java.util.List.of(),name);
    }
    @Test void scopedCanonicalReceiptAndLostResponseReplay() throws Exception {
        for (int i=0;i<2;i++) mvc.perform(post("/agent/tasks/417/cancel").principal(jwt())
                .contentType("application/json").content("{\"expectedTaskVersion\":1}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.taskId").value("417")).andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.taskVersion").value(2)).andExpect(jsonPath("$.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.actorId").doesNotExist()).andExpect(jsonPath("$.grantId").doesNotExist());
        verify(service,times(2)).cancel("0","client","owner","actor","417",1);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"expectedTaskVersion\":-1}","{\"expectedTaskVersion\":1.0}",
            "{\"expectedTaskVersion\":\"1\"}","{\"expectedTaskVersion\":null}","{\"expectedTaskVersion\":true}",
            "{\"expectedTaskVersion\":9223372036854775807}","{\"expectedTaskVersion\":9223372036854775808}",
            "{\"expectedTaskVersion\":1,\"expectedTaskVersion\":2}","{\"expectedTaskVersion\":1} {}",
            "{\"expectedTaskVersion\":1,\"ownerJiacn\":\"foreign\"}","[]","","{"})
    void strictVersionAndAllowlist(String body) throws Exception {
        mvc.perform(post("/agent/tasks/417/cancel").principal(jwt()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @Test void noClientScopeOrActorOverridesAndBoundedBody() throws Exception {
        mvc.perform(post("/agent/tasks/417/cancel?actorAgentId=foreign").principal(jwt())
                .contentType("application/json").content("{\"expectedTaskVersion\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/agent/tasks/417/cancel").principal(jwt()).contentType("application/json")
                .content(" ".repeat(1025))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void jwtRequiredNotGenericAuthentication() throws Exception {
        mvc.perform(post("/agent/tasks/417/cancel").contentType("application/json").content("{\"expectedTaskVersion\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/agent/tasks/417/cancel").principal(new UsernamePasswordAuthenticationToken("owner","secret",java.util.List.of()))
                .contentType("application/json").content("{\"expectedTaskVersion\":1}"))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
    @Test void jwtClaimsAndPrincipalMustBeExact() throws Exception {
        for (var auth:java.util.List.of(
                jwt(Map.of("sub","actor","jiacn","0","client_id","client"),"actor"),
                jwt(Map.of("sub","actor","jiacn","owner","client_id",123),"actor"),
                jwt(Map.of("sub","actor","jiacn","owner","client_id","client"),"other"),
                jwt(Map.of("sub","actor","jiacn"," owner","client_id","client"),"actor"),
                jwt(Map.of("sub","actor","client_id","client"),"actor"))) {
            mvc.perform(post("/agent/tasks/417/cancel").principal(auth).contentType("application/json")
                    .content("{\"expectedTaskVersion\":1}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void declaredTenantCannotBeIgnoredOrConflicted() throws Exception {
        for (var extra:java.util.List.of(Map.<String,Object>of("tenant_id","foreign"),
                Map.<String,Object>of("tenant_id","0","tenantId","foreign"),
                Map.<String,Object>of("tenant_id",123),
                Map.<String,Object>of("tenant_claim_version","2"))) {
            var claims=new java.util.HashMap<String,Object>(Map.of("sub","actor","jiacn","owner","client_id","client"));
            claims.putAll(extra);
            mvc.perform(post("/agent/tasks/417/cancel").principal(jwt(claims,"actor"))
                    .contentType("application/json").content("{\"expectedTaskVersion\":1}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void ownerMismatchIsOpaque404() throws Exception {
        doThrow(new AgentTaskCollaborationException(AgentTaskCollaborationException.Reason.NOT_FOUND,"secret owner")).when(service)
                .cancel(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong());
        mvc.perform(post("/agent/tasks/417/cancel").principal(jwt()).contentType("application/json")
                .content("{\"expectedTaskVersion\":1}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }
    @Test void strictVersionConflictAndUnsupportedInflightAre409() throws Exception {
        doThrow(new AgentTaskStateException(AgentTaskStateException.Reason.VERSION_CONFLICT,"internal")).when(service)
                .cancel(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong());
        mvc.perform(post("/agent/tasks/417/cancel").principal(jwt()).contentType("application/json")
                .content("{\"expectedTaskVersion\":1}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_VERSION_CONFLICT"));
        doThrow(new AgentTaskStateException(AgentTaskStateException.Reason.INVALID_TRANSITION,"runtime secret")).when(service)
                .cancel(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong());
        mvc.perform(post("/agent/tasks/417/cancel").principal(jwt()).contentType("application/json")
                .content("{\"expectedTaskVersion\":1}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INITIAL_CANCEL_UNSUPPORTED"));
    }
    @Test void missingSchemaOrInvalidReceiptIsFailClosed503() throws Exception {
        doThrow(new IllegalStateException("jdbc credentials must not be exposed")).when(service)
                .cancel(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong());
        mvc.perform(post("/agent/tasks/417/cancel").principal(jwt()).contentType("application/json")
                .content("{\"expectedTaskVersion\":1}")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CANCEL_UNAVAILABLE"));
    }
}
