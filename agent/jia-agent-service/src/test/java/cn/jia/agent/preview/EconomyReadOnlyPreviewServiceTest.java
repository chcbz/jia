package cn.jia.agent.preview;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.EconomyReadOnlyPreviewProperties;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.agent.mapper.EconomyReadOnlyPreviewMapper;
import cn.jia.agent.mapper.EconomyReadOnlyPreviewRows;
import cn.jia.agent.service.funding.FundedBountyPreviewPriceBook;
import cn.jia.agent.service.funding.FundedBountyQuoteCalculator;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.entity.EconomyWalletLedgerRow;
import cn.jia.economy.entity.EconomyWalletSnapshotRow;
import cn.jia.economy.entity.skill.SkillEntitlementEntity;
import cn.jia.economy.entity.skill.SkillInstallationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cn.jia.agent.preview.EconomyReadOnlyPreviewDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;

class EconomyReadOnlyPreviewServiceTest {
    private static final Principal A = new Principal("0", "Client-A", "Tenant-A", "actor-A");
    private static final Principal B = new Principal("0", "Client-B", "Tenant-B", "actor-B");
    private static final Principal C = new Principal("0", "Client-A", "Tenant-C", "actor-C");
    private static final String AGENT = "agt_0123456789abcdef0123456789abcdef";
    private EconomyReadOnlyPreviewMapper mapper;
    private HostingRentOwnerResolver owners;
    private EconomyReadOnlyPreviewService service;

    @BeforeEach
    void setUp() {
        mapper = mock(EconomyReadOnlyPreviewMapper.class);
        owners = mock(HostingRentOwnerResolver.class);
        when(owners.requireOwner(any(HostingRentHttp.Actor.class)))
                .thenAnswer(invocation -> invocation.<HostingRentHttp.Actor>getArgument(0).ownerJiacn());
        service = service(true);
    }

    @Test
    void independentSwitchDoesNotConsultLegacyGatesAndDisabledCapabilitiesFailClosed() {
        EconomyReadOnlyPreviewService disabled = service(false);
        Capabilities capabilities = disabled.capabilities(A);
        assertFalse(capabilities.enabled());
        assertTrue(capabilities.features().values().stream().noneMatch(Boolean::booleanValue));
        assertTrue(capabilities.actions().values().stream().noneMatch(Boolean::booleanValue));
        assertThrows(EconomyReadOnlyPreviewException.class, () -> disabled.wallet(A));
        assertThrows(EconomyReadOnlyPreviewException.class, () -> disabled.estimate(A, input()));
        verifyNoInteractions(mapper);
    }

    @Test
    void walletAndLedgerKeepTenantClientActorExactAcrossABAndDoNotCreateAccounts() {
        when(mapper.selectWallet("0", "Client-A", "actor-A"))
                .thenReturn(new EconomyWalletSnapshotRow().setAvailableMicro(12L).setHeldMicro(3L)
                        .setMinimumHeldComponentMicro(3L).setVersion(9L));
        when(mapper.selectWallet("0", "Client-B", "actor-B"))
                .thenReturn(new EconomyWalletSnapshotRow().setAvailableMicro(0L).setHeldMicro(0L)
                        .setMinimumHeldComponentMicro(0L).setVersion(0L));
        assertEquals("12", service.wallet(A).availableMicro());
        assertEquals("0", service.wallet(B).availableMicro());

        when(mapper.selectLedger("0", "Client-A", "actor-A", null, null, 2))
                .thenReturn(List.of(row(3, 100, 9), row(2, -40, 8)));
        var page = service.ledger(A, null, 1);
        assertEquals(1, page.items().size());
        assertEquals("CREDIT", page.items().getFirst().direction());
        assertNotNull(page.nextCursor());
        verify(mapper).selectWallet("0", "Client-A", "actor-A");
        verify(mapper).selectWallet("0", "Client-B", "actor-B");
        verify(mapper).selectLedger("0", "Client-A", "actor-A", null, null, 2);
        verifyNoMoreInteractions(mapper);
    }


