package cn.jia.agent.api;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.EconomyReadOnlyPreviewProperties;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.agent.mapper.EconomyReadOnlyPreviewMapper;
import cn.jia.agent.preview.EconomyReadOnlyPreviewService;
import cn.jia.economy.entity.EconomyWalletSnapshotRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EconomyReadOnlyPreviewControllerTest {
    private EconomyReadOnlyPreviewMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mapper = mock(EconomyReadOnlyPreviewMapper.class);
        var owners = mock(HostingRentOwnerResolver.class);
        when(owners.requireOwner(any(HostingRentHttp.Actor.class)))
                .thenAnswer(invocation -> invocation.<HostingRentHttp.Actor>getArgument(0).ownerJiacn());
        var service = new EconomyReadOnlyPreviewService(mapper, new EconomyReadOnlyPreviewProperties(true),
                new AgentHostingRentProperties(false, null, null, null), owners);
        mvc = MockMvcBuilders.standaloneSetup(new EconomyReadOnlyPreviewController(service)).setControllerAdvice(new cn.jia.core.config.ApiRequestEnvelopeAdvice(),new cn.jia.core.config.ApiEnvelopeAdvice()).addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
    }

    @Test
    void capabilitiesUseExactJwtScopeAndKeepEveryMutationFalse() throws Exception {
        mvc.perform(get("/economy/preview/capabilities").principal(jwt("Tenant-A", "Client-A", "actor-A")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.data.contractVersion").value("economy-readonly-v2"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.actions.estimate").value(true))
                .andExpect(jsonPath("$.data.actions.issue").value(false))
                .andExpect(jsonPath("$.data.actions.purchase").value(false))
                .andExpect(jsonPath("$.data.actions.install").value(false))
                .andExpect(jsonPath("$.data.actions.hostingActivate").value(false));
        verifyNoInteractions(mapper);
    }

    @Test
    void walletDoesNotNormalizeCaseTenantClientActorOrFallbackLegacyZero() throws Exception {
        when(mapper.selectWallet("Tenant-A", "Client-A", "actor-A"))
                .thenReturn(new EconomyWalletSnapshotRow().setAvailableMicro(1L).setHeldMicro(0L)
                        .setMinimumHeldComponentMicro(0L).setVersion(2L));
        mvc.perform(get("/economy/preview/wallet").principal(jwt("Tenant-A", "Client-A", "actor-A")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available.amountMinor").value("1"));
        verify(mapper).selectWallet("Tenant-A", "Client-A", "actor-A");

        for (JwtAuthenticationToken bad : List.of(jwt("0", "Client-A", "actor-A"),
                jwt("Tenant-A ", "Client-A", "actor-A"), jwt("Tenant-A", "0", "actor-A"),
                jwt("Tenant-A", "Client-A", "0"), jwtWithName("Tenant-A", "Client-A", "actor-A", "other"))) {
            mvc.perform(get("/economy/preview/wallet").principal(bad))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PREVIEW_SCOPE_UNAVAILABLE"));
        }
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void unauthenticatedAndUnknownQueriesFailBeforeDataAccess() throws Exception {
        mvc.perform(get("/economy/preview/wallet"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/economy/preview/wallet?tenant=forged")
                        .principal(jwt("Tenant-A", "Client-A", "actor-A")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PREVIEW_BAD_REQUEST"));
        mvc.perform(get("/economy/preview/ledger?limit=101")
                        .principal(jwt("Tenant-A", "Client-A", "actor-A")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/economy/preview/skill-products?offset=00")
                        .principal(jwt("Tenant-A", "Client-A", "actor-A")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test
    void estimateRejectsUnknownDuplicateNumericOverflowAndEstimatedAboveWorst() throws Exception {
        String valid = """
                {"grossBountyAmount":{"amountMinor":"1000000000","currency":"SILVER"},"minimumAcceptedPayout":{"amountMinor":"0","currency":"SILVER"},
                 "estimatedTokens":{"input":"18000","cachedInput":"4000","output":"6000","reasoning":"3000"},
                 "worstTokens":{"input":"36000","cachedInput":"8000","output":"12000","reasoning":"6000"}}
                """;
        mvc.perform(post("/economy/preview/bounty-estimates").principal(jwt("Tenant-A", "Client-A", "actor-A"))
                        .contentType(MediaType.APPLICATION_JSON).content(q(valid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SIMULATION"))
                .andExpect(jsonPath("$.data.charged").value(false))
                .andExpect(jsonPath("$.data.persisted").value(false))
                .andExpect(jsonPath("$.data.rateProvenance")
                        .value("SYNTHETIC_PREVIEW_FIXTURE_NOT_PROVIDER_PRICING"));
        for (String bad : List.of(
                valid.replace("\"amountMinor\":\"1000000000\"", "\"amountMinor\":1000000000"),
                valid.replace("\"amountMinor\":\"0\"", "\"amountMinor\":\"0\",\"owner\":\"forged\""),
                valid.replace("\"input\":\"18000\"", "\"input\":\"18000\",\"input\":\"18000\""),
                valid.replace("\"1000000000\"", "\"9223372036854775808\""),
                valid.replace("\"input\":\"36000\"", "\"input\":\"1\""))) {
            mvc.perform(post("/economy/preview/bounty-estimates")
                            .principal(jwt("Tenant-A", "Client-A", "actor-A"))
                            .contentType(MediaType.APPLICATION_JSON).content(q(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PREVIEW_BAD_REQUEST"));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void disabledCapabilitiesRemainReadableButOtherRoutesReturnStable403() throws Exception {
        var owners = mock(HostingRentOwnerResolver.class);
        var disabled = new EconomyReadOnlyPreviewService(mapper, new EconomyReadOnlyPreviewProperties(false),
                new AgentHostingRentProperties(false, null, null, null), owners);
        mvc = MockMvcBuilders.standaloneSetup(new EconomyReadOnlyPreviewController(disabled)).setControllerAdvice(new cn.jia.core.config.ApiRequestEnvelopeAdvice(),new cn.jia.core.config.ApiEnvelopeAdvice()).addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
        mvc.perform(get("/economy/preview/capabilities").principal(jwt("Tenant-A", "Client-A", "actor-A")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.features.wallet").value(false));
        mvc.perform(get("/economy/preview/wallet").principal(jwt("Tenant-A", "Client-A", "actor-A")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PREVIEW_DISABLED"))
                .andExpect(jsonPath("$.msg").value("Read-only economy preview is disabled"));
        verifyNoInteractions(mapper);
    }

    private static String q(String data) { return "{\"protocolVersion\":1,\"requestId\":\"test-request\",\"data\":"+data+"}"; }

    private static JwtAuthenticationToken jwt(String tenant, String client, String actor) {
        return jwtWithName(tenant, client, actor, actor);
    }

    private static JwtAuthenticationToken jwtWithName(
            String tenant, String client, String actor, String authenticationName) {
        Jwt token = Jwt.withTokenValue("token").header("alg", "none").claim("jiacn", tenant)
                .claim("client_id", client).subject(actor).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of(), authenticationName);
    }
}
