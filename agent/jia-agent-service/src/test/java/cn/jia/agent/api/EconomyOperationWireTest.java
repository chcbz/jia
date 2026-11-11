package cn.jia.agent.api;

import cn.jia.agent.hosting.HostingRentApplicationService;
import cn.jia.agent.entity.funding.AgentTaskClaimReceiptDTO;
import cn.jia.agent.service.HallReadService;
import cn.jia.agent.service.HallPrivateMarkService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EconomyOperationWireTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void claimTimeIsNumberButVersionAboveJsSafeRangeIsUnchanged() {
        var input = new AgentTaskClaimReceiptDTO("task","agent","quote","assigned","9007199254740993","1000");
        var value = json.readTree(json.writeValueAsString(EconomyOperationWire.from(input)));
        assertTrue(value.get("claimedAt").isIntegralNumber());
        assertEquals(1000, value.get("claimedAt").longValue());
        assertEquals("9007199254740993", value.get("taskVersion").asText());
        assertEquals("1000", input.claimedAt());
        assertThrows(IllegalArgumentException.class, () -> EconomyOperationWire.from(
                new AgentTaskClaimReceiptDTO("t","a","q","assigned","1","9007199254740992")));
    }
    @Test void hostingQuoteIsProjectedByActualHttpBoundaryAndRetainsAuthentication() throws Exception {
        var service = mock(HostingRentApplicationService.class);
        var quote = new HostingRentApplicationService.QuoteView("q","INITIAL","linchong","agent",null,null,
                "plan","9007199254740993","WEN","1234500000","172800","2000","1234499850","3","150");
        when(service.quote(any(),eq("linchong"),eq("00000000-0000-4000-8000-000000000001"),any())).thenReturn(quote);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AgentHostingRentController(service))
                .setControllerAdvice(new cn.jia.core.config.ApiEnvelopeAdvice(),new cn.jia.core.config.ApiRequestEnvelopeAdvice(),new cn.jia.core.config.ExceptionHandlerAdvice())
                .addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture").claim("tenant_id", "0").header("alg","none")
                .subject("owner").claim("jiacn","owner").claim("client_id","client").build();
        var auth = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt);
        auth.setAuthenticated(true);
        mvc.perform(post("/agent/personas/linchong/hosting-rent/quotes").principal(auth).header("Idempotency-Key","00000000-0000-4000-8000-000000000001")
                .contentType("application/json").content("{\"protocolVersion\":1,\"requestId\":\"quote-test\",\"data\":{\"purpose\":\"INITIAL\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresAt").value(2000))
                .andExpect(jsonPath("$.data.planVersion").value("9007199254740993"))
                .andExpect(jsonPath("$.data.amountWen").value("1234500000"))
                .andExpect(jsonPath("$.data.recurringAmountWen").value("1234499850"))
                .andExpect(jsonPath("$.data.initialSkillCount").value("3"))
                .andExpect(jsonPath("$.data.initialSkillAmountWen").value("150"))
                .andExpect(jsonPath("$.data.leaseId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.requestId").value("quote-test"));
        mvc.perform(get("/agent/agent/hosting-lease")).andExpect(status().isUnauthorized());
        verify(service,times(1)).quote(any(),eq("linchong"),eq("00000000-0000-4000-8000-000000000001"),any());
        verify(service,never()).lookup(any(),any());
    }
    @Test void hallReadMarkUsesExactDecimalRevisionWithoutChangingStoredView() {
        var input = new HallReadService.ItemSummary(new HallReadService.Ref("PRIVATE_CASE","case"),"title",
                new HallReadService.Status("QUEUED","PERSISTED",100),null,"VIEW",java.util.List.of("VIEW"),100,
                new HallReadService.PersonalMark(9007199254740993L,true,null),null,null);
        var value = json.readTree(json.writeValueAsString(HallReadWire.from(input)));
        assertEquals("9007199254740993",value.get("personalMark").get("revision").asText());
        assertTrue(value.get("personalMark").get("viewedResultRef").isNull());
        assertEquals(9007199254740993L,input.personalMark().revision());
    }
    @Test void leaseProjectionIncludesImmutablePricingPlan() {
        var lease = new HostingRentApplicationService.LeaseSnapshot("lease", "agent", "linchong", "binding", "2",
                "ACTIVE", "1", "50", "2592000", "1000", "2592001000", "WEN", "agent-self-hosted");
        var value = json.readTree(json.writeValueAsString(EconomyOperationWire.from(lease)));
        assertEquals("agent-self-hosted", value.get("planId").asText());
        assertEquals("50", value.get("amountWen").asText());
        assertEquals(2592001000L, value.get("paidThrough").asLong());
    }

    @Test void bothModesUseActualPaidHttpBoundaryWithoutFreeBindingFallback() throws Exception {
        var service = mock(HostingRentApplicationService.class);
        var agents = mock(cn.jia.agent.service.AgentService.class);
        var controller = new AgentController(agents, mock(cn.jia.agent.service.AbilityEvaluationService.class));
        controller.setHostingRent(service);
        when(service.bind(any(), eq("linchong"), anyString(), any())).thenReturn(
                new HostingRentApplicationService.MutationView("agent", "intent", "lease", "quote", "tx",
                        "FUNDS_RESERVED", "WEN", "50", "2592000", "2000"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new cn.jia.core.config.ApiEnvelopeAdvice(), new cn.jia.core.config.ApiRequestEnvelopeAdvice(), new cn.jia.core.config.ExceptionHandlerAdvice())
                .addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture").claim("tenant_id", "0").header("alg", "none")
                .subject("owner").claim("jiacn", "owner").claim("client_id", "client").build();
        var auth = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt);
        auth.setAuthenticated(true);
        for (String mode : java.util.List.of("server", "local")) {
            mvc.perform(post("/agent/personas/linchong/bind").principal(auth)
                    .header("Idempotency-Key", "00000000-0000-4000-8000-000000000001").contentType("application/json")
                    .content("{\"protocolVersion\":1,\"requestId\":\"bind-test\",\"data\":{\"mode\":\"" + mode + "\",\"hostingAction\":\"INITIAL\"}}"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("FUNDS_RESERVED"))
                    .andExpect(jsonPath("$.data.occurredAt").value(2000));
            verify(service).bind(any(), eq("linchong"), anyString(), eq(java.util.Map.of("mode", mode, "hostingAction", "INITIAL")));
        }
        mvc.perform(post("/agent/personas/linchong/bind").principal(auth).contentType("application/json")
                .content("{\"protocolVersion\":1,\"requestId\":\"free-test\",\"data\":{\"mode\":\"local\"}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(agents);
        verifyNoMoreInteractions(service);
    }

}