    @Test
    void sameClientCatalogIsSharedButWalletAndAgentProofRemainActorOwnerIsolated() {
        var product = new EconomyReadOnlyPreviewRows.ProductRow().setProductId("p1").setName("Skill")
                .setDescription("desc").setProductVersionId("pv1").setSkillKey("read")
                .setSkillVersion("1.0.0").setPriceMicro(1L)
                .setApprovedPermissionsManifest("[]").setDeploymentRestriction("NONE");
        when(mapper.selectProducts("0", "Client-A", 0, 2)).thenReturn(List.of(product));
        when(mapper.selectWallet("0", "Client-A", "actor-A"))
                .thenReturn(new EconomyWalletSnapshotRow().setAvailableMicro(11L).setHeldMicro(0L)
                        .setMinimumHeldComponentMicro(0L).setVersion(1L));
        when(mapper.selectWallet("0", "Client-A", "actor-C"))
                .thenReturn(new EconomyWalletSnapshotRow().setAvailableMicro(22L).setHeldMicro(0L)
                        .setMinimumHeldComponentMicro(0L).setVersion(2L));

        assertEquals(service.products(A, 0, 1), service.products(C, 0, 1));
        assertEquals("11", service.wallet(A).availableMicro());
        assertEquals("22", service.wallet(C).availableMicro());
        verify(mapper, times(2)).selectProducts("0", "Client-A", 0, 2);
        verify(mapper).selectWallet("0", "Client-A", "actor-A");
        verify(mapper).selectWallet("0", "Client-A", "actor-C");
    }

    @Test
    void agentReadsRequireExactOwnerAndActorProofBeforeOwnershipSql() {
        when(owners.requireOwner(any(HostingRentHttp.Actor.class)))
                .thenThrow(new HostingRentApplicationException(403, "HOSTING_RENT_OWNER_UNPROVEN"));
        EconomyReadOnlyPreviewException denied = assertThrows(EconomyReadOnlyPreviewException.class,
                () -> service.agentSkills(C, AGENT));
        assertEquals("PREVIEW_RESOURCE_NOT_FOUND", denied.code());
        verifyNoInteractions(mapper);

        reset(owners);
        when(owners.requireOwner(any(HostingRentHttp.Actor.class))).thenReturn("Tenant-A");
        when(mapper.selectOwnedAgent("0", "Client-A", "Tenant-A", AGENT)).thenReturn(List.of());
        assertThrows(EconomyReadOnlyPreviewException.class, () -> service.hostingLease(A, AGENT));
        ArgumentCaptor<HostingRentHttp.Actor> actor = ArgumentCaptor.forClass(HostingRentHttp.Actor.class);
        verify(owners).requireOwner(actor.capture());
        assertEquals("0", actor.getValue().tenantId());
        assertEquals("Client-A", actor.getValue().clientId());
        assertEquals("Tenant-A", actor.getValue().ownerJiacn());
        assertEquals("actor-A", actor.getValue().actorId());
    }

    @Test
    void catalogFiltersThroughReadMapperAndNeverExposesPackageOrPurchase() {
        var row = new EconomyReadOnlyPreviewRows.ProductRow().setProductId("p1").setName("Skill")
                .setDescription("desc").setProductVersionId("pv1").setSkillKey("read")
                .setSkillVersion("1.0.0").setPriceMicro(9007199254740993L)
                .setApprovedPermissionsManifest("[\"repo:read\"]").setDeploymentRestriction("NONE");
        when(mapper.selectProducts("0", "Client-A", 0, 2)).thenReturn(List.of(row));
        when(mapper.selectProduct("0", "Client-A", "p1")).thenReturn(List.of(row));
        Product product = service.products(A, 0, 1).items().getFirst();
        assertEquals("9007199254740993", product.priceMicro());
        assertEquals(List.of("repo:read"), product.permissions());
        assertEquals("CATALOG", product.priceSource());
        assertFalse(product.purchaseAllowed());
        assertEquals(product, service.product(A, "p1"));
    }

    @Test
    void entitlementNeverImpliesInstalledWithoutLinkedSucceededEvidence() {
        owner();
        var verified = entitlement("e1", "i1", "ACTIVE", "read", "1.0.0");
        var unconfirmed = entitlement("e2", "i2", "ACTIVE", "write", "2.0.0");
        var installation = installation("i1", "o-e1", "pv-e1", "read", "1.0.0", "SUCCEEDED", 55L);
        when(mapper.selectEntitlements("0", "Client-A", AGENT))
                .thenReturn(List.of(verified, unconfirmed));
        when(mapper.selectInstallations("0", "Client-A", AGENT)).thenReturn(List.of(installation));

        AgentSkills result = service.agentSkills(A, AGENT);
        assertEquals("VERIFIED_INSTALLED", result.installationEvidence().get(0).status());
        assertEquals("55", result.installationEvidence().get(0).verifiedAt());
        assertEquals("UNCONFIRMED", result.installationEvidence().get(1).status());
        assertEquals("NO_PERSISTED_INSTALLATION", result.installationEvidence().get(1).evidenceKind());
    }

