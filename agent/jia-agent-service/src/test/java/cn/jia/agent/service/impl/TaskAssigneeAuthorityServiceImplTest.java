package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;

class TaskAssigneeAuthorityServiceImplTest {
    final AgentExternalServicePolicyDao policies=mock(AgentExternalServicePolicyDao.class);
    final AgentTaskExecutionGrantDao grants=mock(AgentTaskExecutionGrantDao.class);
    final AgentIdentityService identities=mock(AgentIdentityService.class);
    final TaskAssigneeAuthorityServiceImpl service=new TaskAssigneeAuthorityServiceImpl(policies,identities,grants);
    AgentIdentityRegistryEntity identity;
    @BeforeEach void setup() {
        identity=new AgentIdentityRegistryEntity().setCanonicalAgentId("agent-b").setCanonicalType("OPAQUE")
                .setOwnerJiacn("B").setBindingId(2L).setLifecycleStatus("ACTIVE");
        identity.setTenantId("0");identity.setClientId("client");
        when(policies.identity("0","client","agent-b")).thenReturn(identity);
    }
    @Test void samePlatformOpenTargetUsesBAndOwnTargetNeedsNoPolicy() {
        when(policies.latestForUpdate("0","client","agent-b",2)).thenReturn(new AgentExternalServicePolicyEntity().setEnabled(true).setRevision(3L));
        var target=service.lockForAssignment("0","client","A","agent-b");
        assertEquals("B",target.ownerJiacn());assertEquals(2,target.bindingId());assertEquals(3L,target.externalPolicyRevision());
        verify(identities).lockActiveCanonicalAgentIdsInScope("0","client","B",List.of("agent-b"));
        clearInvocations(policies);
        assertNull(service.lockForAssignment("0","client","B","agent-b").externalPolicyRevision());
        verify(policies,never()).latestForUpdate(any(),any(),any(),anyLong());
    }
    @Test void closedMissingAndEachForeignPlatformComponentReject() {
        assertEquals(409,assertThrows(AgentTaskExecutionGrantException.class,()->service.lockForAssignment("0","client","A","agent-b")).status());
        identity.setClientId("foreign");
        assertEquals(404,assertThrows(AgentTaskExecutionGrantException.class,()->service.resolveForAssignment("0","client","A","agent-b")).status());
        identity.setClientId("client");identity.setTenantId("other");
        assertEquals(404,assertThrows(AgentTaskExecutionGrantException.class,()->service.resolveForAssignment("0","client","A","agent-b")).status());
    }
    @Test void existingGrantIgnoresClosedPolicyButNeverIgnoresExecutorBindingOrVersion() {
        var grant=new AgentTaskExecutionGrantEntity().setTargetAgentId("agent-b").setExecutorOwnerJiacn("B")
                .setExecutorBindingId(2L).setGrantVersion(1L).setExternalPolicyRevision(3L);
        when(grants.findByGrant("0","client","A","task","grant")).thenReturn(grant);
        assertEquals("B",service.requireGrantTarget("0","client","A","task","grant",1).ownerJiacn());
        verify(policies,never()).latestForUpdate(any(),any(),any(),anyLong());
        assertThrows(AgentTaskExecutionGrantException.class,()->service.requireGrantTarget("0","client","A","task","grant",2));
        grant.setExecutorBindingId(9L);
        assertThrows(AgentTaskExecutionGrantException.class,()->service.requireGrantTarget("0","client","A","task","grant",1));
        grant.setExecutorBindingId(2L).setExecutorOwnerJiacn("C");
        assertThrows(AgentTaskExecutionGrantException.class,()->service.requireGrantTarget("0","client","A","task","grant",1));
    }

    private AgentTaskExecutionGrantEntity taskGrant() {
        var g = new AgentTaskExecutionGrantEntity().setTargetAgentId("agent-b")
                .setExecutorOwnerJiacn("B").setExecutorBindingId(2L).setGrantId("grant")
                .setGrantVersion(1L).setState("ACTIVE").setTaskId("task").setOwnerJiacn("A");
        g.setTenantId("0"); g.setClientId("client");
        when(grants.findLatestByTaskAgent("0","client","A","task","agent-b")).thenReturn(g);
        when(grants.findActiveByTask("0","client","A","task")).thenReturn(g);
        when(grants.findByGrant("0","client","A","task","grant")).thenReturn(g);
        return g;
    }
    @Test void liveTaskIdentityUsesFrozenProviderAndDoesNotRecheckSwitch() {
        taskGrant();
        assertEquals("agent-b", service.requireTaskAgent("0","client","A","task","agent-b",true));
        verify(identities).requireActiveIdentityForBinding("0","client","B",2L,"agent-b");
        verify(policies,never()).latestForUpdate(any(),any(),any(),anyLong());
        verify(identities,never()).requireCanonicalAgentIdInScope(any(),any(),eq("A"),any());
    }
    @Test void revokedGrantIsHistoricalOnly() {
        taskGrant().setState("REVOKED");
        when(identities.requirePersistedCanonicalAgentIdInScope("0","client","B","agent-b")).thenReturn("agent-b");
        assertEquals("agent-b",service.requireTaskAgent("0","client","A","task","agent-b",false));
        assertThrows(AgentTaskExecutionGrantException.class,()->service.requireTaskAgent("0","client","A","task","agent-b",true));
    }
    @Test void missingFrozenFieldsNeverFallBackToTaskOwner() {
        taskGrant().setExecutorBindingId(null);
        for(boolean active:List.of(true,false)) assertThrows(AgentTaskExecutionGrantException.class,
                ()->service.requireTaskAgent("0","client","A","task","agent-b",active));
        verifyNoInteractions(identities);
    }
    @Test void staleGrantReboundIdentityAndWrongScopeCannotAcquireLeaseAuthority() {
        var g=taskGrant();
        when(grants.findActiveByTask("0","client","A","task")).thenReturn(null);
        assertThrows(AgentTaskExecutionGrantException.class,()->service.requireTaskAgent("0","client","A","task","agent-b",true));
        when(grants.findActiveByTask("0","client","A","task")).thenReturn(g);
        identity.setBindingId(3L);
        assertThrows(AgentTaskExecutionGrantException.class,()->service.requireTaskAgent("0","client","A","task","agent-b",true));
        identity.setBindingId(2L);g.setOwnerJiacn("C");
        assertThrows(AgentTaskExecutionGrantException.class,()->service.requireTaskAgent("0","client","A","task","agent-b",true));
    }
    @Test void noGrantRetainsOnlyTheOriginalOwnerLocalCanonicalPath() {
        when(identities.requireCanonicalAgentIdInScope("0","client","A","own")).thenReturn("own");
        when(identities.requireCanonicalAgentIdInScope("0","client","A","agent-b")).thenThrow(new IllegalArgumentException("not owner"));
        assertEquals("own",service.requireTaskAgent("0","client","A","task","own",true));
        assertThrows(IllegalArgumentException.class,()->service.requireTaskAgent("0","client","A","task","agent-b",true));
        verifyNoInteractions(policies);
    }
}
