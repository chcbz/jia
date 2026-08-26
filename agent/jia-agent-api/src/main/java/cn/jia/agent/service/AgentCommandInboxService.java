package cn.jia.agent.service;

import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentInboxResult;

/** Durable server Inbox boundary for future D05 consumers. No Rabbit or WebSocket behavior lives here. */
public interface AgentCommandInboxService {
    AgentInboxClaim claim(AgentInboxMessage message, String leaseOwner, long now, long leaseMillis);

    AgentInboxResult complete(AgentInboxClaimToken token, AgentInboxDisposition disposition, long now);
}
