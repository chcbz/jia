package cn.jia.agent.service;

import cn.jia.agent.entity.AgentRuntimeV2SocketTuple;

/** Local routing only. Database authority belongs to the composite/relay transaction. */
public interface AgentRuntimeV2SocketGateway {
    String ownerInstanceId();
    boolean current(AgentRuntimeV2SocketTuple tuple);
    boolean send(AgentRuntimeV2SocketTuple tuple,byte[] originalOuter,long deadlineAt);
}
