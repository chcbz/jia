package cn.jia.agent.api;

import cn.jia.agent.entity.AgentCommandDlqEntry;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandOperationResult;
import cn.jia.agent.entity.AgentCommandOperationType;
import cn.jia.agent.service.AgentCommandOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentCommandOperationsControllerTest {
    private AgentCommandOperationsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AgentCommandOperationsService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentCommandOperationsController(service)).build();
    }

    @Test
    void directJwtScopeAndDedicatedAuthorityAreSoleReadIdentity() throws Exception {
        when(service.listDlq("tenant-a", "client-a", 0, 50)).thenReturn(List.of(
                new AgentCommandDlqEntry(
                        7L, "cmd-1", "event-1", "message-1", "task-1", "agent-a",
                        "PUBLISHED", "PUBLISHED", null, null, 1, 1,
                        "00".repeat(32), 1_700_000_000_000L, null,
                        1_700_000_600_000L, 1_700_000_000_001L)));

        mvc.perform(get("/agent/internal/command-operations/dlq")
                        .principal(jwt("operator-a", "approver-b",
                                AgentCommandOperationsController.AUTHORITY_READ)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"deliveryStatus\":\"PUBLISHED\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"outboxStatus\":\"PUBLISHED\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("wirePayload"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("commandPayload"))));

        verify(service).listDlq("tenant-a", "client-a", 0, 50);
    }

    @Test
    void normalHallAuthorityCannotEnterAndAuthenticationPrecedesSyntax() throws Exception {
        mvc.perform(get("/agent/internal/command-operations/dlq")
                        .queryParam("limit", "01")
                        .principal(jwt("operator-a", "approver-b", "agent-task-read")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verify(service, never()).listDlq(any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void manualReissueTakesRequesterAndDistinctApproverOnlyFromTrustedClaims() throws Exception {
        when(service.manualReissue(any(), anyLong())).thenReturn(new AgentCommandOperationResult(
                "op-1", AgentCommandOperationType.MANUAL_REISSUE, "SUCCEEDED", 7L, "cmd-1",
                "message-1", "message-2", 1, 2, null, 1_700_000_000_000L));
        String body = """
                {"taskId":"task-1","targetAgentId":"agent-a","sourceMessageId":"message-1",
                 "reason":"incident recovery","ticketReference":"INC-42"}
                """;

        mvc.perform(post("/agent/internal/command-operations/deliveries/7/reissue")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .principal(jwt("operator-a", "approver-b",
                                AgentCommandOperationsController.AUTHORITY_REISSUE)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("tenant-a"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("client-a"))));

        var captor = org.mockito.ArgumentCaptor.forClass(AgentCommandOperationRequest.class);
        verify(service).manualReissue(captor.capture(), anyLong());
        org.junit.jupiter.api.Assertions.assertEquals("tenant-a", captor.getValue().tenantId());
        org.junit.jupiter.api.Assertions.assertEquals("client-a", captor.getValue().clientId());
        org.junit.jupiter.api.Assertions.assertEquals("operator-a", captor.getValue().requesterId());
        org.junit.jupiter.api.Assertions.assertEquals("approver-b", captor.getValue().approverId());
    }

    @Test
    void requesterEqualApproverFailsClosedBeforeService() throws Exception {
        mvc.perform(post("/agent/internal/command-operations/deliveries/7/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"task-1\",\"targetAgentId\":\"agent-a\","
                                + "\"sourceMessageId\":\"message-1\",\"reason\":\"reason\","
                                + "\"ticketReference\":\"INC-42\"}")
                        .principal(jwt("operator-a", "operator-a",
                                AgentCommandOperationsController.AUTHORITY_REISSUE)))
                .andExpect(status().isForbidden());
        verify(service, never()).manualReissue(any(), anyLong());
    }

    @Test
    void nonJwtAndJwtNameClaimDriftFailBeforeService() throws Exception {
        var nonJwt = new UsernamePasswordAuthenticationToken(
                "operator-a", "ignored",
                List.of(new SimpleGrantedAuthority(
                        AgentCommandOperationsController.AUTHORITY_READ)));
        mvc.perform(get("/agent/internal/command-operations/metrics").principal(nonJwt))
                .andExpect(status().isUnauthorized());

        Jwt base = jwtToken("operator-a", "approver-b");
        JwtAuthenticationToken drift = new JwtAuthenticationToken(
                base, List.of(new SimpleGrantedAuthority(
                        AgentCommandOperationsController.AUTHORITY_READ)), "different-name");
        mvc.perform(get("/agent/internal/command-operations/metrics").principal(drift))
                .andExpect(status().isForbidden());
        verify(service, never()).metrics(any(), any(), anyLong());
    }

    @Test
    void bodyRejectsUnknownIdentityFieldsAndUnpairedSurrogates() throws Exception {
        String extraIdentity = """
                {"taskId":"task-1","targetAgentId":"agent-a","sourceMessageId":"message-1",
                 "reason":"incident recovery","ticketReference":"INC-42",
                 "requesterId":"forged","approverId":"forged"}
                """;
        mvc.perform(post("/agent/internal/command-operations/dlq/7/redrive")
                        .contentType(MediaType.APPLICATION_JSON).content(extraIdentity)
                        .principal(jwt("operator-a", "approver-b",
                                AgentCommandOperationsController.AUTHORITY_REDRIVE)))
                .andExpect(status().isBadRequest());

        String invalidUnicode = "{\"taskId\":\"task-1\",\"targetAgentId\":\"agent-a\","
                + "\"sourceMessageId\":\"message-1\",\"reason\":\"bad\\uD800\","
                + "\"ticketReference\":\"INC-42\"}";
        mvc.perform(post("/agent/internal/command-operations/dlq/7/redrive")
                        .contentType(MediaType.APPLICATION_JSON).content(invalidUnicode)
                        .principal(jwt("operator-a", "approver-b",
                                AgentCommandOperationsController.AUTHORITY_REDRIVE)))
                .andExpect(status().isBadRequest());

        String duplicate = "{\"taskId\":\"task-1\",\"taskId\":\"task-2\","
                + "\"targetAgentId\":\"agent-a\",\"sourceMessageId\":\"message-1\","
                + "\"reason\":\"reason\",\"ticketReference\":\"INC-42\"}";
        mvc.perform(post("/agent/internal/command-operations/dlq/7/redrive")
                        .contentType(MediaType.APPLICATION_JSON).content(duplicate)
                        .principal(jwt("operator-a", "approver-b",
                                AgentCommandOperationsController.AUTHORITY_REDRIVE)))
                .andExpect(status().isBadRequest());

        String trailing = "{\"taskId\":\"task-1\",\"targetAgentId\":\"agent-a\","
                + "\"sourceMessageId\":\"message-1\",\"reason\":\"reason\","
                + "\"ticketReference\":\"INC-42\"} {}";
        mvc.perform(post("/agent/internal/command-operations/dlq/7/redrive")
                        .contentType(MediaType.APPLICATION_JSON).content(trailing)
                        .principal(jwt("operator-a", "approver-b",
                                AgentCommandOperationsController.AUTHORITY_REDRIVE)))
                .andExpect(status().isBadRequest());
        verify(service, never()).brokerRedrive(any(), anyLong());
    }

    private JwtAuthenticationToken jwt(String subject, String approver, String authority) {
        return new JwtAuthenticationToken(jwtToken(subject, approver),
                List.of(new SimpleGrantedAuthority(authority)));
    }

    private Jwt jwtToken(String subject, String approver) {
        return Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "tenant-a").claim("client_id", "client-a")
                .claim("sub", subject).claim("agent_command_ops_approver", approver)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
