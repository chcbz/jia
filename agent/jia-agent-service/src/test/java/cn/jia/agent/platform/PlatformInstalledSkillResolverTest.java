package cn.jia.agent.platform;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.agent.skill.InstalledSkillResolverService;
import cn.jia.agent.skill.InstalledSkillSourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static cn.jia.agent.platform.PlatformInstallationStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformInstalledSkillResolverTest {
    private static final Scope SCOPE=new Scope("0","client-a","owner-a");
    private static final String AGENT="agent-wuyong";
    private static final byte[] HASH=new byte[32];
    private static final long NOW=1_800_000_000_000L;
    private PlatformInstallationStore store;
    private PlatformSkillCatalog catalog;
    private AgentIdentityService identities;
    private cn.jia.agent.dao.AgentRuntimeDao runtimes;
    private AgentRuntimeAuthenticationService authentication;
    private PlatformInstalledSkillResolver adapter;
    private String packageSha;

    @BeforeEach void setUp() {
        store=mock(PlatformInstallationStore.class);catalog=new PlatformSkillCatalog();packageSha=catalog.sha256();
        identities=mock(AgentIdentityService.class);runtimes=mock(cn.jia.agent.dao.AgentRuntimeDao.class);
        authentication=mock(AgentRuntimeAuthenticationService.class);
        when(identities.requireActiveIdentityForBinding("0","client-a","owner-a",7,AGENT))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT));
        when(runtimes.findByAgentId(AGENT)).thenReturn(runtime(7));
        when(authentication.requireControlledTarget("0","client-a","owner-a",AGENT,7,"PLATFORM_SKILL_INSTALL/v1"))
                .thenReturn(new AgentRuntimeAuthenticationService.ControlledTarget("runtime-a","key-a",HASH));
        adapter=new PlatformInstalledSkillResolver(store,catalog,identities,runtimes,authentication,
                Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC));
    }

    @Test void missingAdaptersAndMarketOriginAreUnavailableAndTagsAreNeverConsulted() {
        @SuppressWarnings("unchecked") ObjectProvider<InstalledSkillSourceResolver> providers=mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.empty());
        var resolver=new InstalledSkillResolverService(providers);
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,resolver.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED)).state());
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,resolver.resolve(request(InstalledSkillResolver.Origin.MARKET)).state());
        verifyNoInteractions(store,identities,runtimes,authentication);
    }

    @Test void marketRequestNeverFallsThroughToPlatformAdapter() {
        @SuppressWarnings("unchecked") ObjectProvider<InstalledSkillSourceResolver> providers=mock(ObjectProvider.class);
        InstalledSkillSourceResolver platform=mock(InstalledSkillSourceResolver.class);
        when(platform.origin()).thenReturn(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED);
        when(providers.orderedStream()).thenReturn(Stream.of(platform));
        var result=new InstalledSkillResolverService(providers).resolve(request(InstalledSkillResolver.Origin.MARKET));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());
        verify(platform,never()).resolve(any());
    }

    @Test void oneThousandOneNewFailuresCannotHideOlderValidCurrentSuccess() {
        Installation oldSuccess=row("psi_old_success",7,"runtime-a",HASH,packageSha,"SUCCEEDED","b".repeat(64),null,2,NOW-1002);
        when(store.verifiedCandidate(any())).thenReturn(candidate(oldSuccess,"SUCCEEDED",NOW+1));
        var result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.VERIFIED,result.state());
        assertEquals("psi_old_success",result.proof().installationRef());
        verify(store,times(1)).verifiedCandidate(any());
        verify(store,never()).pendingCandidate(any(),anyLong());
        verify(store,never()).latestHistorical(any());
    }

    @Test void requestedIsPendingOnlyBeforeExpiryAndWithNoCommittedResult() {
        Installation pending=row("psi_pending",7,"runtime-a",HASH,packageSha,"REQUESTED",null,null,1,NOW-2);
        when(store.pendingCandidate(any(),eq(NOW))).thenReturn(candidate(pending,"SUCCEEDED",NOW+1));
        assertEquals(InstalledSkillResolver.State.PENDING,adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED)).state());
        when(store.pendingCandidate(any(),eq(NOW))).thenReturn(null);
        when(store.latestHistorical(any())).thenReturn(pending);
        assertEquals(InstalledSkillResolver.State.REVOKED,adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED)).state());
    }

    @Test void malformedSucceededRowsCannotBecomeVerified() {
        Installation malformed=row("psi_bad",7,"runtime-a",HASH,packageSha,"SUCCEEDED",null,null,4,NOW-1);
        when(store.verifiedCandidate(any())).thenReturn(candidate(malformed,"SUCCEEDED",NOW+1));
        var result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());
        Installation error=row("psi_error",7,"runtime-a",HASH,packageSha,"SUCCEEDED","b".repeat(64),"unexpected",4,NOW-1);
        when(store.verifiedCandidate(any())).thenReturn(candidate(error,"SUCCEEDED",NOW+1));
        result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());
    }

    @Test void unresolvedCurrentIdentityBindingOrRuntimeReturnsUnavailableBeforeStoreAccess() {
        when(identities.requireActiveIdentityForBinding("0","client-a","owner-a",7,AGENT)).thenReturn(null);
        var result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());
        verifyNoInteractions(store);

        clearInvocations(store,identities,runtimes,authentication);
        when(identities.requireActiveIdentityForBinding("0","client-a","owner-a",7,AGENT))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT));
        when(runtimes.findByAgentId(AGENT)).thenReturn(runtime(6));
        result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());
        verifyNoInteractions(store);
    }

    @Test void exactHistoryKeyIncludesCurrentBindingRuntimeAndRegistration() {
        Installation failed=row("psi_current_failed",7,"runtime-a",HASH,packageSha,"FAILED",null,"x",3,NOW);
        when(store.latestHistorical(any())).thenReturn(failed);
        var result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.REVOKED,result.state());
        ArgumentCaptor<ResolutionKey> key=ArgumentCaptor.forClass(ResolutionKey.class);
        verify(store).latestHistorical(key.capture());
        assertEquals(7,key.getValue().bindingId());assertEquals("runtime-a",key.getValue().runtimeInstanceId());
        assertArrayEquals(HASH,key.getValue().registrationHash());
    }

    @Test void oldBindingRuntimeOrRegistrationFactsNeverLeakAsCurrentRevokedProof() {
        Installation oldBinding=row("psi_binding6",6,"runtime-a",HASH,packageSha,"FAILED",null,"x",1,NOW);
        when(store.latestHistorical(any())).thenReturn(oldBinding);
        var result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());

        Installation oldRuntime=row("psi_old_runtime",7,"runtime-old",HASH,packageSha,"FAILED",null,"x",1,NOW);
        when(store.latestHistorical(any())).thenReturn(oldRuntime);
        result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());

        byte[] oldHash=HASH.clone();oldHash[0]=1;
        Installation oldRegistration=row("psi_old_hash",7,"runtime-a",oldHash,packageSha,"FAILED",null,"x",1,NOW);
        when(store.latestHistorical(any())).thenReturn(oldRegistration);
        result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());
    }

    @Test void catalogRevocationRequiresAnExactCurrentFactAndNeverLeaksOldBinding() {
        String revokedSha="f".repeat(64);
        Installation exact=row("psi_exact_revoked",7,"runtime-a",HASH,revokedSha,"SUCCEEDED","b".repeat(64),null,2,NOW);
        when(store.verifiedCandidate(any())).thenReturn(candidate(exact,"SUCCEEDED",NOW+1));
        var result=adapter.resolve(request(revokedSha));
        assertEquals(InstalledSkillResolver.State.REVOKED,result.state());
        assertEquals("psi_exact_revoked",result.proof().installationRef());

        reset(store);
        Installation oldBinding=row("psi_old_revoked",6,"runtime-a",HASH,revokedSha,"FAILED",null,"x",1,NOW);
        when(store.latestHistorical(any())).thenReturn(oldBinding);
        result=adapter.resolve(request(revokedSha));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());
    }

    @Test void foreignHistoricalOrCurrentRowsNeverExposeInstallationReferences() {
        Installation foreignHistory=new Installation("psi_foreign",new Scope("0","client-a","owner-b"),"owner-b","key",
                "a".repeat(64),AGENT,7,"runtime-a",HASH,PlatformSkillCatalog.SKILL_KEY,
                PlatformSkillCatalog.SKILL_VERSION,packageSha,"challenge","command","FAILED",null,"x",1,NOW);
        when(store.latestHistorical(any())).thenReturn(foreignHistory);
        var result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());

        when(store.verifiedCandidate(any())).thenReturn(candidate(foreignHistory,"SUCCEEDED",NOW+1));
        result=adapter.resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());
    }

    @Test void adapterExceptionIsSanitizedByUnifiedResolver() {
        @SuppressWarnings("unchecked") ObjectProvider<InstalledSkillSourceResolver> providers=mock(ObjectProvider.class);
        InstalledSkillSourceResolver broken=mock(InstalledSkillSourceResolver.class);
        when(broken.origin()).thenReturn(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED);
        when(broken.resolve(any())).thenThrow(new IllegalStateException("database detail"));
        when(providers.orderedStream()).thenReturn(Stream.of(broken));
        var result=new InstalledSkillResolverService(providers).resolve(request(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED));
        assertEquals(InstalledSkillResolver.State.UNAVAILABLE,result.state());assertNull(result.proof());
    }

    @Test void requestAndProofRejectNullIdentityReferencesAndLegacyScope() {
        assertThrows(IllegalArgumentException.class,()->new InstalledSkillResolver.Request("0","client-a","owner-a",null,7,
                InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,"skill","1",packageSha));
        assertThrows(IllegalArgumentException.class,()->new InstalledSkillResolver.Proof(null,1,"skill","1",packageSha));
        assertThrows(IllegalArgumentException.class,()->new InstalledSkillResolver.Request("legacy","client-a","owner-a",AGENT,7,
                InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,"skill","1",packageSha));
        assertThrows(IllegalArgumentException.class,()->new InstalledSkillResolver.Request("0","0","owner-a",AGENT,7,
                InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,"skill","1",packageSha));
        assertThrows(IllegalArgumentException.class,()->new InstalledSkillResolver.Request("0","client-a","0",AGENT,7,
                InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,"skill","1",packageSha));
    }

    private static AgentRuntimeEntity runtime(long binding) {
        AgentRuntimeEntity runtime=new AgentRuntimeEntity();
        runtime.setAgentId(AGENT);runtime.setTenantId("0");runtime.setClientId("client-a");
        runtime.setOwnerJiacn("owner-a");runtime.setBindingId(binding);
        return runtime;
    }
    private InstalledSkillResolver.Request request(InstalledSkillResolver.Origin origin) {
        return new InstalledSkillResolver.Request("0","client-a","owner-a",AGENT,7,origin,
                PlatformSkillCatalog.SKILL_KEY,PlatformSkillCatalog.SKILL_VERSION,packageSha);
    }
    private InstalledSkillResolver.Request request(String digest) {
        return new InstalledSkillResolver.Request("0","client-a","owner-a",AGENT,7,
                InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,PlatformSkillCatalog.SKILL_KEY,
                PlatformSkillCatalog.SKILL_VERSION,digest);
    }
    private Installation row(String id,long binding,String runtime,byte[] registration,String digest,String state,
            String result,String error,long revision,long created) {
        return new Installation(id,SCOPE,"owner-a","key","a".repeat(64),AGENT,binding,runtime,registration,
                PlatformSkillCatalog.SKILL_KEY,PlatformSkillCatalog.SKILL_VERSION,digest,"challenge","command",
                state,result,error,revision,created);
    }
    private ResolutionCandidate candidate(Installation row,String deliveryStatus,long expires) {
        return new ResolutionCandidate(row,PlatformSkillInstallationService.TYPE,deliveryStatus,expires);
    }
}
