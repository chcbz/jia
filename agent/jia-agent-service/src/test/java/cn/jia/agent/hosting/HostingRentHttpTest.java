package cn.jia.agent.hosting;

import cn.jia.agent.api.AgentController;
import cn.jia.agent.api.AgentHostingRentController;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HostingRentHttpTest {
    private static final String AGENT = "agt_0123456789abcdef0123456789abcdef";
    private static final String KEY = "00000000-0000-0000-0000-000000000001";
    private static final String QUOTES = "/agent/personas/wuyong/hosting-rent/quotes";
    private static final String BIND = "/agent/personas/wuyong/bind";
    private static final HostingRentHttp.Actor ACTOR = new HostingRentHttp.Actor("Login-A", "Tenant-A", "Client-A");
    private final HostingRentApplicationService application = mock(HostingRentApplicationService.class);
    private final AgentService agents = mock(AgentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new AgentController(agents, mock(AbilityEvaluationService.class));
        controller.setHostingRent(application);
        mvc = MockMvcBuilders.standaloneSetup(controller, new AgentHostingRentController(application)).build();
    }

    @Test
    void quoteAndConfirmationWireUsesExactJwtActorAndCanonicalStrings() throws Exception {
        when(application.quote(eq(ACTOR), eq("wuyong"), eq(KEY), anyMap())).thenReturn(quote());
        mvc.perform(post(QUOTES).principal(jwt()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"INITIAL\",\"agentId\":null}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.amountMicro").value("1000000000"))
                .andExpect(jsonPath("$.data.periodSeconds").value("2592000"))
                .andExpect(jsonPath("$.data.planVersion").value("1"))
                .andExpect(jsonPath("$.data.expiresAt").value("1800000300000"));
        when(application.bind(eq(ACTOR), eq("wuyong"), eq(KEY), anyMap())).thenReturn(receipt());
        String first = mvc.perform(post(BIND).principal(jwt()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(confirmation()))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.status").value("FUNDS_RESERVED"))
                .andExpect(jsonPath("$.data.occurredAt").value("1800000000000"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, mvc.perform(post(BIND).principal(jwt()).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(confirmation())).andReturn().getResponse().getContentAsString());
        verify(application, times(2)).bind(eq(ACTOR), eq("wuyong"), eq(KEY), anyMap());
        verifyNoInteractions(agents);
    }

    @Test
    void freeReprovisionHasFrozenZeroPriceReceiptNotPaidOrRenewalFields() throws Exception {
        var free = new HostingRentApplicationService.ReprovisionView("hrr-test", AGENT, "hrl-test", "REPROVISION", "ACCEPTED",
                "SILVER", "0", "5", "1802592000000", "1800000000000");
        when(application.bind(eq(ACTOR), eq("wuyong"), eq(KEY), anyMap())).thenReturn(free);
        mvc.perform(post(BIND).principal(jwt()).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"server\",\"hostingAction\":\"REPROVISION\",\"agentId\":\"" + AGENT
                                + "\",\"leaseId\":\"hrl-test\",\"expectedLeaseVersion\":\"4\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.requestId").value("hrr-test"))
                .andExpect(jsonPath("$.data.amountMicro").value("0"))
                .andExpect(jsonPath("$.data.leaseVersion").value("5"))
                .andExpect(jsonPath("$.data.paidThrough").value("1802592000000"))
                .andExpect(jsonPath("$.data.transactionId").doesNotExist())
                .andExpect(jsonPath("$.data.periodSeconds").doesNotExist());
        verifyNoInteractions(agents);
    }

    @Test
    void leaseAndExplicitRenewalHaveSeparateRoutesAndPassVersionAsString() throws Exception {
        when(application.lookup(ACTOR, AGENT)).thenReturn(new HostingRentApplicationService.LeaseView(false, null, null, "NOT_MANAGED"));
        mvc.perform(get("/agent/" + AGENT + "/hosting-lease").principal(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.managed").value(false));
        when(application.renewalQuote(eq(ACTOR), eq("hrl-test"), eq(KEY), anyMap())).thenReturn(quote());
        mvc.perform(post("/agent/hosting-leases/hrl-test/renewal-quotes").principal(jwt()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":\"" + AGENT + "\",\"expectedLeaseVersion\":\"2\"}"))
                .andExpect(status().isOk());
        when(application.renew(eq(ACTOR), eq("hrl-test"), eq(KEY), anyMap())).thenReturn(receipt());
        mvc.perform(post("/agent/hosting-leases/hrl-test/renewals").principal(jwt()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":\"" + AGENT + "\",\"quoteId\":\"hrq-test\","
                                + "\"expectedLeaseVersion\":\"2\",\"expectedPlanVersion\":\"1\",\"expectedAmountMicro\":\"1000000000\",\"expectedPeriodSeconds\":\"2592000\"}"))
                .andExpect(status().isOk());
        verify(application).renewalQuote(ACTOR, "hrl-test", KEY, Map.of("agentId", AGENT, "expectedLeaseVersion", "2"));
        verify(application, never()).bind(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void forgedClaimsMissingKeyDuplicateKeysAndNumericMoneyCannotReachApplication() throws Exception {
        mvc.perform(post(BIND).contentType(MediaType.APPLICATION_JSON).content(confirmation()))
                .andExpect(status().isUnauthorized());
        var fallback = new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none")
                .claim("uid", "Login-A").claim("jiacn", "Tenant-A").claim("client_id", "Client-A").build(), List.of());
        mvc.perform(post(BIND).principal(fallback).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(confirmation())).andExpect(status().isForbidden());
        mvc.perform(post(QUOTES).principal(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"INITIAL\"}"))
                .andExpect(status().isBadRequest());
        for (String malformed : List.of("{\"purpose\":\"INITIAL\",\"purpose\":\"INITIAL\"}", "{} {}", "[]",
                "{\"purpose\":\"INITIAL\",\"owner\":\"forged\"}", "{\"agentId\":1}")) {
            mvc.perform(post(QUOTES).principal(jwt()).header("Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(malformed)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(BIND).principal(jwt()).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation().replace("\"1000000000\"", "1000000000")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(QUOTES + "?actor=forged").principal(jwt()).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"INITIAL\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(QUOTES).principal(jwt()).header("Idempotency-Key", KEY, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"INITIAL\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(application, agents);
    }

    @Test
    void localAndDefaultBindingStayOnOriginalServiceWithoutRentIdentityOrHeaders() throws Exception {
        for (String body : List.of("null", "{}", "{\"mode\":null}", "{\"mode\":\" \"}")) {
            mvc.perform(post(BIND).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        mvc.perform(post(BIND).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"local\"}"))
                .andExpect(status().isOk());
        verify(agents, times(4)).bindPersona("wuyong");
        verify(agents).bindPersona("wuyong", "local");
        verifyNoInteractions(application);
    }

    @Test
    void positiveDecimalsAndHashingNeverCoerceOrDependOnObjectOrder() {
        for (String bad : List.of("0", "01", "+1", "1.0", "1e3", " 1", "1 ", "9223372036854775808")) {
            assertThrows(HostingRentApplicationException.class, () -> HostingRentHttp.positive(bad));
        }
        assertEquals(1000000000L, HostingRentHttp.positive("1000000000"));
        var first = new java.util.LinkedHashMap<String, String>(); first.put("a", "1"); first.put("b", "2");
        var second = new java.util.LinkedHashMap<String, String>(); second.put("b", "2"); second.put("a", "1");
        assertArrayEquals(HostingRentHttp.hash("confirm", first), HostingRentHttp.hash("confirm", second));
        assertFalse(java.util.Arrays.equals(HostingRentHttp.hash("confirm", first), HostingRentHttp.hash("renew", first)));
    }

    private HostingRentApplicationService.QuoteView quote() {
        return new HostingRentApplicationService.QuoteView("hrq-test", "INITIAL", "wuyong", AGENT, null, null,
                "agent-hosting-preview", "1", "SILVER", "1000000000", "2592000", "1800000300000");
    }
    private HostingRentApplicationService.MutationView receipt() {
        return new HostingRentApplicationService.MutationView(AGENT, "hri-test", "hrl-test", "hrq-test", "etx-test",
                "FUNDS_RESERVED", "SILVER", "1000000000", "2592000", "1800000000000");
    }
    private String confirmation() {
        return "{\"mode\":\"server\",\"hostingAction\":\"INITIAL\",\"agentId\":\"" + AGENT
                + "\",\"quoteId\":\"hrq-test\",\"expectedPlanVersion\":\"1\",\"expectedAmountMicro\":\"1000000000\",\"expectedPeriodSeconds\":\"2592000\"}";
    }
    private JwtAuthenticationToken jwt() {
        return new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none").subject("Login-A")
                .claim("jiacn", "Tenant-A").claim("client_id", "Client-A").build(), List.of());
    }
}
