package cn.jia.agent.service;

import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentRabbitPublishResult;

/** Rabbit network boundary. Implementations must publish outside database transactions. */
public interface AgentOutboxPublisher {
    AgentRabbitPublishResult publish(AgentOutboxClaimToken token, long confirmTimeoutMillis);
}
