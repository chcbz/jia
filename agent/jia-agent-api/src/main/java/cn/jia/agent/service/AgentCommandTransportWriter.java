package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;

/** REQUIRED transactional writer for one durable Agent command intent and its initial outbox row. */
public interface AgentCommandTransportWriter {
    AgentCommandTransportWriteResult write(AgentCommandDraft draft);

    /**
     * Writes one Hall command only after caller/target ownership and writable task membership
     * have been locked and revalidated in the same REQUIRED transaction as delivery/outbox.
     */
    AgentCommandTransportWriteResult writeAuthorizedHall(
            AgentCommandDraft draft, String callerAgentId);
}
