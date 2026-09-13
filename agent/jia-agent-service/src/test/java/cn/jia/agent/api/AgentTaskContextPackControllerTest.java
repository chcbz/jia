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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    void exactJwtSubjectAndNameAloneBindActorAndScopeIgnoringThreadLocalIdentity() throws Exception {
        EsContext poisoned = new EsContext();
        poisoned.setJiacn("cookie-tenant");
        poisoned.setClientId("cookie-client");
        EsContextHolder.setContext(poisoned);
        when(service.generate("tenant-a", "client-a", TASK, ACTOR, "9"))
                .thenReturn(pack("tenant-a", "client-a", "9"));

        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("expectedVersion", "9").principal(jwt(ACTOR)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.provenance.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.provenance.clientId").value("client-a"))
                .andExpect(jsonPath("$.provenance.taskId").value(TASK))
                .andExpect(jsonPath("$.provenance.actorAgentId").value(ACTOR));
        verify(service).generate("tenant-a", "client-a", TASK, ACTOR, "9");
    }

    @Test
    void canonicalVersionBoundariesRemainRetrievable() throws Exception {
        for (String version : List.of("0", "9223372036854775807")) {
            when(service.generate("tenant-a", "client-a", TASK, ACTOR, version))
                    .thenReturn(pack("tenant-a", "client-a", version));
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .queryParam("expectedVersion", version).principal(jwt(ACTOR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.provenance.currentEventVersion").value(version));
            verify(service).generate("tenant-a", "client-a", TASK, ACTOR, version);
        }
    }

    @Test
    void alternateCoordinatorReviewerAndPrivateProducerQueriesNeverReachService() throws Exception {
        // Even same-subject compatibility is forbidden: HTTP has no actor delegation.
        for (String nominated : List.of(ACTOR, "coordinator", "reviewer", "private-producer")) {
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .queryParam("actorAgentId", nominated).principal(jwt(ACTOR)))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        verifyNoInteractions(gate, service);
    }

    @Test
    void closedQueryRejectsUnknownAndDuplicateKeysBeforeGateOrService() throws Exception {
        for (String field : List.of("tenantId", "clientId", "sub", "actor", "limit", "unknown")) {
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .queryParam(field, "other").principal(jwt(ACTOR)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("actorAgentId", ACTOR, "other").principal(jwt(ACTOR)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("expectedVersion", "1", "1").principal(jwt(ACTOR)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gate, service);
    }

    @Test
    void absentUnauthenticatedAndNonJwtPrincipalsNeverReachService() throws Exception {
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        JwtAuthenticationToken unauthenticated = jwt(ACTOR);
        unauthenticated.setAuthenticated(false);
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK).principal(unauthenticated))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .principal(new UsernamePasswordAuthenticationToken(ACTOR, "unused", List.of())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(gate, service);
    }

    @Test
    void missingNonStringPaddedAndMalformedSubjectCannotAuthorizeAnyActor() throws Exception {
        Object[] subjects = {null, 7, true, List.of(ACTOR), java.util.Map.of("id", ACTOR),
                "", " ", " " + ACTOR, ACTOR + "\u00a0", ACTOR + "\n", "a\u0000b",
                "\ud800", "\udc00", "a".repeat(101)};
        for (Object subject : subjects) {
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .principal(jwtClaims("tenant-a", "client-a", subject, ACTOR)))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        verifyNoInteractions(gate, service);
    }

    @Test
    void missingPaddedOrMismatchedAuthenticationNameCannotAuthorizeSubject() throws Exception {
        String[] names = {null, "", " ", " " + ACTOR, ACTOR + "\u00a0", "other-agent",
                "a\u0000b", "\ud800", "a".repeat(101)};
        for (String name : names) {
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .principal(jwtClaims("tenant-a", "client-a", ACTOR, name)))
                    .andExpect(status().isForbidden());
        }
        // No normalization silently aliases two distinct authenticated identifiers.
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .principal(jwtClaims("tenant-a", "client-a", "caf\u00e9", "cafe\u0301")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(gate, service);
    }

    @Test
    void tenantAndClientClaimsRemainExactStrings() throws Exception {
        Object[] invalid = {null, 9, List.of("scope"), "", " tenant", "client\u00a0", "a".repeat(51)};
        for (Object value : invalid) {
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .principal(jwtClaims(value, "client-a", ACTOR, ACTOR)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .principal(jwtClaims("tenant-a", value, ACTOR, ACTOR)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(gate, service);
    }

    @Test
    void malformedVersionAndTaskRejectBeforeGateOrService() throws Exception {
        for (String version : List.of("", "01", "-1", "+1", " 1", "1 ", "1.0",
                "9223372036854775808", "9".repeat(100))) {
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                            .queryParam("expectedVersion", version).principal(jwt(ACTOR)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", " " + TASK).principal(jwt(ACTOR)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gate, service);
    }

    @Test
    void gateFailureStaleAndAuthenticatedActorsAclDenialsUseNonLeakingResponses() throws Exception {
        when(gate.allows("tenant-a", "client-a")).thenReturn(false);
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK).principal(jwt(ACTOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_UNAVAILABLE"));
        verifyNoInteractions(service);

        when(gate.allows("tenant-a", "client-a")).thenReturn(true);
        when(service.generate("tenant-a", "client-a", TASK, ACTOR, "8"))
                .thenThrow(new AgentTaskContextPackException(
                        AgentTaskContextPackException.Reason.STALE_VERSION));
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("expectedVersion", "8").principal(jwt(ACTOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_STALE"));

        // Each request uses its OWN authenticated subject, never a nominated query actor.
        for (String actor : List.of(ACTOR, "inactive-agent", "foreign-agent", "non-member")) {
            when(service.generate("tenant-a", "client-a", TASK, actor, null))
                    .thenThrow(new AgentTaskContextPackException(
                            AgentTaskContextPackException.Reason.NOT_FOUND_OR_FORBIDDEN));
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK).principal(jwt(actor)))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_NOT_FOUND"));
        }
        when(service.generate("tenant-a", "client-a", "foreign-task", ACTOR, null))
                .thenThrow(new AgentTaskContextPackException(
                        AgentTaskContextPackException.Reason.NOT_FOUND_OR_FORBIDDEN));
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", "foreign-task").principal(jwt(ACTOR)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_NOT_FOUND"));
    }

    @Test
    void nullOrForeignSerializedProvenanceCannotBecomeHttpSuccess() throws Exception {
        List<Consumer<AgentTaskContextPackDTO>> corruptions = List.of(
                value -> value.setProvenance(null),
                value -> value.getProvenance().setTenantId("foreign-tenant"),
                value -> value.getProvenance().setClientId("foreign-client"),
                value -> value.getProvenance().setTaskId("foreign-task"),
                value -> value.getProvenance().setActorAgentId("private-producer"),
                value -> value.getProvenance().setActorAgentId(null),
                value -> value.getProvenance().setTenantId(null),
                value -> value.getProvenance().setClientId(null),
                value -> value.getProvenance().setTaskId(null),
                value -> value.getProvenance().setTaskVersion(null),
                value -> value.getProvenance().setTaskVersion("01"),
                value -> value.getProvenance().setCurrentEventVersion(null),
                value -> value.getProvenance().setCurrentEventVersion("-1"),
                value -> value.getProvenance().setCurrentEventVersion("9223372036854775808"),
                value -> value.getProvenance().setCurrentEventVersion("8"));
        for (Consumer<AgentTaskContextPackDTO> corrupt : corruptions) {
            AgentTaskContextPackDTO value = pack("tenant-a", "client-a", "9");
            value.getTaskDescription().getDescription().setValue("foreign-raw-secret");
            corrupt.accept(value);
            when(service.generate("tenant-a", "client-a", TASK, ACTOR, "9")).thenReturn(value);
            assertUnavailableOutput();
        }
        when(service.generate("tenant-a", "client-a", TASK, ACTOR, "9")).thenReturn(null);
        assertUnavailableOutput();
    }

    private void assertUnavailableOutput() throws Exception {
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK)
                        .queryParam("expectedVersion", "9").principal(jwt(ACTOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_UNAVAILABLE"))
                .andExpect(jsonPath("$.provenance").doesNotExist())
                .andExpect(content().string(not(containsString("foreign-raw-secret"))));
    }

    @Test
    void serializedResponseAboveBoundFailsClosedInsteadOfTruncating() throws Exception {
        AgentTaskContextPackDTO result = pack("tenant-a", "client-a", "9");
        result.getTaskDescription().getDescription().setValue(
                "x".repeat(AgentTaskContextPackController.MAX_SERIALIZED_BYTES));
        when(service.generate("tenant-a", "client-a", TASK, ACTOR, null)).thenReturn(result);
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", TASK).principal(jwt(ACTOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("TASK_CONTEXT_PACK_UNAVAILABLE"));
    }

    @Test
    void earliestFilterAddsNoStoreBeforeSecurityResponse() throws Exception {
        AgentTaskContextPackCacheControlFilter filter = new AgentTaskContextPackCacheControlFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/agent/tasks/task-1/context-pack");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));
        org.junit.jupiter.api.Assertions.assertEquals("private, no-store",
                response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    private static JwtAuthenticationToken jwt(String actor) {
        return jwtClaims("tenant-a", "client-a", actor, actor);
    }

    private static JwtAuthenticationToken jwtClaims(
            Object tenant, Object client, Object subject, String name) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (tenant != null) builder.claim("jiacn", tenant);
        if (client != null) builder.claim("client_id", client);
        if (subject != null) builder.claim("sub", subject);
        return new JwtAuthenticationToken(builder.build(), List.of(), name);
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
