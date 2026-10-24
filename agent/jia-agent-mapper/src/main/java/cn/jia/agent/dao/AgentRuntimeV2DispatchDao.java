package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;

public interface AgentRuntimeV2DispatchDao {
    /** Returns the server-generated opaque dispatch id, or {@code null} when the fenced CAS lost. */
    String prepare(CommandProducerScope producer, RuntimeReceiverScope receiver,
            long inboxId, long expectedVersion,
            String runtimeBindingId, long bindingGeneration, String runtimeInstanceId,
            long preparedAt);
    AgentConsumerInboxEntity lock(CommandProducerScope producer, RuntimeReceiverScope receiver,
            String runtimeBindingId, String runtimeDispatchId);
    int projectLease(CommandProducerScope producer, RuntimeReceiverScope receiver,
            AgentConsumerInboxEntity current, String holderBindingId,
            long holderBindingGeneration, String holderRuntimeInstanceId,
            String currentLeaseFactId, Long expectedLeaseRevision, long leaseRevision,
            long startedAt, long leaseUntil, long now);
}
