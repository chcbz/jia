package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentRuntimeV2BindingEntity;
import cn.jia.agent.entity.AgentRuntimeV2BindingGenerationEvent;
import cn.jia.agent.entity.AgentRuntimeV2BindingGenerationFactEntity;
import cn.jia.agent.entity.AuthenticatedRuntimeSession;
import cn.jia.agent.entity.RuntimeReceiverScope;

public interface AgentRuntimeV2BindingGenerationFactDao {
    AgentRuntimeV2BindingGenerationFactEntity createVersion2(RuntimeReceiverScope scope,
            AgentRuntimeV2BindingEntity binding, AuthenticatedRuntimeSession initialSession,
            byte[] reconnectVerifier, long effectiveAt);
    AgentRuntimeV2BindingGenerationFactEntity append(RuntimeReceiverScope scope,
            AuthenticatedRuntimeSession expectedSession, AgentRuntimeV2BindingGenerationEvent event,
            AuthenticatedRuntimeSession nextSession, Long nextSocketOwnerLeaseUntil, long effectiveAt, String reason);
    AgentRuntimeV2BindingGenerationFactEntity lockLatest(RuntimeReceiverScope scope,
            String runtimeBindingId);

    /** Immutable audit read only; the returned row never grants current routing authority. */
    AgentRuntimeV2BindingGenerationFactEntity findHistoricalByFactId(RuntimeReceiverScope currentIdentity,
            String runtimeBindingId, String bindingGenerationFactId);

    /** Immutable audit read only; mutable receiver revisions are not lookup predicates. */
    AgentRuntimeV2BindingGenerationFactEntity findHistoricalConnectedGeneration(
            RuntimeReceiverScope currentIdentity, String runtimeBindingId, long bindingGeneration);
}
