package cn.jia.agent.skill;

import cn.jia.agent.service.*;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InstalledSkillDescriptorServiceTest {
    private final SkillCapabilityLookup.Scope scope=new SkillCapabilityLookup.Scope("0","client","owner","agent");
    private final AgentIdentityService identities=mock(AgentIdentityService.class);
    private final EconomySkillMarketplaceMapper market=mock(EconomySkillMarketplaceMapper.class);
    private final EconomySkillApplicationMapper app=mock(EconomySkillApplicationMapper.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<SkillPackages> packages=mock(ObjectProvider.class);
    private final InstalledSkillDescriptorService service=new InstalledSkillDescriptorService(identities,market,app,packages);
    private SkillEntitlementEntity e;
    private SkillInstallationEntity i;
    private SkillOrderEntity o;
    private SkillProductVersionEntity v;
    @BeforeEach void setup() {
        when(identities.requireCanonicalAgentIdInScope("0","client","owner","agent")).thenReturn("agent");
        byte[] hash=new byte[32]; String permissions="[]";
        e=new SkillEntitlementEntity().setTenantId("0").setClientId("client").setTargetAgentId("agent")
                .setSkillKey("text-file").setSkillVersion("1.0.0").setEntitlementId("ent").setInstallationId("ins")
                .setOrderId("ord").setProductVersionId("pv").setStatus("ACTIVE").setPermissionGrantVersion(1L)
                .setApprovedPermissionsManifest(permissions).setApprovedPermissionsSha256(hash).setVersion(2L).setActivatedAt(1L);
        i=new SkillInstallationEntity().setTenantId("0").setClientId("client").setTargetAgentId("agent")
                .setInstallationId("ins").setOrderId("ord").setProductVersionId("pv").setStatus("SUCCEEDED")
                .setSkillKey("text-file").setSkillVersion("1.0.0").setPackageSha256(hash).setPackageSize(100L).setInstalledAt(1L);
        o=new SkillOrderEntity().setTenantId("0").setClientId("client").setTargetAgentId("agent").setOrderId("ord")
                .setProductVersionId("pv").setStatus("ACTIVE").setPermissionGrantVersion(1L)
                .setApprovedPermissionsManifest(permissions).setApprovedPermissionsSha256(hash);
        v=new SkillProductVersionEntity().setTenantId("0").setClientId("client").setProductVersionId("pv")
                .setReviewStatus("APPROVED").setSkillKey("text-file").setSkillVersion("1.0.0")
                .setPackageSha256(hash).setPackageSize(100L).setApprovedPermissionsManifest(permissions)
                .setApprovedPermissionsSha256(hash);
        when(market.selectEntitlementsByAgent("0","client","agent")).thenReturn(List.of(e));
        when(app.installation("0","client","ins")).thenReturn(i); when(app.order("0","client","ord")).thenReturn(o);
        when(market.selectApprovedProductVersion("0","client","pv")).thenReturn(v);
        when(app.successfulReceipts("0","client","ins")).thenReturn(1L);
        var binding=new SkillDeliveryBindingEntity();binding.setCanonicalAgentId("agent");
        binding.setRuntimeInstallationId("runtime-install");binding.setRuntimeHostId("host");
        when(app.deliveryBinding("0","client","ins")).thenReturn(binding);
    }
    private InstalledSkillDescriptorLookup.Descriptor read() { return service.read(scope).skills().getFirst(); }
    @Test void pendingIsUnavailableAndDoesNotReadPackagesOrFinancialServices() {
        e.setStatus("PENDING_INSTALLATION");assertEquals("SKILL_NOT_INSTALLED",read().reasonCode());
        verifyNoInteractions(packages,app);
    }
    @Test void activeWithoutSuccessfulReceiptCannotBecomeExecutable() {
        when(app.successfulReceipts("0","client","ins")).thenReturn(0L);
        assertEquals("SKILL_INSTALLATION_RECEIPT_MISSING",read().reasonCode()); verifyNoInteractions(packages);
    }
    @Test void approvalPermissionAndInstallationMustAllMatch() {
        o.setPermissionGrantVersion(2L);assertEquals("SKILL_INSTALLATION_EVIDENCE_INVALID",read().reasonCode());
        o.setPermissionGrantVersion(1L);i.setPackageSize(101L);assertFalse(read().verified());
        i.setPackageSize(100L);v.setSkillKey("other");assertFalse(read().verified());verifyNoInteractions(packages);
    }
    @Test void activationMustReferToSameSuccessfulInstallationEpoch() {
        i.setInstalledAt(2L);assertEquals("SKILL_INSTALLATION_EVIDENCE_INVALID",read().reasonCode());
        i.setInstalledAt(1L);e.setActivatedAt(null);assertFalse(read().verified());verifyNoInteractions(packages);
    }
    @Test void multipleVerifiedDescriptorsUseOnlyOneFinalEntitlementRead() {
        var store=mock(SkillPackages.class);when(packages.getIfUnique()).thenReturn(store);
        var node=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();node.put("runner","native-model");
        when(store.manifest(any())).thenReturn(new SkillManifestCodec.Manifest(node,"a".repeat(64)));
        var second=new SkillEntitlementEntity().setTenantId("0").setClientId("client").setTargetAgentId("agent")
                .setSkillKey("text-file").setSkillVersion("1.0.0").setEntitlementId("ent-2").setInstallationId("ins")
                .setOrderId("ord").setProductVersionId("pv").setStatus("ACTIVE").setPermissionGrantVersion(1L)
                .setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(new byte[32]).setVersion(2L).setActivatedAt(1L);
        when(market.selectEntitlementsByAgent("0","client","agent")).thenReturn(List.of(e,second));
        assertTrue(service.read(scope).skills().stream().allMatch(InstalledSkillDescriptorLookup.Descriptor::verified));
        verify(market,times(2)).selectEntitlementsByAgent("0","client","agent");
    }
    @Test void caseVariantScopeRowsCannotMatch() {
        e.setClientId("Client");assertThrows(InstalledSkillDescriptorLookup.SourceUnavailable.class,()->read());
        verifyNoInteractions(app,packages);
    }
    @Test void anotherRuntimeHostOrAgentBindingCannotCreateDescriptor() {
        var binding=new SkillDeliveryBindingEntity();binding.setCanonicalAgentId("foreign");
        binding.setRuntimeInstallationId("runtime-install");binding.setRuntimeHostId("host");
        when(app.deliveryBinding(anyString(),anyString(),anyString())).thenReturn(binding);
        assertEquals("SKILL_INSTALLATION_BINDING_INVALID",read().reasonCode());verifyNoInteractions(packages);
    }
    @Test void ordinaryPackageIsNotExecutable() {
        var store=mock(SkillPackages.class);when(packages.getIfUnique()).thenReturn(store);
        assertEquals("SKILL_NOT_EXECUTABLE",read().reasonCode());
    }
    @Test void approvalSourceFailureIsNotEmptyCatalogSuccess() {
        when(market.selectEntitlementsByAgent(anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("private source"));
        var failure=assertThrows(InstalledSkillDescriptorLookup.SourceUnavailable.class,()->service.read(scope));
        assertEquals("SKILL_DESCRIPTOR_SOURCE_UNAVAILABLE",failure.getMessage());
    }
    @Test void revokedDuringPreparationDoesNotReusePreviouslyActiveSnapshot() {
        var store=mock(SkillPackages.class);when(packages.getIfUnique()).thenReturn(store);
        var node=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();node.put("runner","native-model");
        when(store.manifest(any())).thenReturn(new SkillManifestCodec.Manifest(node,"a".repeat(64)));
        when(market.selectEntitlementsByAgent("0","client","agent")).thenReturn(List.of(e),List.of());
        assertEquals("SKILL_INSTALLATION_CHANGED",read().reasonCode());
    }
    @Test void canonicalOwnershipIsCheckedOnEveryRead() {
        when(identities.requireCanonicalAgentIdInScope(anyString(),anyString(),anyString(),anyString())).thenReturn("foreign");
        assertThrows(InstalledSkillDescriptorLookup.SourceUnavailable.class,()->service.read(scope));verifyNoInteractions(market,app);
    }
    @Test void revokedIdentityIsClassifiedWithoutSwallowingDependencyFailures() {
        var denied=new cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException(
                cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN,"not in scope");
        when(identities.requireCanonicalAgentIdInScope("0","client","owner","agent")).thenThrow(denied);
        var rejected=assertThrows(InstalledSkillDescriptorLookup.SourceUnavailable.class,()->service.read(scope));
        assertEquals(InstalledSkillDescriptorLookup.SourceUnavailable.Reason.FORBIDDEN,rejected.reason());
        assertSame(denied,rejected.getCause());verifyNoInteractions(market,app,packages);
    }
    @Test void databaseFailureIsNotMisclassifiedAsAuthorizationRejection() {
        var failure=new org.springframework.dao.DataAccessResourceFailureException("unavailable");
        when(identities.requireCanonicalAgentIdInScope("0","client","owner","agent")).thenThrow(failure);
        var unavailable=assertThrows(InstalledSkillDescriptorLookup.SourceUnavailable.class,()->service.read(scope));
        assertEquals(InstalledSkillDescriptorLookup.SourceUnavailable.Reason.UNAVAILABLE,unavailable.reason());
        assertSame(failure,unavailable.getCause());verifyNoInteractions(market,app,packages);
    }

}
