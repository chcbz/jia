package cn.jia.agent.service;

import cn.jia.agent.entity.AgentRawCommandDispatchResult;

/**
 * Exact tenant/client/task/target WebSocket boundary for already-authorized command bytes.
 * Implementations must send the supplied bytes without JSON reserialization or compatibility wrapping.
 */
public interface AgentRawCommandDispatcher {
    AgentRawCommandDispatchResult dispatchExactRawCommand(
            String tenantId,
            String clientId,
            String taskId,
            String targetAgentId,
            byte[] rawWireBytes);
}
