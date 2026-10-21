package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;

class AgentExternalServicePolicyServiceImplTest {
    final AgentExternalServicePolicyDao policies=mock(AgentExternalServicePolicyDao.class);
    final AgentIdentityService identities=mock(AgentIdentityService.class);
    final AgentTaskMetaDao tasks=mock(AgentTaskMetaDao.class);
    final AgentTaskExecutionGrantDao grants=mock(AgentTaskExecutionGrantDao.class);
    final AgentRuntimeDao runtimes=mock(AgentRuntimeDao.class);
    final AgentExternalServicePolicyServiceImpl service=new AgentExternalServicePolicyServiceImpl(policies,identities,tasks,grants,runtimes);
    final AgentTaskExecutionGrantService.Scope owner=new AgentTaskExecutionGrantService.Scope("0","client","B");
    final Map<String,AgentExternalServicePolicyEntity> latest=new HashMap<>(),receipts=new HashMap<>();
    @BeforeEach void setup() {
        for(String agent:List.of("one","two")) {
            var i=new AgentIdentityRegistryEntity().setCanonicalAgentId(agent).setCanonicalType("OPAQUE").setOwnerJiacn("B").setBindingId(agent.equals("one")?1L:2L).setLifecycleStatus("ACTIVE");
            i.setTenantId("0");i.setClientId("client");when(policies.identity("0","client",agent)).thenReturn(i);
        }
        when(policies.latest(eq("0"),eq("client"),anyString(),anyLong())).thenAnswer(i->latest.get(i.getArgument(2)));
        when(policies.receipt(eq("0"),eq("client"),eq("B"),anyString())).thenAnswer(i->receipts.get(i.getArgument(3)));
        when(policies.latestForUpdate(eq("0"),eq("client"),anyString(),anyLong())).thenAnswer(i->latest.get(i.getArgument(2)));
        when(policies.receiptForUpdate(eq("0"),eq("client"),eq("B"),anyString())).thenAnswer(i->receipts.get(i.getArgument(3)));
        doAnswer(i->{AgentExternalServicePolicyEntity row=i.getArgument(0);latest.put(row.getAgentId(),row);receipts.put(row.getIdempotencyKey(),row);return null;}).when(policies).insert(any());
    }
    @Test void independentSwitchesDefaultOffAndOriginalReceiptSurvivesLaterWrites() {
        assertFalse(service.read(owner,"one").enabled());
        var first=service.write(owner,"one","key-1",true,0);
        assertTrue(first.enabled());assertFalse(service.read(owner,"two").enabled());
        service.write(owner,"one","key-2",false,1);
        assertFalse(service.read(owner,"one").enabled());
        assertEquals(first,service.write(owner,"one","key-1",true,0));
        assertEquals(first,service.receipt(owner,"one","key-1"));
        verify(policies,times(2)).insert(any());verifyNoInteractions(runtimes);
    }
    @Test void staleRevisionAndChangedReplayAreConflictsAndOtherOwnerCannotWrite() {
        service.write(owner,"one","key",true,0);
        assertEquals(409,assertThrows(AgentTaskExecutionGrantException.class,()->service.write(owner,"one","new-key",false,0)).status());
        assertEquals(409,assertThrows(AgentTaskExecutionGrantException.class,()->service.write(owner,"one","key",false,0)).status());
        assertEquals(404,assertThrows(AgentTaskExecutionGrantException.class,()->service.write(new AgentTaskExecutionGrantService.Scope("0","client","C"),"one","c-key",true,0)).status());
    }
    @Test void foreignTaskCannotEnumerateCandidatesAndResponseContainsNoRuntimeSecrets() {
        assertThrows(AgentTaskExecutionGrantException.class,()->service.candidates(owner,"foreign",null,50));
        verify(policies,never()).candidates(any(),any(),any(),any(),anyInt());
        assertEquals(Set.of("agentId","displayName","providerDisplayName","isMine","availability"),
                Arrays.stream(AgentTaskTargetSummaryDTO.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
    }
}
