package cn.jia.agent.api;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.entity.AgentTaskContextPackDTO;
import cn.jia.agent.exception.AgentTaskContextPackException;
import cn.jia.agent.service.AgentTaskContextPackService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskContextPackControllerTest extends BaseMockTest {
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock AgentTaskContextPackService service;
    @Mock AgentTaskEventsGate gate;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        lenient().when(gate.allows(anyString(), anyString())).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentTaskContextPackController(service, gate)).build();
    }

    @AfterEach
    void clearContext() {
        EsContextHolder.setContext(new EsContext());
    }

    @Test
    void jwtClaimsAloneAuthorizeScopeAndClientScopeQueriesCannotOverrideThem() throws Exception {
        EsContext poisoned = new EsContext();
        poisoned.setJiacn("cookie-tenant");
        poisoned.setClientId("cookie-client");
        EsContextHolder.setContext(poisoned);
        when(service.generate("tenant-a", "client-a", TASK, ACTOR, "9"))
                .thenReturn(pack("tenant-a", "client-a", "9"));

        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .queryParam("expectedVersion", "9")
                        .queryParam("tenantId", "attacker-tenant")
                        .queryParam("clientId", "attacker-client")
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.provenance.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.provenance.clientId").value("client-a"));

        verify(service).generate("tenant-a", "client-a", TASK, ACTOR, "9");
    }

    @Test
    void authenticationClaimsAndSyntaxFailBeforeGateOrService() throws Exception {
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        Jwt bad = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "tenant-a").claim("client_id", 9)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .principal(new JwtAuthenticationToken(bad, List.of())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .queryParam("expectedVersion", "01")
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verifyNoInteractions(gate, service);
    }

    @Test
    void duplicateActorOrExpectedVersionIsRejected() throws Exception {
        JwtAuthenticationToken authentication = jwt("tenant-a", "client-a");
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR, "other")
                        .principal(authentication))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .queryParam("expectedVersion", "1", "2")
                        .principal(authentication))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gate, service);
    }

    @Test
    void gateFailureStaleAndAllAclDenialsUseFrozenNonLeakingResponses() throws Exception {
        JwtAuthenticationToken authentication = jwt("tenant-a", "client-a");
        when(gate.allows("tenant-a", "client-a")).thenReturn(false);
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR).principal(authentication))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_UNAVAILABLE"));
        verify(service, never()).generate(anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));

        when(gate.allows("tenant-a", "client-a")).thenReturn(true);
        when(service.generate("tenant-a", "client-a", TASK, ACTOR, "8"))
                .thenThrow(new AgentTaskContextPackException(
                        AgentTaskContextPackException.Reason.STALE_VERSION));
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR).queryParam("expectedVersion", "8")
                        .principal(authentication))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_STALE"));

        for (String actor : List.of(ACTOR, "inactive-agent", "foreign-agent", "non-member")) {
            when(service.generate("tenant-a", "client-a", TASK, actor, null))
                    .thenThrow(new AgentTaskContextPackException(
                            AgentTaskContextPackException.Reason.NOT_FOUND_OR_FORBIDDEN));
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .queryParam("actorAgentId", actor).principal(authentication))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_NOT_FOUND"));
        }
        when(service.generate("tenant-a", "client-a", "foreign-task", ACTOR, null))
                .thenThrow(new AgentTaskContextPackException(
                        AgentTaskContextPackException.Reason.NOT_FOUND_OR_FORBIDDEN));
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", "foreign-task")
                        .queryParam("actorAgentId", ACTOR).principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_NOT_FOUND"));
    }

    @Test
    void serializedResponseAboveBoundFailsClosedInsteadOfTruncating() throws Exception {
        AgentTaskContextPackDTO result = pack("tenant-a", "client-a", "9");
        result.getTaskDescription().getDescription().setValue(
                "x".repeat(AgentTaskContextPackController.MAX_SERIALIZED_BYTES));
        when(service.generate("tenant-a", "client-a", TASK, ACTOR, null))
                .thenReturn(result);

        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_UNAVAILABLE"));
    }

    @Test
    void earliestFilterAddsNoStoreBeforeSecurityResponse() throws Exception {
        AgentTaskContextPackCacheControlFilter filter =
                new AgentTaskContextPackCacheControlFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/agent/tasks/task-1/context-pack");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));
        org.junit.jupiter.api.Assertions.assertEquals("private, no-store",
                response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    private static JwtAuthenticationToken jwt(String tenantId, String clientId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", tenantId).claim("client_id", clientId)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private static AgentTaskContextPackDTO pack(
            String tenantId, String clientId, String version) {
        AgentTaskContextPackDTO result = new AgentTaskContextPackDTO();
        result.setSchemaVersion("f01-context-pack-v1");
        AgentTaskContextPackDTO.Provenance provenance =
                new AgentTaskContextPackDTO.Provenance();
        provenance.setTenantId(tenantId);
        provenance.setClientId(clientId);
        provenance.setTaskId(TASK);
        provenance.setActorAgentId(ACTOR);
        provenance.setTaskVersion("1");
        provenance.setCurrentEventVersion(version);
        result.setProvenance(provenance);
        AgentTaskContextPackDTO.TaskDescriptionSection task =
                new AgentTaskContextPackDTO.TaskDescriptionSection();
        task.setStatus("AVAILABLE");
        AgentTaskContextPackDTO.SafeText title = new AgentTaskContextPackDTO.SafeText();
        title.setValue("Title");
        AgentTaskContextPackDTO.SafeText description = new AgentTaskContextPackDTO.SafeText();
        description.setValue("Description");
        task.setTitle(title);
        task.setDescription(description);
        result.setTaskDescription(task);
        result.setDigestAlgorithm("SHA-256");
        result.setDigest("a".repeat(64));
        return result;
    }
}
