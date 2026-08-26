package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandAck;
import cn.jia.agent.entity.AgentCommandAckResult;

/** Durable monotonic Agent command ACK state machine. */
public interface AgentCommandAckService {
    AgentCommandAckResult acknowledge(AgentCommandAck ack, long now);
}
