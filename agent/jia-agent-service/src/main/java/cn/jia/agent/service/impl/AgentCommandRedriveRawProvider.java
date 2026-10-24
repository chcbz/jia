package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentConfirmedPublishRequest;

import java.util.Map;

/** Creates already-physical, non-recovering one-shot sessions; callers never receive a factory. */
public interface AgentCommandRedriveRawProvider {
    AgentCommandRedriveSourceSession openSource() throws Exception;

    AgentCommandRedrivePublishAttempt preparePublish(
            AgentConfirmedPublishRequest request,
            Map<String, Object> preservedHeaders) throws Exception;
}
