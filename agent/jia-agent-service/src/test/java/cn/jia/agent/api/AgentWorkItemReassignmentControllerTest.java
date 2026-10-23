package cn.jia.agent.api;

import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentResultDTO;
import cn.jia.agent.exception.AgentWorkItemReassignmentException;
import cn.jia.agent.service.AgentWorkItemReassignmentService;
import cn.jia.core.security.AllowSensitiveOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentWorkItemReassignmentControllerTest {
    private static final String ACTOR = "agt_cccccccccccccccccccccccccccccccc";
    private AgentWorkItemReassignmentService service;
    private MockMvc mvc;
    private cn.jia.agent.security.AgentRuntimeAuthenticationService runtimeAuthentication;

    @BeforeEach
    void setUp() {
        service = mock(AgentWorkItemReassignmentService.class);
        runtimeAuthentication=mock(cn.jia.agent.security.AgentRuntimeAuthenticationService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentWorkItemReassignmentController(service, runtimeAuthentication)).build();
    }

    @Test
    void mutationRequiresExactJwtScopeSubjectAndAuthority() throws Exception {
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body())
                        .principal(jwt(false, true, "tenant-a", "client-a")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body())
                        .principal(jwt(true, false, "tenant-a", "client-a")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body())
                        .principal(jwt(true, true, "tenant-a", "client-a", "different-sub")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void nonJwtAndPaddedClaimsFailClosedBeforeBody() throws Exception {
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(
                                "user", "n/a", List.of())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content("{")
                        .principal(jwt(true, true, " tenant-a", "client-a")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }





    @Test
    void nativeLeaseQueryConfirmsAuthenticatedTargetAndCannotSelectOrOmitActor() throws Exception {
        var runtime=mock(cn.jia.agent.security.AgentRuntimeAuthentication.class);
        when(runtime.isAuthenticated()).thenReturn(true);
        when(runtime.getPrincipal()).thenReturn(new cn.jia.agent.security.AgentRuntimeAuthentication.Scope("0","client-a","tenant-a",ACTOR,"boot"));
        String path="/agent/tasks/task-1/work-items/work-1/reassignments/rsn-1/lease/start";
        String body="{\"commandId\":\"cmd-original\",\"expectedWorkItemVersion\":5}";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).principal(runtime))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post(path).queryParam("actorAgentId","agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .contentType(MediaType.APPLICATION_JSON).content(body).principal(runtime))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WORK_ITEM_REASSIGNMENT_FORBIDDEN"));
        mvc.perform(post(path).queryParam("actorAgentId",ACTOR,ACTOR)
                .contentType(MediaType.APPLICATION_JSON).content(body).principal(runtime))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service,runtimeAuthentication);
    }

    @Test
    void missingKeyAndAmbiguousActorRejectBeforeService() throws Exception {
        JwtAuthenticationToken jwt = jwt(true, true, "tenant-a", "client-a");
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body()).principal(jwt))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR, ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body()).principal(jwt))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }



    @Test
    void leaseCredentialResponsesAreExplicitlyLimitedSensitiveOutputExceptions()
            throws Exception {
        for (String name : List.of("readLease", "startLease", "heartbeatLease")) {
            Method method = java.util.Arrays.stream(
                            AgentWorkItemReassignmentController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertNotNull(
                    method.getAnnotation(AllowSensitiveOutput.class));
        }
        Method operator = java.util.Arrays.stream(
                        AgentWorkItemReassignmentController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("reassign"))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(
                operator.getAnnotation(AllowSensitiveOutput.class));
    }


    @Test
    void everyLeaseEndpointRejectsCallerSelectedTargetBeforeParsingOrService() throws Exception {
        for (String suffix : List.of("", "/start", "/heartbeat")) {
            for (JwtAuthenticationToken identity : List.of(
                    jwt(true, false, "tenant-a", "client-a"),
                    targetJwt("agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    targetJwt("agt_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"))) {
                mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments/rsn-1/lease" + suffix)
                                .queryParam("actorAgentId", "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                                .contentType(MediaType.APPLICATION_JSON).content("{")
                                .principal(identity))
                        .andExpect(status().isForbidden())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                        .andExpect(content().string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("leaseToken"))));
            }
        }
        verifyNoInteractions(service);
    }


    private JwtAuthenticationToken targetJwt(String subject) {
        Jwt token = Jwt.withTokenValue("test-target-token").header("alg", "none")
                .claim("jiacn", "tenant-a").claim("client_id", "client-a")
                .subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of());
    }

    private JwtAuthenticationToken jwt(
            boolean subject, boolean authority, String tenant, String client) {
        return jwt(subject, authority, tenant, client, null);
    }

    private JwtAuthenticationToken jwt(
            boolean subject, boolean authority, String tenant, String client, String name) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", tenant).claim("client_id", client)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (subject) builder.subject("operator-123");
        Jwt token = builder.build();
        List<SimpleGrantedAuthority> authorities = authority
                ? List.of(new SimpleGrantedAuthority(
                AgentWorkItemReassignmentController.REASSIGN_AUTHORITY)) : List.of();
        return name == null ? new JwtAuthenticationToken(token, authorities)
                : new JwtAuthenticationToken(token, authorities, name);
    }

    private String body() {
        return "{\"expectedTaskVersion\":7,\"expectedWorkItemVersion\":4,"
                + "\"expectedPreviousAgentId\":\"agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                + "\"targetAgentId\":\"agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
                + "\"sourceCommandId\":\"cmd-old\","
                + "\"reason\":\"authoritative_lease_expired\"}";
    }
}
