package cn.jia.agent.service;

import cn.jia.agent.common.AgentHostedProfileState;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentHostedBindingTransaction.Prepared;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentPersonaProvisioningServiceTest {
    private static final Scope SCOPE = new Scope("owner-a", "client-a", "owner-a");

    @Test
    void localBindUsesExplicitJwtScopeWithoutHostedSideEffects() {
        AgentService agentService = mock(AgentService.class);
        AgentHostedBindingTransaction transactions = mock(AgentHostedBindingTransaction.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId("agt_0123456789abcdef0123456789abcdef");
        when(agentService.bindPersona("owner-a", "client-a", "owner-a", "wuyong"))
                .thenReturn(runtime);

        var result = new AgentPersonaProvisioningService(agentService, transactions, publisher)
                .bind(SCOPE, "wuyong", "local");

        assertEquals("local", result.getMode());
        verify(agentService).bindPersona("owner-a", "client-a", "owner-a", "wuyong");
        verifyNoInteractions(transactions, publisher);
    }

    @Test
    void suspendingRepairCompletesCrashWindowAndRepeatedRepairIsIdempotent() {
        AgentService agentService = mock(AgentService.class);
        AgentHostedBindingTransaction transactions = mock(AgentHostedBindingTransaction.class);
        AgentHostedProfilePublisher publisher = mock(AgentHostedProfilePublisher.class);
        AgentHostedProfileEntity suspending = hosted(AgentHostedProfileState.SUSPENDING, 4L);
        AgentHostedProfileEntity suspended = hosted(AgentHostedProfileState.SUSPENDED, 5L);
        AgentPersonaEntity persona = new AgentPersonaEntity();
        persona.setPersonaCode("wuyong");
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId(suspending.getCanonicalAgentId());
        Prepared first = new Prepared(suspending, runtime, persona, "dedicated-secret");
        Prepared second = new Prepared(suspended, runtime, persona, null);
        when(transactions.resumeRepair(SCOPE, 19L)).thenReturn(first, second);
        when(transactions.completeUnbind(SCOPE, 19L, 4L, 5L)).thenReturn(suspended);

        AgentPersonaProvisioningService service =
                new AgentPersonaProvisioningService(agentService, transactions, publisher);
        assertEquals(AgentHostedProfileState.SUSPENDED, service.repair(SCOPE, 19L).getHostedState());
        assertEquals(AgentHostedProfileState.SUSPENDED, service.repair(SCOPE, 19L).getHostedState());

        verify(publisher, times(1)).publish(suspending, persona, 4L, 5L, false, "dedicated-secret");
        verify(transactions, times(1)).completeUnbind(SCOPE, 19L, 4L, 5L);
        verify(transactions, never()).markRepair(any(), anyLong(), anyString(), any());
    }

    private static AgentHostedProfileEntity hosted(String state, long generation) {
        AgentHostedProfileEntity hosted = new AgentHostedProfileEntity();
        hosted.setId(7L);
        hosted.setBindingId(19L);
        hosted.setTenantId(SCOPE.tenantId());
        hosted.setClientId(SCOPE.clientId());
        hosted.setOwnerJiacn(SCOPE.ownerJiacn());
        hosted.setCanonicalAgentId("agt_0123456789abcdef0123456789abcdef");
        hosted.setPersonaCode("wuyong");
        hosted.setProfileKey(AgentHostedBindingTransaction.scopeDigest(SCOPE) + 19L);
        hosted.setApiKeyId("key-19");
        hosted.setLifecycleState(state);
        hosted.setGeneration(generation);
        hosted.setDesiredEnabled(false);
        return hosted;
    }
}