    @Test
    void localHostingIsNotApplicableAndHostedLeaseIsReadOnlyProjection() {
        owner();
        when(mapper.selectHostedProfile("0", "Client-A", "Tenant-A", 7L, AGENT)).thenReturn(List.of());
        assertEquals("NOT_APPLICABLE", service.hostingLease(A, AGENT).applicability());
        verify(mapper, never()).selectLatestLease(anyString(), anyString(), anyString(), anyString());

        reset(mapper);
        owner();
        AgentHostedProfileEntity hostedProfile = new AgentHostedProfileEntity();
        hostedProfile.setBindingId(7L);
        hostedProfile.setCanonicalAgentId(AGENT);
        hostedProfile.setOwnerJiacn("Tenant-A");
        hostedProfile.setPersonaCode("wuyong");
        hostedProfile.setTenantId("0");
        hostedProfile.setClientId("Client-A");
        when(mapper.selectHostedProfile("0", "Client-A", "Tenant-A", 7L, AGENT))
                .thenReturn(List.of(hostedProfile));
        when(mapper.selectLatestLease("0", "Client-A", "actor-A", AGENT))
                .thenReturn(new EconomyHostingLeaseEntity().setLeaseId("lease-1").setAgentId(AGENT)
                        .setPrincipalType("USER").setPrincipalId("actor-A").setVersion(3L).setStatus("ACTIVE")
                        .setPlanVersion(1L).setAmountMicro(1000000000L).setPeriodSeconds(2592000L)
                        .setPaidFrom(100L).setPaidThrough(200L)
                        .setTenantId("0").setClientId("Client-A"));
        HostingLease hosted = service.hostingLease(A, AGENT);
        assertEquals("APPLICABLE", hosted.applicability());
        assertEquals("lease-1", hosted.lease().leaseId());
        assertEquals("1000000000", hosted.lease().amountMicro());
    }

    @Test
    void referencePlanDoesNotRequireOrEnablePaidHostingGate() {
        HostingPlan plan = service.hostingPlan(A);
        assertEquals("CONFIGURATION_REFERENCE", plan.source());
        assertEquals(AgentHostingRentProperties.PREVIEW_AMOUNT_MICRO, plan.amountMicro());
        assertFalse(plan.activationAllowed());
        verifyNoInteractions(mapper);
    }

