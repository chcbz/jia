package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.core.tenant.UserAdmissionScope;

/** REQUIRED transactional writer for one durable Agent command intent and its initial outbox row. */
public interface AgentCommandTransportWriter {
    AgentCommandTransportWriteResult write(AgentCommandDraft draft);

    /** Writes protocol v2 from a trusted signed user scope and locked deployment admission. */
    AgentCommandTransportWriteResult writeV2(
            UserAdmissionScope scope, AgentCommandDraft draft);

    /**
     * Writes one Hall command only after caller/target ownership and writable task membership
     * have been locked and revalidated in the same REQUIRED transaction as delivery/outbox.
     */
    AgentCommandTransportWriteResult writeAuthorizedHall(
            AgentCommandDraft draft, String callerAgentId);

    /** Protocol-v2 Hall writer; request fields cannot select tenant, domain, pool or deployment. */
    AgentCommandTransportWriteResult writeAuthorizedHallV2(
            UserAdmissionScope scope, AgentCommandDraft draft, String callerAgentId);
}
