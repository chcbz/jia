package cn.jia.agent.service;

import cn.jia.agent.entity.AgentRawCommandDispatchResult;

/**
 * Exact tenant/client/task/target WebSocket boundary for already-authorized command bytes.
 * Implementations must send the supplied bytes without JSON reserialization or compatibility wrapping.
 */
public interface AgentRawCommandDispatcher {
    /** Exact registered-session presence used by D06 immediately before any reissue transaction. */
    boolean isExactAgentConnected(String tenantId, String clientId, String targetAgentId);

    AgentRawCommandDispatchResult dispatchExactRawCommand(
            String tenantId,
            String clientId,
            String taskId,
            String targetAgentId,
            byte[] rawWireBytes);
}
