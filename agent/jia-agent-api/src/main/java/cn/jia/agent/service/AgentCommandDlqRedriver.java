package cn.jia.agent.service;

import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;

/** Bounded broker-side DLQ acquisition, validation, confirmed republish, and settlement boundary. */
public interface AgentCommandDlqRedriver {
    AgentRabbitPublishResult redrive(
            AgentConfirmedPublishRequest expected, long confirmTimeoutMillis, int scanLimit);
}
