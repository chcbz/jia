package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandReconnectScope;

/** Non-blocking bounded reconnect hint. False means the bounded queue declined the signal. */
public interface AgentCommandReconnectSignal {
    boolean signalReconnect(AgentCommandReconnectScope scope);
}
