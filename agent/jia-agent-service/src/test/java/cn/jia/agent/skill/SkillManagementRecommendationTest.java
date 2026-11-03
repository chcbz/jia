package cn.jia.agent.skill;

import cn.jia.agent.dao.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SkillManagementRecommendationTest {
    static final HostingRentHttp.Actor ACTOR=new HostingRentHttp.Actor("user","0","client","owner");
    static final String HASH="a".repeat(64);
    @SuppressWarnings("unchecked") static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);when(p.getObject()).thenReturn(value);return p;
    }
    static class Fixture {
        final EconomySkillManagementMapper db=mock(EconomySkillManagementMapper.class);
        final EconomySkillMarketplaceMapper market=mock(EconomySkillMarketplaceMapper.class);
        final SkillAgentVersions versions=mock(SkillAgentVersions.class);
        final SkillMarketplaceService marketplace=mock(SkillMarketplaceService.class);
        final AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
        final SkillCapabilityLookup catalog=mock(SkillCapabilityLookup.class);
        final SkillPackages packages=mock(SkillPackages.class);
        final SkillInstallCommandWriter writer=mock(SkillInstallCommandWriter.class);
        final AgentManagedSessionLookup sessions=mock(AgentManagedSessionLookup.class);
        final SkillManagementService service;
        Fixture() {
            var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(marketplace.available(ACTOR)).thenReturn(true);
            service=new SkillManagementService(db,market,mock(EconomySkillApplicationMapper.class),marketplace,versions,
                    mock(AgentRuntimeDao.class),mock(AgentRuntimeV1InstallationDao.class),auth,mock(AgentIdentityService.class),
                    provider(packages),provider(writer),provider(sessions),provider(catalog),tx);
        }
        void installed(String agent) {
            var state=new SkillManagedStateEntity().setActiveInstallationId("si_"+agent).setSkillKey("vision")
                    .setDesiredEnabled(true).setAdmissionEnabled(false).setSkillRevision(1L).setActivePolicyRevision(1L);
            when(db.states("0","client",agent)).thenReturn(List.of(state));
            var authorization=new SkillInstallationAuthorizationEntity().setInstallationId("si_"+agent)
                    .setRuntimeInstallationId("rt_"+agent).setRuntimeHostId("host")
                    .setPackageJson("{\"skillInstallationId\":\"si_"+agent+"\",\"productVersionId\":\"spv_vision\",\"skillVersion\":\"1.0.0\",\"packageSha256\":\""+HASH+"\",\"manifestSha256\":null,\"byteLength\":\"1\",\"kind\":\"INSTRUCTION\"}");
            when(db.activeAuthorizationsForAgent("0","client",agent)).thenReturn(List.of(authorization));
            var proof=new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope("0","client","owner",agent,"instance"),"rt_"+agent,"host",1L,HASH,1L,1L);
            when(auth.currentRegisteredProof("0","client",agent)).thenReturn(proof);
            when(catalog.lookup(eq(new SkillCapabilityLookup.Scope("0","client","owner",agent)),any()))
                    .thenReturn(view(agent,1L,SkillCapabilityLookup.Readiness.NOT_EXECUTABLE));
            when(db.products("0","client")).thenReturn(List.of(new SkillProductEntity().setProductId("product").setName("识图").setDescription("读取图片文字")));
            when(market.selectApprovedProductVersion("0","client","spv_vision")).thenReturn(new SkillProductVersionEntity().setProductId("product"));
        }
        SkillCapabilityLookup.View view(String agent,long at,SkillCapabilityLookup.Readiness state) {
            var fact=new SkillCapabilityLookup.RuntimeSkill("vision","1.0.0",HASH,null,null,state,null,null,null,
                    "si_"+agent,"spv_vision","INSTRUCTION","1",true,at);
            return new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",new AgentManagedSessionLookup.SessionFence("rt_"+agent,"host","instance",1L),new SkillCapabilityLookup.Catalog("1",List.of(fact)));
        }
    }
    @Test void emptySkillsAvoidCatalogProductsAndVersionLookupsButStillCheckOwnership() {
        var f=new Fixture();
        assertEquals(Map.of("agent",List.of()),f.service.recommendationSkills(ACTOR,List.of("agent")));
        verify(f.versions).readOwned(ACTOR,"agent");verify(f.db,never()).products(anyString(),anyString());
        verifyNoInteractions(f.auth,f.catalog,f.packages,f.writer,f.market,f.sessions);
    }
    @Test void oneBatchSharesProductsVersionsAndReadsOnlyOneCatalogPerAgent() {
        var f=new Fixture();f.installed("a");f.installed("b");
        var rows=f.service.recommendationSkills(ACTOR,List.of("a","b"));
        assertTrue(rows.get("a").getFirst().available());assertTrue(rows.get("b").getFirst().available());
        assertEquals("识图",rows.get("a").getFirst().title());
        verify(f.db).products("0","client");verify(f.market).selectApprovedProductVersion("0","client","spv_vision");
        verify(f.auth).currentRegisteredProof("0","client","a");verify(f.auth).currentRegisteredProof("0","client","b");
        verify(f.catalog,times(2)).lookup(any(),any());verify(f.db,never()).findAuthorization(anyString(),anyString(),anyString());
        verifyNoInteractions(f.packages,f.writer,f.sessions);
    }
    @Test void heartbeatTimestampDoesNotBlanketInvalidateAndNextObservationReloadsFacts() {
        var f=new Fixture();f.installed("a");
        when(f.catalog.lookup(any(),any())).thenReturn(f.view("a",1L,SkillCapabilityLookup.Readiness.NOT_EXECUTABLE),
                f.view("a",2L,SkillCapabilityLookup.Readiness.NOT_EXECUTABLE),f.view("a",3L,SkillCapabilityLookup.Readiness.UNKNOWN));
        var first=f.service.recommendationSkills(ACTOR,List.of("a"));
        assertTrue(first.get("a").getFirst().available());
        assertEquals(first,f.service.recommendationSkills(ACTOR,List.of("a")));
        assertFalse(f.service.recommendationSkills(ACTOR,List.of("a")).get("a").getFirst().available());
        verify(f.db,times(3)).products("0","client");verify(f.market,times(3)).selectApprovedProductVersion("0","client","spv_vision");
    }
    @Test void deniedOwnershipAndFencedCatalogNeverProducePositiveEvidence() {
        var f=new Fixture();f.installed("a");
        when(f.catalog.lookup(any(),any())).thenReturn(SkillCapabilityLookup.View.unavailable(SkillCapabilityLookup.State.FENCED));
        assertFalse(f.service.recommendationSkills(ACTOR,List.of("a")).get("a").getFirst().available());
        when(f.versions.readOwned(ACTOR,"other")).thenThrow(new IllegalArgumentException("not owned"));
        assertThrows(IllegalArgumentException.class,()->f.service.recommendationSkills(ACTOR,List.of("other")));
        verify(f.db,never()).states("0","client","other");
    }
    @Test void withdrawnProductsRemainExcludedInBatchProjection() {
        var f=new Fixture();f.installed("a");when(f.db.products("0","client")).thenReturn(List.of());
        assertTrue(f.service.recommendationSkills(ACTOR,List.of("a")).get("a").isEmpty());
    }
    @Test void enabledConfirmedInstructionIsCitableWithoutOpeningExecutableAdmission() {
        assertTrue(SkillManagementService.recommendationEvidenceAvailable("INSTRUCTION",true,false,"NOT_EXECUTABLE"));
        assertFalse(SkillManagementService.recommendationEvidenceAvailable("INSTRUCTION",false,false,"NOT_EXECUTABLE"));
    }
    @Test void instructionWithoutExactLiveCatalogIsNotPositiveEvidence() {
        for(String state:List.of("OFFLINE","UNKNOWN","VERIFYING","VERSION_MISMATCH","CHANGED","DEPENDENCY_MISSING","READY"))
            assertFalse(SkillManagementService.recommendationEvidenceAvailable("INSTRUCTION",true,false,state),state);
    }
    @Test void executableFactsStillRequireEnabledAdmissionAndReadyUnknownKindsAreNotGuessed() {
        assertTrue(SkillManagementService.recommendationEvidenceAvailable("EXECUTABLE",true,true,"READY"));
        assertFalse(SkillManagementService.recommendationEvidenceAvailable("EXECUTABLE",true,false,"READY"));
        assertFalse(SkillManagementService.recommendationEvidenceAvailable("EXECUTABLE",false,true,"READY"));
        assertFalse(SkillManagementService.recommendationEvidenceAvailable("EXECUTABLE",true,true,"DEPENDENCY_MISSING"));
        assertFalse(SkillManagementService.recommendationEvidenceAvailable("UNKNOWN_KIND",true,true,"READY"));
    }
}
