package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandMailboxPage;

/** Exact-scope bounded keyset query over the durable command mailbox. */
public interface AgentCommandMailboxService {
    AgentCommandMailboxPage query(
            String tenantId,
            String clientId,
            String callerAgentId,
            String targetAgentId,
            String taskId,
            Long beforeCreateTime,
            Long beforeId,
            int limit,
            boolean includeTerminal);
}
