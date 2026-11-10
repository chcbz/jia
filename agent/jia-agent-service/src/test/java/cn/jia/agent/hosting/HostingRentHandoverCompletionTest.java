package cn.jia.agent.hosting;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.service.*;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.entity.*;
import cn.jia.economy.hosting.HostingRentRenewalService;
import cn.jia.oauth.service.ApiKeyService;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction proxy and H2 effects; relationship DAOs and native service are doubles. */
class HostingRentHandoverCompletionTest {
    final AgentRuntimeV1InstallationDao installations=mock(AgentRuntimeV1InstallationDao.class);
    final AgentPersonaBindingDao bindings=mock(AgentPersonaBindingDao.class);
    final HostingRentRenewalService renewal=mock(HostingRentRenewalService.class);
    final AgentIdentityService identities=mock(AgentIdentityService.class);
    final AgentRuntimeDao runtimes=mock(AgentRuntimeDao.class);
    final AgentRuntimeV1Service runtime=mock(AgentRuntimeV1Service.class);
    final HostingRentFinishingMapper tasks=mock(HostingRentFinishingMapper.class);
    final ApiKeyService keys=mock(ApiKeyService.class);
    final ManagedHostingProvisioner.Preparation p=new ManagedHostingProvisioner.Preparation("tenant","client","old-owner","agent","intent","lease","7",1000);
    final ManagedHostingProvisioner.Candidate candidate=new ManagedHostingProvisioner.Candidate("installation","manifest",null,0,1,"host");
    final ManagedHostingProvisioner.CurrentOperation cleaned=new ManagedHostingProvisioner.CurrentOperation(p,candidate);
    EconomyHostingLeaseEntity lease;
    EconomyHostingProvisioningIntentEntity intent;
    AgentPersonaBindingEntity binding;
    AgentRuntimeEntity projection;
    JdbcTemplate jdbc;
    HostingRentHandoverCompletion service;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:handover_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source); jdbc.execute("CREATE TABLE effects(name VARCHAR(100))");
        ObjectProvider<ApiKeyService> provider=mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(keys);
        var target=new HostingRentHandoverCompletion(installations,bindings,renewal,identities,runtimes,runtime,tasks,provider,
                new FixedTenantAdmissionPolicy("tenant","client"));
        var proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        service=(HostingRentHandoverCompletion)proxy.getProxy();
        var installation=new AgentRuntimeV1InstallationEntity().setCanonicalAgentId("agent").setManifestSha256("manifest");
        installation.setTenantId("tenant"); installation.setClientId("client");
        when(installations.lock("installation")).thenReturn(installation);
        binding=new AgentPersonaBindingEntity().setId(7L).setAgentId("agent").setJiacn("old-owner").setPersonaCode("hero").setStatus(1);
        binding.setTenantId("tenant");binding.setClientId("client");when(bindings.findByIdForUpdate(7L)).thenReturn(binding);
        intent=new EconomyHostingProvisioningIntentEntity().setQuotePurpose("INITIAL").setPrincipalType("USER").setPrincipalId("old-payer")
                .setStatus("ACTIVE").setAgentId("agent").setLeaseId("lease").setReservedAt(1000L)
                .setRuntimeInstallationId("installation").setRuntimeManifestSha256("manifest");
        lease=new EconomyHostingLeaseEntity().setStatus("FINISHING").setPrincipalType("USER").setPrincipalId("old-payer")
                .setBindingId("7").setAgentId("agent").setPersonaCode("hero");
        when(renewal.lockHandover(any(),eq("intent"),eq("lease"))).thenReturn(new HostingRentRenewalService.HandoverSnapshot(intent,lease));
        when(tasks.unfinishedPage("tenant","client","old-owner","agent",0)).thenReturn(List.of());
        projection=new AgentRuntimeEntity().setId(9L).setBindingId(7L).setOwnerJiacn("old-owner")
                .setRuntimeInstallationId("installation").setRuntimeHostId("host"); projection.setClientId("client");
        when(runtimes.findByAgentIdForUpdate("agent")).thenReturn(projection);
        doAnswer(i->{effect("revoke");return null;}).when(runtime).revoke(eq("tenant"),eq("client"),eq("old-owner"),eq("installation"),anyLong());
        when(renewal.completeHandover(any(),any(),eq("lease"),eq("7"),eq("agent"))).thenAnswer(i->{effect("lease");return null;});
        when(bindings.updateById(binding)).thenAnswer(i->{effect("binding");return 1;});
        doAnswer(i->{effect("identity");return null;}).when(identities).suspendForBinding("tenant","client","old-owner",7L);
        when(runtimes.clearBindingAfterUnbind(eq(9L),eq("agent"),eq(7L),eq("client"),eq("old-owner"),anyLong()))
                .thenAnswer(i->{effect("projection");return 1;});
    }
    void effect(String name) {assertTrue(TransactionSynchronizationManager.isActualTransactionActive());jdbc.update("INSERT INTO effects VALUES(?)",name);}
    int effects() {return jdbc.queryForObject("SELECT COUNT(*) FROM effects",Integer.class);}
    @AfterEach void close() {jdbc.execute("DROP ALL OBJECTS");}
    @Test void managedStopUsesOriginalTransactionAndDoesNotRevokeOrReleaseAnything() {
        doAnswer(i->{effect("stopped");return null;}).when(renewal).recordExecutorStopped(any(),any(),eq("lease"),eq("7"),eq("agent"),eq("lease"));
        assertTrue(service.prepareManagedStop(cleaned));assertEquals(0,effects());
        service.recordManagedStop(cleaned);assertEquals(1,effects());assertEquals(1,binding.getStatus());
        verify(renewal).recordExecutorStopped(any(),argThat(a->"old-payer".equals(a.id())),eq("lease"),eq("7"),eq("agent"),eq("lease"));
        verify(renewal,never()).completeHandover(any(),any(),anyString(),anyString(),anyString());
        verifyNoInteractions(runtime,identities,keys);
    }
    @Test void managedStopRechecksRelationshipAndPendingWorkBeforeRecordingFact() {
        assertTrue(service.prepareManagedStop(cleaned));projection.setRuntimeHostId("other-host");
        assertThrows(IllegalStateException.class,()->service.recordManagedStop(cleaned));
        projection.setRuntimeHostId("host");
        when(tasks.unfinishedPage("tenant","client","old-owner","agent",0)).thenReturn(List.of(new AgentTaskMetaEntity()));
        assertThrows(IllegalStateException.class,()->service.prepareManagedStop(cleaned));
        verify(renewal,never()).recordExecutorStopped(any(),any(),anyString(),anyString(),anyString(),anyString());
        assertEquals(0,effects());
    }
    @Test void stopFactFailureRollsBackWithoutRevokingOriginalEvidenceIdentity() {
        doAnswer(i->{effect("stopped");throw new IllegalStateException("stop fact failed");}).when(renewal)
                .recordExecutorStopped(any(),any(),anyString(),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->service.recordManagedStop(cleaned));assertEquals(0,effects());
        verifyNoInteractions(runtime,identities,keys);
    }
    @Test void closesOriginalRelationshipInOneTransactionUsingInstallationFirstOrder() {
        service.completeManaged(cleaned);assertEquals(5,effects());assertEquals(0,binding.getStatus());
        var order=inOrder(installations,bindings,runtime,renewal,identities,runtimes);
        order.verify(installations).lock("installation");order.verify(bindings).findByIdForUpdate(7L);
        order.verify(renewal).lockHandover(any(),eq("intent"),eq("lease"));
        order.verify(runtimes).findByAgentIdForUpdate("agent");
        order.verify(runtime).revoke(eq("tenant"),eq("client"),eq("old-owner"),eq("installation"),anyLong());
        order.verify(renewal).completeHandover(any(),argThat(a->"old-payer".equals(a.id())),eq("lease"),eq("7"),eq("agent"));
        order.verify(bindings).updateById(binding);order.verify(identities).suspendForBinding("tenant","client","old-owner",7L);
        verifyNoInteractions(keys);
    }
    @Test void lastProjectionFailureRollsBackRevocationLeaseAndBindingEffects() {
        when(runtimes.clearBindingAfterUnbind(anyLong(),anyString(),anyLong(),anyString(),anyString(),anyLong())).thenReturn(0);
        assertThrows(IllegalStateException.class,()->service.completeManaged(cleaned));assertEquals(0,effects());
    }
    @Test void closedOriginalReplayDoesNotRevokeOrTouchLaterInvitationProjection() {
        lease.setStatus("CLOSED");binding.setStatus(0);projection.setOwnerJiacn("next-owner").setBindingId(8L);
        service.completeManaged(cleaned);assertEquals(0,effects());
        verifyNoInteractions(runtime,identities,runtimes,tasks,keys);
        verify(renewal,never()).completeHandover(any(),any(),anyString(),anyString(),anyString());
    }
    @Test void unfinishedWorkOrChangedHostNeverReleasesFinancialOrIdentityState() {
        when(tasks.unfinishedPage("tenant","client","old-owner","agent",0)).thenReturn(List.of(new AgentTaskMetaEntity()));
        assertThrows(IllegalStateException.class,()->service.completeManaged(cleaned));
        when(tasks.unfinishedPage("tenant","client","old-owner","agent",0)).thenReturn(List.of());projection.setRuntimeHostId("other-host");
        assertThrows(IllegalStateException.class,()->service.completeManaged(cleaned));
        verifyNoInteractions(runtime,identities,keys);assertEquals(0,effects());
        verify(renewal,never()).completeHandover(any(),any(),anyString(),anyString(),anyString());
    }
    @Test void changedHistoricalOwnerCannotReplayCompletion() {
        lease.setStatus("CLOSED");binding.setJiacn("next-owner");
        assertThrows(IllegalStateException.class,()->service.completeManaged(cleaned));assertEquals(0,effects());
    }
    @Test void existingDedicatedKeyIsRevokedExactlyAndFailureRollsBack() {
        intent.setManagedApiKeyId("old-key");
        when(keys.disableManagedKey("old-key","tenant","client","old-owner","hosting:intent")).thenReturn(false);
        assertThrows(IllegalStateException.class,()->service.completeManaged(cleaned));assertEquals(0,effects());
        verifyNoInteractions(identities);
        verify(renewal,never()).completeHandover(any(),any(),anyString(),anyString(),anyString());
    }
}
