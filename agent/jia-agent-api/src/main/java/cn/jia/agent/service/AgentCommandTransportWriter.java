package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;

/** REQUIRED transactional writer for one durable Agent command intent and its initial outbox row. */
public interface AgentCommandTransportWriter {
    AgentCommandTransportWriteResult write(AgentCommandDraft draft);
}
