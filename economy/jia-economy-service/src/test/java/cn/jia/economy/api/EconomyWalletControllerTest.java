package cn.jia.economy.api;

import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyWalletLedgerRow;
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
    private static final String TENANT = "Tenant-A";
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
    void walletUsesJwtScopeOnlyAndReturnsExactStringWire() throws Exception {
        when(mapper.selectUserAvailableAccount(TENANT, CLIENT, USER)).thenReturn(new EconomyAccountEntity()
                .setBalanceMicro(1200000000L).setVersion(17L));
        when(mapper.selectHeldMicroComponents(TENANT, CLIENT, USER)).thenReturn(List.of(300000000L));

        mvc.perform(get("/economy/wallet").principal(auth(TENANT, CLIENT, USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("SILVER"))
                .andExpect(jsonPath("$.availableMicro").value("1200000000"))
                .andExpect(jsonPath("$.heldMicro").value("300000000"))
                .andExpect(jsonPath("$.version").value("17"));
        verify(mapper).selectUserAvailableAccount(TENANT, CLIENT, USER);
        verify(mapper).selectHeldMicroComponents(TENANT, CLIENT, USER);
    }

    @Test
    void emptyWalletIsReadOnlyZeroAndDisabledOrPoisonedScopeDoesNotReachMapper() throws Exception {
        when(mapper.selectUserAvailableAccount(TENANT, CLIENT, USER)).thenReturn(null);
        when(mapper.selectHeldMicroComponents(TENANT, CLIENT, USER)).thenReturn(List.of());
        mvc.perform(get("/economy/wallet").principal(auth(TENANT, CLIENT, USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableMicro").value("0"))
                .andExpect(jsonPath("$.heldMicro").value("0"))
                .andExpect(jsonPath("$.version").value("0"));

        mvc(gate(false)).perform(get("/economy/wallet").principal(auth(TENANT, CLIENT, USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ECONOMY_PREVIEW_DISABLED"));
        mvc.perform(get("/economy/wallet").principal(auth("Tenant-B", CLIENT, USER)))
                .andExpect(status().isForbidden());
        verify(mapper).selectUserAvailableAccount(TENANT, CLIENT, USER);
        verify(mapper).selectHeldMicroComponents(TENANT, CLIENT, USER);
    }

    @Test
    void issuanceRejectsUnknownDuplicateMalformedAndProductionInputBeforeTreasury() throws Exception {
        String valid = "{\"amountMicro\":\"100\",\"campaignRef\":\"preview-welcome\"}";
        for (String body : List.of(
                "{\"amountMicro\":\"100\",\"campaignRef\":\"preview-welcome\",\"scope\":\"Tenant-B\"}",
                "{\"amountMicro\":\"100\",\"amountMicro\":\"101\",\"campaignRef\":\"preview-welcome\"}",
                "{\"amountMicro\":100,\"campaignRef\":\"preview-welcome\"}",
                "{\"amountMicro\":\"01\",\"campaignRef\":\"preview-welcome\"} trailing")) {
            mvc.perform(post("/economy/preview/issuances").principal(auth(TENANT, CLIENT, USER))
                            .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        mvc.perform(post("/economy/preview/issuances").principal(auth(TENANT, CLIENT, USER))
                        .header("Idempotency-Key", KEY, KEY).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/economy/preview/issuances?x=1").principal(auth(TENANT, CLIENT, USER))
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest());

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("dev", "prod");
        mvc = mvc(gate(true), prod);
        mvc.perform(post("/economy/preview/issuances").principal(auth(TENANT, CLIENT, USER))
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ECONOMY_PREVIEW_DISABLED"));
        verify(treasury, never()).issue(any());
    }

    @Test
    void issuanceUsesTreasuryAndReturnsFrozenReceiptWithoutWalletState() throws Exception {
        when(treasury.issue(any())).thenReturn(new EconomyPostingResult("etx_1", "POSTED", "SILVER", 99L,
                100L, 100L, List.of(), null));
        mvc.perform(post("/economy/preview/issuances").principal(auth(TENANT, CLIENT, USER))
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMicro\":\"100\",\"campaignRef\":\"preview-welcome\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("etx_1"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.amountMicro").value("100"))
                .andExpect(jsonPath("$.availableMicro").doesNotExist());
        ArgumentCaptor<EconomyPostingCommand> command = ArgumentCaptor.forClass(EconomyPostingCommand.class);
        verify(treasury).issue(command.capture());
        org.junit.jupiter.api.Assertions.assertEquals(TENANT, command.getValue().scope().tenantId());
        org.junit.jupiter.api.Assertions.assertEquals(CLIENT, command.getValue().scope().clientId());
        org.junit.jupiter.api.Assertions.assertEquals(USER, command.getValue().principal().id());
        org.junit.jupiter.api.Assertions.assertEquals("preview-welcome", command.getValue().businessId());
    }

    @Test
    void ledgerIsUserAvailableOnlyBoundedAndCursorStable() throws Exception {
        when(mapper.selectUserAvailableLedger(eq(TENANT), eq(CLIENT), eq(USER), eq(null), eq(null), eq(51)))
                .thenReturn(List.of(row(9L, "etx_9", "een_9", 100L, 200L),
                        row(8L, "etx_8", "een_8", -40L, 199L)));
        mvc.perform(get("/economy/ledger?limit=50").principal(auth(TENANT, CLIENT, USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.items[0].amountMicro").value("100"))
                .andExpect(jsonPath("$.items[0].postedAt").value("200"))
                .andExpect(jsonPath("$.items[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[1].amountMicro").value("40"))
                .andExpect(jsonPath("$.nextCursor", nullValue()));
        mvc.perform(get("/economy/ledger?limit=101").principal(auth(TENANT, CLIENT, USER)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/economy/ledger?limit=1&limit=2").principal(auth(TENANT, CLIENT, USER)))
                .andExpect(status().isBadRequest());
    }

    private MockMvc mvc(EconomyPreviewGate gate) {
        return mvc(gate, environment);
    }

    private MockMvc mvc(EconomyPreviewGate gate, MockEnvironment activeEnvironment) {
        return MockMvcBuilders.standaloneSetup(new EconomyWalletController(gate,
                new EconomyWalletService(mapper, treasury, activeEnvironment, gate))).build();
    }

    private static EconomyPreviewGate gate(boolean enabled) {
        return new EconomyPreviewGate(new EconomyPreviewProperties(enabled, true,
                List.of(new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT))));
    }

    private static EconomyWalletLedgerRow row(long id, String transactionId, String entryId,
                                               long signed, long postedAt) {
        return new EconomyWalletLedgerRow().setRowId(id).setTransactionId(transactionId).setEntryId(entryId)
                .setBusinessType("ISSUE_SILVER").setBusinessRef("preview-welcome")
                .setSignedAmountMicro(signed).setStatus("POSTED").setPostedAt(postedAt);
    }

    private static JwtAuthenticationToken auth(String tenant, String client, String subject) {
        Jwt token = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", tenant).claim("client_id", client).subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
