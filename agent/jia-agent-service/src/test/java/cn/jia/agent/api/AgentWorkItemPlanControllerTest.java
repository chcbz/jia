package cn.jia.agent.api;

import cn.jia.agent.entity.AgentWorkItemPlanConfirmRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanSuggestRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanViewDTO;
import cn.jia.agent.exception.AgentWorkItemPlanException;
import cn.jia.agent.service.AgentWorkItemPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentWorkItemPlanControllerTest {
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private AgentWorkItemPlanService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AgentWorkItemPlanService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AgentWorkItemPlanController(service)).build();
    }

    @Test
    void jwtClaimsAreSoleScopeAuthorityAndSuggestionIsNoStore() throws Exception {
        when(service.suggest(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(view(false));

        mvc.perform(post("/agent/tasks/task-1/work-item-plans/suggest")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\":\"Implement endpoint\",\"maxItems\":4,"
                                + "\"dependencyMode\":\"sequential\"}")
                        .principal(jwt("Tenant-A", "Client-A")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.confirmationRequired").value(true));

        var request = org.mockito.ArgumentCaptor.forClass(
                AgentWorkItemPlanSuggestRequestDTO.class);
        verify(service).suggest("Tenant-A", "Client-A", "task-1", ACTOR, request.capture());
        assertEquals("Implement endpoint", request.getValue().getObjective());
    }

    @Test
    void authenticationAndExactClaimsPrecedePathActorAndBodyParsing() throws Exception {
        mvc.perform(post("/agent/tasks/%20bad%20/work-item-plans/suggest")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        Jwt malformed = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "tenant-a").claim("client_id", 7)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        mvc.perform(post("/agent/tasks/%20bad%20/work-item-plans/suggest")
                        .contentType(MediaType.APPLICATION_JSON).content("{")
                        .principal(new JwtAuthenticationToken(malformed, List.of())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void nonJwtAndPaddedClaimsFailClosed() throws Exception {
        mvc.perform(post("/agent/tasks/task-1/work-item-plans/suggest")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(
                                "user", "n/a", List.of())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/agent/tasks/task-1/work-item-plans/suggest")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .principal(jwt(" tenant-a", "client-a")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void strictBodiesRejectDuplicateUnknownIdentityAndTrailingJson() throws Exception {
        JwtAuthenticationToken auth = jwt("tenant-a", "client-a");
        for (String body : List.of(
                "{\"objective\":\"a\",\"objective\":\"b\",\"maxItems\":2,"
                        + "\"dependencyMode\":\"parallel\"}",
                "{\"objective\":\"a\",\"maxItems\":2,\"dependencyMode\":\"parallel\","
                        + "\"tenantId\":\"forged\",\"clientId\":\"forged\"}",
                "{\"objective\":\"a\",\"maxItems\":2,"
                        + "\"dependencyMode\":\"parallel\"} {}")) {
            mvc.perform(post("/agent/tasks/task-1/work-item-plans/suggest")
                            .queryParam("actorAgentId", ACTOR)
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .principal(auth))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void confirmationBodyCannotInjectScopeAssignmentOrDispatchFields() throws Exception {
        String body = confirmBody().replace(
                "\"maxAttempts\":3",
                "\"maxAttempts\":3,\"tenantId\":\"forged\","
                        + "\"assigneeAgentId\":\"" + ACTOR + "\","
                        + "\"dispatch\":true");

        mvc.perform(post("/agent/tasks/task-1/work-item-plans/confirm")
                        .header("Idempotency-Key", "confirm-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        verifyNoInteractions(service);
    }

    @Test
    void confirmRequiresSingleActorAndIdempotencyKeyBeforeService() throws Exception {
        String body = confirmBody();
        JwtAuthenticationToken auth = jwt("tenant-a", "client-a");
        mvc.perform(post("/agent/tasks/task-1/work-item-plans/confirm")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .principal(auth))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/agent/tasks/task-1/work-item-plans/confirm")
                        .header("Idempotency-Key", "confirm-key-0001")
                        .queryParam("actorAgentId", ACTOR, "other")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .principal(auth))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void confirmForwardsOnlyAuthenticatedScopeAndReturnsReplayFlag() throws Exception {
        when(service.confirm(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(view(true));

        mvc.perform(post("/agent/tasks/task-1/work-item-plans/confirm")
                        .header("Idempotency-Key", "confirm-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody())
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        verify(service).confirm(org.mockito.ArgumentMatchers.eq("tenant-a"),
                org.mockito.ArgumentMatchers.eq("client-a"),
                org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq(ACTOR),
                org.mockito.ArgumentMatchers.eq("confirm-key-0001"),
                any(AgentWorkItemPlanConfirmRequestDTO.class));
    }

    @Test
    void serviceFailuresMapToNonLeakingFrozenStatuses() throws Exception {
        when(service.suggest(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new AgentWorkItemPlanException(
                        AgentWorkItemPlanException.Reason.NOT_FOUND_OR_FORBIDDEN, "secret scope"));
        var missing = mvc.perform(post("/agent/tasks/task-1/work-item-plans/suggest")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\":\"a\",\"maxItems\":1,"
                                + "\"dependencyMode\":\"parallel\"}")
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret scope"))))
                .andReturn();
        assertEquals("private, no-store",
                missing.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));

        when(service.suggest(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new AgentWorkItemPlanException(
                        AgentWorkItemPlanException.Reason.INVALID_PERSISTED_STATE, "database detail"));
        mvc.perform(post("/agent/tasks/task-1/work-item-plans/suggest")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\":\"a\",\"maxItems\":1,"
                                + "\"dependencyMode\":\"parallel\"}")
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("database detail"))));
    }

    @Test
    void bodySizeLimitRejectsBeforeAllocationToService() throws Exception {
        mvc.perform(post("/agent/tasks/task-1/work-item-plans/suggest")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x".repeat(AgentWorkItemPlanController.MAX_BODY_BYTES + 1))
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isBadRequest());
        verify(service, never()).suggest(any(), any(), any(), any(), any());
    }

    private JwtAuthenticationToken jwt(String tenant, String client) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", tenant).claim("client_id", client)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private AgentWorkItemPlanViewDTO view(boolean confirmed) {
        AgentWorkItemPlanViewDTO view = new AgentWorkItemPlanViewDTO();
        view.setTaskId("task-1");
        view.setConfirmationRequired(!confirmed);
        view.setConfirmed(confirmed);
        view.setIdempotentReplay(confirmed);
        view.setItems(List.of());
        return view;
    }

    private String confirmBody() {
        return "{\"confirmed\":true,\"sourcePlanId\":\"wpp1.a.b.c.7.d\","
                + "\"sourcePlanDigest\":\"" + "0".repeat(64) + "\","
                + "\"expectedTaskVersion\":\"7\",\"items\":[{"
                + "\"itemKey\":\"item-1\",\"title\":\"Build\","
                + "\"description\":\"Build endpoint\",\"workType\":\"implementation\","
                + "\"requiredAbilities\":[\"java\"],\"priority\":0,"
                + "\"requiredItem\":true,\"dependsOn\":[],\"maxAttempts\":3}]}";
    }
}
