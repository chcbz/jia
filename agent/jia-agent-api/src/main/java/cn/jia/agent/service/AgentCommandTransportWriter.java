package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.output.dto.OutputContextDTO;

/** REQUIRED transactional writer for one durable Agent command intent and its initial outbox row. */
public interface AgentCommandTransportWriter {
    AgentCommandTransportWriteResult write(AgentCommandDraft draft);

    /** Writes a command with server-established output context in its first frozen wire image. */
    default AgentCommandTransportWriteResult write(
            AgentCommandDraft draft, OutputContextDTO trustedOutputContext) {
        if (trustedOutputContext != null) {
            throw new IllegalStateException("trusted output context is unsupported by this writer");
        }
        return write(draft);
    }

    /**
     * Writes one Hall command only after caller/target ownership and writable task membership
     * have been locked and revalidated in the same REQUIRED transaction as delivery/outbox.
     */
    AgentCommandTransportWriteResult writeAuthorizedHall(
            AgentCommandDraft draft, String callerAgentId);
}
