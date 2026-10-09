package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandV2ExecutionLeaseFactEntity;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;

public interface AgentCommandV2ExecutionLeaseFactDao {
    AgentCommandV2ExecutionLeaseFactEntity lockLatest(CommandProducerScope producer,
            RuntimeReceiverScope receiver,
            String runtimeBindingId, String runtimeDispatchId);
}
