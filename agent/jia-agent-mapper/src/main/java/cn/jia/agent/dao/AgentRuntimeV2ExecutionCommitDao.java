package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandV2AckReceiptEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionContinuationAnchorEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionGrantEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionLeaseFactEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionRequestEntity;
import cn.jia.agent.entity.AuthenticatedRuntimeSession;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;

/** DAO-owned atomic reducers for facts, projections, grants, anchors, and request mappings. */
public interface AgentRuntimeV2ExecutionCommitDao {
    AgentCommandV2ExecutionGrantEntity appendStarted(CommandProducerScope producer,
            RuntimeReceiverScope receiver, AuthenticatedRuntimeSession session,
            String runtimeDispatchId, AgentCommandV2AckReceiptEntity receipt,
            AgentCommandV2ExecutionLeaseFactEntity startFact,
            AgentCommandV2ExecutionGrantEntity startGrant,
            AgentCommandV2ExecutionContinuationAnchorEntity anchor);

    AgentCommandV2ExecutionGrantEntity continueExecution(CommandProducerScope producer,
            RuntimeReceiverScope receiver, AuthenticatedRuntimeSession session,
            AgentCommandV2ExecutionRequestEntity request,
            AgentCommandV2ExecutionLeaseFactEntity transferFact,
            AgentCommandV2ExecutionGrantEntity continuationGrant,
            AgentCommandV2ExecutionContinuationAnchorEntity anchor);

    AgentCommandV2ExecutionGrantEntity renewExecution(CommandProducerScope producer,
            RuntimeReceiverScope receiver, AuthenticatedRuntimeSession session,
            AgentCommandV2ExecutionRequestEntity request,
            AgentCommandV2ExecutionLeaseFactEntity renewalFact,
            AgentCommandV2ExecutionGrantEntity renewalGrant);
}
