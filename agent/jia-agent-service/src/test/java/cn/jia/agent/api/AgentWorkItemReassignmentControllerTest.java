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

    @BeforeEach
    void setUp() {
        service = mock(AgentWorkItemReassignmentService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentWorkItemReassignmentController(service)).build();
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
    void strictRequestRejectsInjectedScopeDuplicateFieldsAndExtraQuery() throws Exception {
        JwtAuthenticationToken jwt = jwt(true, true, "tenant-a", "client-a");
        for (String value : List.of(
                body().replace("\"reason\":", "\"tenantId\":\"forged\",\"reason\":"),
                body().replace("\"reason\":", "\"operatorSubject\":\"forged\",\"reason\":"),
                body().replace("\"reason\":", "\"targetAgentId\":\"agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"reason\":"),
                body() + " {}")) {
            mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                            .header("Idempotency-Key", "reassign-key-0001")
                            .queryParam("actorAgentId", ACTOR)
                            .contentType(MediaType.APPLICATION_JSON).content(value)
                            .principal(jwt))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR).queryParam("tenantId", "forged")
                        .contentType(MediaType.APPLICATION_JSON).content(body()).principal(jwt))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedScopeAndOperatorAreSoleForwardedIdentity() throws Exception {
        AgentWorkItemReassignmentResultDTO result = new AgentWorkItemReassignmentResultDTO();
        result.setReassignmentId("rsn-1");
        result.setTargetAgentId("agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        result.setIdempotentReplay(true);
        when(service.reassign(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any())).thenReturn(result);

        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body())
                        .principal(jwt(true, true, "Tenant-A", "Client-A")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        verify(service).reassign(eq("Tenant-A"), eq("Client-A"), eq("operator-123"),
                eq(ACTOR), eq("task-1"), eq("work-1"), eq("reassign-key-0001"),
                any(AgentWorkItemReassignmentRequestDTO.class));
    }

    @Test
    void leaseReadRequiresJwtScopeAndExactTargetActorButNotOperatorAuthority() throws Exception {
        AgentWorkItemReassignmentLeaseDTO lease = new AgentWorkItemReassignmentLeaseDTO();
        lease.setLeaseToken("secret-target-token");
        when(service.readLease(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(lease);

        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments/rsn-1/lease")
                        .queryParam("actorAgentId", "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"cmd-new\",\"expectedWorkItemVersion\":5}")
                        .principal(targetJwt("agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseToken").value("secret-target-token"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        verify(service).readLease(eq("tenant-a"), eq("client-a"),
                eq("agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), eq("task-1"), eq("work-1"),
                eq("rsn-1"), any());
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
    void requestBodyIsReadThroughTheBoundedStreamBeforeJsonParsing() throws Exception {
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"reason\":\"" + "x".repeat(
                                AgentWorkItemReassignmentController.MAX_BODY_BYTES) + "\"}")
                        .principal(jwt(true, true, "tenant-a", "client-a")))
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
    void domainErrorsAreNonLeakingAndStable() throws Exception {
        doThrow(new AgentWorkItemReassignmentException(
                AgentWorkItemReassignmentException.Reason.LEASE_NOT_EXPIRED,
                "secret lease token and database detail"))
                .when(service).reassign(anyString(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString(), any());
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments")
                        .header("Idempotency-Key", "reassign-key-0001")
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body())
                        .principal(jwt(true, true, "tenant-a", "client-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ITEM_LEASE_LIVE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret lease token"))));
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

    @Test
    void leaseMutationsUseExactAuthenticatedTargetWithoutOperatorAuthority() throws Exception {
        String target = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments/rsn-1/lease/start")
                        .queryParam("actorAgentId", target)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"cmd-new\",\"expectedWorkItemVersion\":5}")
                        .principal(targetJwt(target)))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/reassignments/rsn-1/lease/heartbeat")
                        .queryParam("actorAgentId", target)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"cmd-new\",\"expectedWorkItemVersion\":6,\"leaseDurationMillis\":1000}")
                        .principal(targetJwt(target)))
                .andExpect(status().isOk());
        verify(service).startLease(eq("tenant-a"), eq("client-a"), eq(target),
                eq("task-1"), eq("work-1"), eq("rsn-1"), any());
        verify(service).heartbeatLease(eq("tenant-a"), eq("client-a"), eq(target),
                eq("task-1"), eq("work-1"), eq("rsn-1"), any());
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
