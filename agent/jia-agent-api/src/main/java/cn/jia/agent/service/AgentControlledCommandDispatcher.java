package cn.jia.agent.service;

import cn.jia.agent.entity.AgentRawCommandDispatchResult;

/** Domain ACL adapter for new controlled commands; never falls through to task membership. */
public interface AgentControlledCommandDispatcher {
    String commandType();
    AgentRawCommandDispatchResult dispatch(String tenant, String client, String owner,
            String resourceId, String targetAgentId, String commandId, byte[] exactWire);
}
