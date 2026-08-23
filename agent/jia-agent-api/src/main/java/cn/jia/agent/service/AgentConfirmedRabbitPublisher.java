package cn.jia.agent.service;

import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;

/** Reusable bounded correlated-confirm primitive for exact D04 manifest routes. */
public interface AgentConfirmedRabbitPublisher {
    AgentRabbitPublishResult publish(
            AgentConfirmedPublishRequest request, long confirmTimeoutMillis);
}