    @Test
    void pureEstimateMatchesFrozenIntegerCalculatorAndSeparatesCachedTokens() {
        BountyEstimate result = service.estimate(A, input());
        var expected = FundedBountyQuoteCalculator.calculate(1_000_000_000L, 0L,
                FundedBountyPreviewPriceBook.ESTIMATED_TOKENS,
                FundedBountyPreviewPriceBook.WORST_TOKENS);
        assertEquals(Long.toString(expected.estimatedComputeMicro()), result.estimatedComputeMicro());
        assertEquals(FundedBountyPreviewPriceBook.RATE_PROVENANCE, result.rateProvenance());
        assertEquals("4000", result.estimatedTokens().cachedInput());
        assertFalse(result.charged());
        assertFalse(result.persisted());
        assertThrows(EconomyReadOnlyPreviewException.class, () -> service.estimate(A, new EstimateInput(1, 0,
                new ParsedTokens(2, 0, 0, 0), new ParsedTokens(1, 0, 0, 0))));
        assertThrows(EconomyReadOnlyPreviewException.class, () -> service.estimate(A, new EstimateInput(Long.MAX_VALUE,
                Long.MAX_VALUE, new ParsedTokens(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                new ParsedTokens(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))));
        verifyNoInteractions(mapper);
    }

    @Test
    void foreignOrAmbiguousAgentIsUniformNotFoundOrUnavailableBeforeSkillAndLeaseReads() {
        when(mapper.selectOwnedAgent("0", "Client-A", "Tenant-A", AGENT)).thenReturn(List.of());
        EconomyReadOnlyPreviewException missing = assertThrows(EconomyReadOnlyPreviewException.class,
                () -> service.agentSkills(A, AGENT));
        assertEquals("PREVIEW_RESOURCE_NOT_FOUND", missing.code());
        verify(mapper, never()).selectEntitlements(anyString(), anyString(), anyString());

        when(mapper.selectOwnedAgent("0", "Client-B", "Tenant-B", AGENT)).thenReturn(List.of(
                new EconomyReadOnlyPreviewRows.AgentOwnershipRow().setBindingId(1L).setTenantId("0")
                        .setClientId("Client-B").setOwnerJiacn("Tenant-B").setAgentId(AGENT).setPersonaCode("x"),
                new EconomyReadOnlyPreviewRows.AgentOwnershipRow().setBindingId(2L).setTenantId("0")
                        .setClientId("Client-B").setOwnerJiacn("Tenant-B").setAgentId(AGENT).setPersonaCode("y")));
        EconomyReadOnlyPreviewException ambiguous = assertThrows(EconomyReadOnlyPreviewException.class,
                () -> service.hostingLease(B, AGENT));
        assertEquals("PREVIEW_DATA_UNAVAILABLE", ambiguous.code());
    }

    @Test
    void serviceRejectsInvalidScopeAndPaginationBeforeMapperAccess() {
        assertEquals("PREVIEW_SCOPE_UNAVAILABLE", assertThrows(EconomyReadOnlyPreviewException.class,
                () -> service.wallet(new Principal("Tenant-A", "Client-A", "Tenant-A", "actor-A"))).code());
        assertEquals("PREVIEW_BAD_REQUEST", assertThrows(EconomyReadOnlyPreviewException.class,
                () -> service.ledger(A, null, 0)).code());
        assertEquals("PREVIEW_BAD_REQUEST", assertThrows(EconomyReadOnlyPreviewException.class,
                () -> service.products(A, Integer.MAX_VALUE, 1)).code());
        verifyNoInteractions(mapper);
    }

    private EconomyReadOnlyPreviewService service(boolean enabled) {
        return new EconomyReadOnlyPreviewService(mapper, new EconomyReadOnlyPreviewProperties(enabled),
                new AgentHostingRentProperties(false, null, null, null), owners);
    }

    private void owner() {
        when(mapper.selectOwnedAgent("0", "Client-A", "Tenant-A", AGENT)).thenReturn(List.of(
                new EconomyReadOnlyPreviewRows.AgentOwnershipRow().setBindingId(7L).setTenantId("0")
                        .setClientId("Client-A").setOwnerJiacn("Tenant-A")
                        .setAgentId(AGENT).setPersonaCode("wuyong")));
    }

    private static EconomyWalletLedgerRow row(long id, long signed, long posted) {
        return new EconomyWalletLedgerRow().setRowId(id).setTransactionId("tx-" + id).setEntryId("en-" + id)
                .setBusinessType("ISSUE_SILVER").setBusinessRef("fixture").setSignedAmountMicro(signed)
                .setStatus("POSTED").setPostedAt(posted);
    }

    private static SkillEntitlementEntity entitlement(String id, String installation, String status,
            String key, String version) {
        return new SkillEntitlementEntity().setEntitlementId(id).setOrderId("o-" + id)
                .setInstallationId(installation).setProductVersionId("pv-" + id).setTargetAgentId(AGENT)
                .setSkillKey(key).setSkillVersion(version).setStatus(status).setPermissionGrantVersion(1L)
                .setTenantId("0").setClientId("Client-A");
    }

    private static SkillInstallationEntity installation(String id, String order, String product,
            String key, String version, String status, Long installedAt) {
        return new SkillInstallationEntity().setInstallationId(id).setOrderId(order).setProductVersionId(product)
                .setTargetAgentId(AGENT).setSkillKey(key).setSkillVersion(version).setStatus(status)
                .setInstalledAt(installedAt).setTenantId("0").setClientId("Client-A");
    }

    private static EstimateInput input() {
        return new EstimateInput(1_000_000_000L, 0L,
                new ParsedTokens(18_000, 4_000, 6_000, 3_000),
                new ParsedTokens(36_000, 8_000, 12_000, 6_000));
    }
}
