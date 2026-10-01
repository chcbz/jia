package cn.jia.chat.api;

import cn.jia.chat.service.ControlledImagePointAndStartCapabilityService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentTaskControlledImageCapabilityControllerTest {
    private ControlledImagePointAndStartCapabilityService service;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service=mock(ControlledImagePointAndStartCapabilityService.class);
        mvc=MockMvcBuilders.standaloneSetup(
                new AgentTaskControlledImageCapabilityController(service)).build();
        EsContext context=new EsContext();context.setTenantId("0");context.setClientId("client-a");
        context.setJiacn("owner-a");EsContextHolder.setContext(context);
    }
    @AfterEach void clear() { EsContextHolder.clearContext(); }

    @Test void exactOwnerQueryProjectsConsentRequiredWithoutIssuingAnything() throws Exception {
        var capability=new ControlledImagePointAndStartCapabilityService.Capability(2,"task-1","agent-a",
                "ORDINARY_SINGLE_AGENT_CONTROLLED_IMAGE_ASSIGN_AND_START",
                new ControlledImagePointAndStartCapabilityService.ServerLane("READY",List.of()),
                new ControlledImagePointAndStartCapabilityService.ControlledExecution("READY",
                        "PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V2",1,List.of("GENERATE_IMAGE")),
                new ControlledImagePointAndStartCapabilityService.ProviderBinding("CONTROLLED_IMAGE_HTTP_V1",
                        "binding-a","1","model-a",16,1,1),
                new ControlledImagePointAndStartCapabilityService.Authorization("CONSENT_REQUIRED",false),
                new ControlledImagePointAndStartCapabilityService.NewStart(false,
                        List.of("OWNER_EXACT_CONSENT_REQUIRED")),
                List.of("GENERATE_IMAGE"),"GENERATE_IMAGE","TASK_LINKED_REFERENCE",16,
                new ControlledImagePointAndStartCapabilityService.OriginalIntentRecovery(false,
                        "RECOVERY_REQUIRED","EXPLICIT_USER_EXACT_ORIGINAL_KEY_AND_BODY_ONLY"));
        when(service.read(any(),eq("task-1"),eq("agent-a"))).thenReturn(capability);
        mvc.perform(get("/agent/tasks/task-1/point-and-start-controlled-image-capability")
                        .queryParam("targetAgentId","agent-a").principal(jwt("owner-a","client-a")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.data.schemaVersion").value(2))
                .andExpect(jsonPath("$.data.authorization.state").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.data.authorization.paidExecutionAuthorized").value(false))
                .andExpect(jsonPath("$.data.newStart.eligible").value(false));
        verify(service).read(eq(new cn.jia.agent.service.AgentTaskExecutionGrantService.Scope(
                "0","client-a","owner-a")),eq("task-1"),eq("agent-a"));
    }

    @Test void malformedQueryForeignContextAndSourceFailureNeverBecomeAvailable() throws Exception {
        for(String path:List.of(
                "/agent/tasks/task-1/point-and-start-controlled-image-capability",
                "/agent/tasks/task-1/point-and-start-controlled-image-capability?targetAgentId=agent-a&extra=x",
                "/agent/tasks/task-1/point-and-start-controlled-image-capability?targetAgentId=agent-a&targetAgentId=agent-b")) {
            mvc.perform(get(path).principal(jwt("owner-a","client-a"))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        mvc.perform(get("/agent/tasks/task-1/point-and-start-controlled-image-capability")
                        .queryParam("targetAgentId","agent-a").principal(jwt("owner-b","client-a")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
        when(service.read(any(),eq("task-1"),eq("agent-a")))
                .thenThrow(new ControlledImagePointAndStartCapabilityService.SourceUnavailable());
        mvc.perform(get("/agent/tasks/task-1/point-and-start-controlled-image-capability")
                        .queryParam("targetAgentId","agent-a").principal(jwt("owner-a","client-a")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONTROLLED_IMAGE_CAPABILITY_SOURCE_UNAVAILABLE"));
    }

    private static JwtAuthenticationToken jwt(String owner,String client) {
        Jwt token=Jwt.withTokenValue("fixture").header("alg","none").claim("tenant_id","0")
                .claim("jiacn",owner).claim("client_id",client).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication=new JwtAuthenticationToken(token,List.of());
        authentication.setAuthenticated(true);return authentication;
    }
}
