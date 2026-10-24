package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandV2AckReceiptEntity;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;

public interface AgentCommandV2AckReceiptDao {
    AgentCommandV2AckReceiptEntity findByAckMessageId(
            CommandProducerScope producer, RuntimeReceiverScope receiver, String ackMessageId);
    AgentCommandV2AckReceiptEntity lockCommitted(CommandProducerScope producer,
            RuntimeReceiverScope receiver,
            String runtimeBindingId, String runtimeDispatchId, String status);
}
