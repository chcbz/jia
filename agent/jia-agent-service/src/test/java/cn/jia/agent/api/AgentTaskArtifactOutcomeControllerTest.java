package cn.jia.agent.api;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.entity.AgentTaskArtifactAcceptDTO;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeViewDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskArtifactOutcomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskArtifactOutcomeControllerTest {
    private static final String TENANT = "0";
    private static final String OWNER = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String DECISION = "decision-0001";

    private AgentTaskArtifactOutcomeService service;
    private AgentTaskEventsGate gate;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AgentTaskArtifactOutcomeService.class);
        gate = mock(AgentTaskEventsGate.class);
        lenient().when(gate.allows(anyString(), anyString())).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentTaskArtifactOutcomeController(service, gate)).build();
    }



    @Test
    void anonymousNonJwtMalformedClaimsAndNameMismatchFailBeforeGateOrService()
            throws Exception {
        mvc.perform(post("/agent/tasks/%20bad%20/artifact-outcomes/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        mvc.perform(post("/agent/tasks/%20bad%20/artifact-outcomes/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(
                                "user", "n/a", List.of())))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/agent/tasks/%20bad%20/artifact-outcomes/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{")
                        .principal(jwtClaims(OWNER, 7, ACTOR, ACTOR)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/agent/tasks/%20bad%20/artifact-outcomes/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{")
                        .principal(jwtClaims(OWNER, CLIENT, " " + ACTOR, " " + ACTOR)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/agent/tasks/%20bad%20/artifact-outcomes/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{")
                        .principal(jwtClaims(OWNER, CLIENT, ACTOR, OTHER)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(gate, service);
    }




    @Test
    void foreignAuthenticatedIdentitiesCollapseToOneNonEnumeratingNotFoundShape()
            throws Exception {
        when(service.listAuthoritativeAccepted(
                TENANT, CLIENT, "owner-b", TASK, ACTOR, null, null))
                .thenThrow(new AgentTaskCollaborationException(Reason.NOT_FOUND, "tenant secret"));
        when(service.listAuthoritativeAccepted(
                TENANT, "client-b", OWNER, TASK, ACTOR, null, null))
                .thenThrow(new AgentTaskCollaborationException(Reason.NOT_FOUND, "client secret"));
        when(service.listAuthoritativeAccepted(
                TENANT, CLIENT, OWNER, TASK, OTHER, null, null))
                .thenThrow(new AgentTaskCollaborationException(Reason.FORBIDDEN, "actor secret"));

        MvcResult foreignTenant = mvc.perform(
                        get("/agent/tasks/{taskId}/artifact-outcomes/accepted", TASK)
                                .principal(jwt("owner-b", CLIENT, ACTOR)))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult foreignClient = mvc.perform(
                        get("/agent/tasks/{taskId}/artifact-outcomes/accepted", TASK)
                                .principal(jwt(OWNER, "client-b", ACTOR)))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult foreignActor = mvc.perform(
                        get("/agent/tasks/{taskId}/artifact-outcomes/accepted", TASK)
                                .principal(jwt(OWNER, CLIENT, OTHER)))
                .andExpect(status().isNotFound()).andReturn();

        String notFoundBody = foreignTenant.getResponse().getContentAsString();
        assertEquals(notFoundBody, foreignClient.getResponse().getContentAsString());
        assertEquals(notFoundBody, foreignActor.getResponse().getContentAsString());
        assertFalse(notFoundBody.contains("tenant secret"));
        assertFalse(notFoundBody.contains("client secret"));
        assertFalse(notFoundBody.contains("actor secret"));
        verify(service).listAuthoritativeAccepted(
                TENANT, CLIENT, "owner-b", TASK, ACTOR, null, null);
        verify(service).listAuthoritativeAccepted(
                TENANT, "client-b", OWNER, TASK, ACTOR, null, null);
        verify(service).listAuthoritativeAccepted(
                TENANT, CLIENT, OWNER, TASK, OTHER, null, null);
    }


    @Test
    void listQueryIsClosedCanonicalAndBoundedBeforeGateOrService() throws Exception {
        JwtAuthenticationToken auth = jwt(OWNER, CLIENT, ACTOR);
        for (String uri : List.of(
                "/agent/tasks/task-1/artifact-outcomes/accepted?tenantId=tenant-b",
                "/agent/tasks/task-1/artifact-outcomes/accepted?workItemId=",
                "/agent/tasks/task-1/artifact-outcomes/accepted?limit=0",
                "/agent/tasks/task-1/artifact-outcomes/accepted?limit=01",
                "/agent/tasks/task-1/artifact-outcomes/accepted?limit=101",
                "/agent/tasks/task-1/artifact-outcomes/accepted?limit=1&limit=2")) {
            mvc.perform(get(uri).principal(auth))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        verifyNoInteractions(gate, service);
    }


    @Test
    void acceptRejectsMismatchedArtifactVersionOutcomeAndDecisionAuthority()
            throws Exception {
        JwtAuthenticationToken auth = jwt(OWNER, CLIENT, ACTOR);
        AgentTaskArtifactOutcomeViewDTO mismatch =
                accepted("artifact-other", "work-1", OTHER, DECISION);
        when(service.accept(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(mismatch);
        mvc.perform(post("/agent/tasks/{taskId}/artifact-outcomes/accept", TASK)
                        .header("Idempotency-Key", DECISION)
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody())
                        .principal(auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ARTIFACT_OUTCOME_UNAVAILABLE"));

        mismatch = accepted("artifact-new", "work-1", OTHER, DECISION);
        mismatch.setArtifactVersion(3);
        when(service.accept(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(mismatch);
        mvc.perform(post("/agent/tasks/{taskId}/artifact-outcomes/accept", TASK)
                        .header("Idempotency-Key", DECISION)
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody())
                        .principal(auth))
                .andExpect(status().isServiceUnavailable());

        mismatch = accepted("artifact-new", "work-1", OTHER, DECISION);
        mismatch.setOutcomeVersion(2L);
        when(service.accept(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(mismatch);
        mvc.perform(post("/agent/tasks/{taskId}/artifact-outcomes/accept", TASK)
                        .header("Idempotency-Key", DECISION)
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody())
                        .principal(auth))
                .andExpect(status().isServiceUnavailable());

        mismatch = accepted("artifact-new", "work-1", OTHER, "decision-other");
        when(service.accept(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(mismatch);
        mvc.perform(post("/agent/tasks/{taskId}/artifact-outcomes/accept", TASK)
                        .header("Idempotency-Key", DECISION)
                        .contentType(MediaType.APPLICATION_JSON).content(acceptBody())
                        .principal(auth))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void acceptedOnlyProjectionRejectsSupersededCrossTaskDuplicateAndOversizedResults()
            throws Exception {
        AgentTaskArtifactOutcomeViewDTO superseded =
                accepted("artifact-a", "work-1", OTHER, "decision-a");
        superseded.setOutcomeState("superseded");
        when(service.listAuthoritativeAccepted(
                TENANT, CLIENT, OWNER, TASK, ACTOR, null, null)).thenReturn(List.of(superseded));
        mvc.perform(get("/agent/tasks/{taskId}/artifact-outcomes/accepted", TASK)
                        .principal(jwt(OWNER, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("superseded"))));

        AgentTaskArtifactOutcomeViewDTO crossTask =
                accepted("artifact-a", "work-1", OTHER, "decision-a");
        crossTask.setTaskId("task-b");
        when(service.listAuthoritativeAccepted(
                TENANT, CLIENT, OWNER, TASK, ACTOR, null, 2)).thenReturn(List.of(crossTask));
        mvc.perform(get("/agent/tasks/{taskId}/artifact-outcomes/accepted", TASK)
                        .queryParam("limit", "2")
                        .principal(jwt(OWNER, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable());

        AgentTaskArtifactOutcomeViewDTO duplicate =
                accepted("artifact-a", "work-1", OTHER, "decision-a");
        when(service.listAuthoritativeAccepted(
                TENANT, CLIENT, OWNER, TASK, ACTOR, null, 2))
                .thenReturn(List.of(duplicate, duplicate));
        mvc.perform(get("/agent/tasks/{taskId}/artifact-outcomes/accepted", TASK)
                        .queryParam("limit", "2")
                        .principal(jwt(OWNER, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable());

        when(service.listAuthoritativeAccepted(
                TENANT, CLIENT, OWNER, TASK, ACTOR, null, 1))
                .thenReturn(List.of(duplicate, duplicate));
        mvc.perform(get("/agent/tasks/{taskId}/artifact-outcomes/accepted", TASK)
                        .queryParam("limit", "1")
                        .principal(jwt(OWNER, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable());
    }


    private static String acceptBody() {
        return "{\"acceptedArtifact\":{\"artifactId\":\"artifact-new\","
                + "\"artifactVersion\":2,\"expectedOutcomeVersion\":0},"
                + "\"supersededArtifacts\":[{\"artifactId\":\"artifact-old\","
                + "\"artifactVersion\":1,\"expectedOutcomeVersion\":1}]}";
    }

    private static String withField(String body, String field) {
        return body.substring(0, body.length() - 1) + "," + field + "}";
    }

    private static AgentTaskArtifactOutcomeViewDTO accepted(
            String artifactId, String workItemId, String producer, String decisionId) {
        AgentTaskArtifactOutcomeViewDTO view = new AgentTaskArtifactOutcomeViewDTO();
        view.setArtifactId(artifactId);
        view.setTaskId(TASK);
        view.setWorkItemId(workItemId);
        view.setProducerAgentId(producer);
        view.setArtifactType("analysis");
        view.setTitle("Accepted artifact");
        view.setContentHash("a".repeat(64));
        view.setArtifactVersion("artifact-new".equals(artifactId) ? 2 : 1);
        view.setVisibility("task_members");
        view.setCreatedAt(1_800_000_000_000L);
        view.setOutcomeState("accepted");
        view.setOutcomeVersion(1L);
        view.setDecisionId(decisionId);
        view.setDecidedByAgentId(ACTOR);
        view.setDecidedAt(1_800_000_000_100L);
        return view;
    }

    private static JwtAuthenticationToken jwt(
            String tenant, String client, String subject) {
        return jwtClaims(tenant, client, subject, subject);
    }

    private static JwtAuthenticationToken jwtClaims(
            Object tenant, Object client, Object subject, String authenticationName) {
        Jwt.Builder builder = Jwt.withTokenValue("token").claim("tenant_id", "0").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (tenant != null) {
            builder.claim("jiacn", tenant);
        }
        if (client != null) {
            builder.claim("client_id", client);
        }
        if (subject != null) {
            builder.claim("sub", subject);
        }
        return new JwtAuthenticationToken(builder.build(), List.of(), authenticationName);
    }
}
