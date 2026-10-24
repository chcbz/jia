package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandV2ExecutionContinuationAnchorEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionGrantEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionLeaseFactEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionRequestEntity;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;

/** Stable-key, read-only audit access. None of these methods grants current execution authority. */
public interface AgentRuntimeV2ExecutionHistoryDao {
    AgentCommandV2ExecutionLeaseFactEntity findLeaseFact(CommandProducerScope producer,
            RuntimeReceiverScope receiver, String runtimeBindingId, String leaseFactId);
    AgentCommandV2ExecutionGrantEntity findGrant(CommandProducerScope producer,
            RuntimeReceiverScope receiver, String runtimeBindingId, String executionGrantId);
    AgentCommandV2ExecutionRequestEntity findRequest(CommandProducerScope producer,
            RuntimeReceiverScope receiver, String runtimeBindingId, String executionRequestId);
    AgentCommandV2ExecutionContinuationAnchorEntity findContinuationAnchor(
            CommandProducerScope producer, RuntimeReceiverScope receiver, String runtimeBindingId,
            String runtimeDispatchId, String startedReceiptId, long holderBindingGeneration);
}
