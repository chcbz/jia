package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentCommandAckV2;

/** DB-only protocol-v2 ACK boundary. Network/runtime authentication must happen before this call. */
public interface AgentCommandAckV2Service {
    AgentCommandAckResult acknowledge(AgentCommandAckV2 acknowledgement, long now);
}
