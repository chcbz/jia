package cn.jia.economy.api;

import cn.jia.economy.config.EconomyCampaignProperties;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.entity.EconomyWalletLedgerRow;
import cn.jia.economy.entity.EconomyWalletSnapshotRow;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyTreasuryPostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EconomyWalletControllerTest {
    private static final String TENANT = "0";
    private static final String OWNER = "Owner-A";
    private static final String CLIENT = "Client-A";
    private static final String USER = "user-1";
    private static final String KEY = "00000000-0000-0000-0000-000000000001";

    private EconomyLedgerMapper mapper;
    private EconomyTreasuryPostingService treasury;
    private MockEnvironment environment;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mapper = mock(EconomyLedgerMapper.class);
        treasury = mock(EconomyTreasuryPostingService.class);
        environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        mvc = mvc(gate(true));
    }

    @Test
    void walletUsesTrustedScopeAndReturnsExactMoneyWire() throws Exception {
        when(mapper.selectUserWalletSnapshot(TENANT, CLIENT, USER)).thenReturn(snapshot(
                1200000000L, 300000000L, 17L));

        mvc.perform(get("/economy/wallet").principal(auth(OWNER, CLIENT, USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("WEN"))
                .andExpect(jsonPath("$.data.available.amountMinor").value("1200000000"))
                .andExpect(jsonPath("$.data.held.amountMinor").value("300000000"))
                .andExpect(jsonPath("$.data.version").value("17"));
        verify(mapper).selectUserWalletSnapshot(TENANT, CLIENT, USER);
    }

    @Test
    void walletFailsClosedWhenAnyHeldComponentIsNegative() throws Exception {
        when(mapper.selectUserWalletSnapshot(TENANT, CLIENT, USER)).thenReturn(new EconomyWalletSnapshotRow()
                .setAvailableWen(100L).setHeldWen(20L).setMinimumHeldComponentWen(-1L).setVersion(1L));

        mvc.perform(get("/economy/wallet").principal(auth(OWNER, CLIENT, USER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ECONOMY_UNAVAILABLE"));
        verify(mapper).selectUserWalletSnapshot(TENANT, CLIENT, USER);
    }

    @Test
    void emptyWalletIsReadOnlyZeroAndDisabledOrPoisonedScopeDoesNotReachMapper() throws Exception {
        when(mapper.selectUserWalletSnapshot(TENANT, CLIENT, USER)).thenReturn(snapshot(0L, 0L, 0L));
        mvc.perform(get("/economy/wallet").principal(auth(OWNER, CLIENT, USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available.amountMinor").value("0"))
                .andExpect(jsonPath("$.data.held.amountMinor").value("0"))
                .andExpect(jsonPath("$.data.version").value("0"));

        mvc(gate(false)).perform(get("/economy/wallet").principal(auth(OWNER, CLIENT, USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ECONOMY_PREVIEW_DISABLED"));
        mvc.perform(get("/economy/wallet").principal(auth("Owner-B", CLIENT, USER)))
                .andExpect(status().isOk());
        mvc.perform(get("/economy/wallet").principal(auth(OWNER, "Client-B", USER)))
                .andExpect(status().isForbidden());
        verify(mapper, org.mockito.Mockito.times(2)).selectUserWalletSnapshot(TENANT, CLIENT, USER);
    }

    @Test
    void issuanceRejectsUnknownDuplicateMalformedAndProductionInputBeforeTreasury() throws Exception {
        String valid = "{\"amount\":{\"amountMinor\":\"100\",\"currency\":\"WEN\"},\"campaignRef\":\"preview-welcome\"}";
        for (String body : List.of(
                "{\"amountWen\":\"100\",\"campaignRef\":\"preview-welcome\",\"scope\":\"Tenant-B\"}",
                "{\"amountWen\":\"100\",\"amountWen\":\"101\",\"campaignRef\":\"preview-welcome\"}",
                "{\"amountWen\":100,\"campaignRef\":\"preview-welcome\"}",
                "{\"amountWen\":\"01\",\"campaignRef\":\"preview-welcome\"} trailing")) {
            mvc.perform(post("/economy/preview/issuances").principal(auth(OWNER, CLIENT, USER))
                            .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        mvc.perform(post("/economy/preview/issuances").principal(auth(OWNER, CLIENT, USER))
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/economy/preview/issuances").principal(auth(OWNER, CLIENT, USER))
                        .header("Idempotency-Key", KEY, KEY).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/economy/preview/issuances?x=1").principal(auth(OWNER, CLIENT, USER))
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest());

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("dev", "prod");
        mvc = mvc(gate(true), prod);
        mvc.perform(post("/economy/preview/issuances").principal(auth(OWNER, CLIENT, USER))
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ECONOMY_PREVIEW_DISABLED"));
        verify(treasury, never()).issue(any());
    }

    @Test
    void issuanceUsesTreasuryAndReturnsFrozenReceiptWithoutWalletState() throws Exception {
        when(treasury.issue(any())).thenReturn(new EconomyPostingResult("etx_1", "POSTED", "WEN", 99L,
                100L, 100L, List.of(), null));
        mvc.perform(post("/economy/preview/issuances").principal(auth(OWNER, CLIENT, USER))
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"amountMinor\":\"100\",\"currency\":\"WEN\"},\"campaignRef\":\"preview-welcome\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value("etx_1"))
                .andExpect(jsonPath("$.data.status").value("POSTED"))
                .andExpect(jsonPath("$.data.amount.amountMinor").value("100"))
                .andExpect(jsonPath("$.data.available.amountMinor").doesNotExist());
        ArgumentCaptor<EconomyPostingCommand> command = ArgumentCaptor.forClass(EconomyPostingCommand.class);
        verify(treasury).issue(command.capture());
        org.junit.jupiter.api.Assertions.assertEquals(TENANT, command.getValue().scope().tenantId());
        org.junit.jupiter.api.Assertions.assertEquals(CLIENT, command.getValue().scope().clientId());
        org.junit.jupiter.api.Assertions.assertEquals(USER, command.getValue().principal().id());
        org.junit.jupiter.api.Assertions.assertEquals("preview-welcome", command.getValue().businessId());
    }

    @Test
    void productionOnboardingGrantUsesServerCampaignAndStableScope() throws Exception {
        when(treasury.issue(any())).thenReturn(new EconomyPostingResult("etx_welcome", "POSTED", "WEN", 99L,
                3500L, 3500L, List.of(), null));
        mvc = mvc(gate(true), environment,
                new EconomyCampaignProperties(List.of(new EconomyCampaignProperties.Campaign("beta-hall-welcome-v1", EconomyCampaignProperties.Event.HALL_ENTERED, true, 3500, null, null))));

        mvc.perform(post("/economy/events").principal(auth(OWNER, CLIENT, USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"HALL_ENTERED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants[0].transactionId").value("etx_welcome"))
                .andExpect(jsonPath("$.data.grants[0].amount.amountMinor").value("3500"))
                .andExpect(jsonPath("$.data.grants[0].campaignRef").value("beta-hall-welcome-v1"));

        ArgumentCaptor<EconomyPostingCommand> command = ArgumentCaptor.forClass(EconomyPostingCommand.class);
        verify(treasury).issue(command.capture());
        org.junit.jupiter.api.Assertions.assertEquals("0", command.getValue().scope().tenantId());
        org.junit.jupiter.api.Assertions.assertEquals(CLIENT, command.getValue().scope().clientId());
        org.junit.jupiter.api.Assertions.assertEquals(USER, command.getValue().principal().id());
        org.junit.jupiter.api.Assertions.assertEquals("beta-hall-welcome-v1", command.getValue().businessId());
        org.junit.jupiter.api.Assertions.assertTrue(command.getValue().idempotencyKey()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    void campaignEventRejectsClientAmountsAndDisabledCampaignIsNoOp() throws Exception {
        mvc.perform(post("/economy/events").principal(auth(OWNER, CLIENT, USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountWen\":\"9999999999\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/economy/events").principal(auth(OWNER, CLIENT, USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"HALL_ENTERED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants").isEmpty());
        verify(treasury, never()).issue(any());
    }

    @Test
    void ledgerIsUserAvailableOnlyBoundedAndCursorStable() throws Exception {
        when(mapper.selectUserAvailableLedger(eq(TENANT), eq(CLIENT), eq(USER), eq(null), eq(null), eq(51)))
                .thenReturn(List.of(row(9L, "etx_9", "een_9", 100L, 200L),
                        row(8L, "etx_8", "een_8", -40L, 199L)));
        mvc.perform(get("/economy/ledger?pageSize=50").principal(auth(OWNER, CLIENT, USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.data.items[0].amount.amountMinor").value("100"))
                .andExpect(jsonPath("$.data.items[0].postedAt").value("200"))
                .andExpect(jsonPath("$.data.items[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.data.items[1].amount.amountMinor").value("40"))
                .andExpect(jsonPath("$.data.nextCursor", nullValue()));
        mvc.perform(get("/economy/ledger?pageSize=101").principal(auth(OWNER, CLIENT, USER)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/economy/ledger?pageSize=1&pageSize=2").principal(auth(OWNER, CLIENT, USER)))
                .andExpect(status().isBadRequest());
    }

    private MockMvc mvc(EconomyPreviewGate gate) {
        return mvc(gate, environment);
    }

    private MockMvc mvc(EconomyPreviewGate gate, MockEnvironment activeEnvironment) {
        return mvc(gate, activeEnvironment, EconomyCampaignProperties.disabled());
    }

    private MockMvc mvc(EconomyPreviewGate gate, MockEnvironment activeEnvironment,
            EconomyCampaignProperties onboardingGrant) {
        return MockMvcBuilders.standaloneSetup(new EconomyWalletController(gate,
                new EconomyWalletService(mapper, treasury, activeEnvironment, gate, onboardingGrant))).build();
    }

    private static EconomyPreviewGate gate(boolean enabled) {
        return new EconomyPreviewGate(new EconomyPreviewProperties(enabled, true,
                List.of(new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT))));
    }

    private static EconomyWalletSnapshotRow snapshot(long available, long held, long version) {
        return new EconomyWalletSnapshotRow().setAvailableWen(available).setHeldWen(held)
                .setMinimumHeldComponentWen(held == 0 ? 0L : held).setVersion(version);
    }

    private static EconomyWalletLedgerRow row(long id, String transactionId, String entryId,
                                               long signed, long postedAt) {
        return new EconomyWalletLedgerRow().setRowId(id).setTransactionId(transactionId).setEntryId(entryId)
                .setBusinessType("ISSUE_WEN").setBusinessRef("preview-welcome")
                .setSignedAmountWen(signed).setStatus("POSTED").setPostedAt(postedAt);
    }

    private static JwtAuthenticationToken auth(String tenant, String client, String subject) {
        Jwt token = Jwt.withTokenValue("token").claim("tenant_id", "0").header("alg", "none")
                .claim("jiacn", tenant).claim("client_id", client).subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
