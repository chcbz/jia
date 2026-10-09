package cn.jia.agent.service;

import cn.jia.agent.entity.AgentRuntimeV2RegistrationContext;
import cn.jia.agent.entity.AgentRuntimeV2SocketTuple;

/** Persistence commits before this port returns; callers must not hold ambient transactions. */
public interface AgentRuntimeV2RegistrationService {
    record Result(byte[] responseBytes, AgentRuntimeV2SocketTuple currentSocket, AgentRuntimeV2SocketTuple replacedSocket) {
        public Result { responseBytes = responseBytes.clone(); }
        @Override public byte[] responseBytes() { return responseBytes.clone(); }
    }
    Result create(AgentRuntimeV2RegistrationContext identity, String fence, String owner, long epoch, byte[] raw);
    Result challenge(AgentRuntimeV2RegistrationContext identity, String fence, String owner, long epoch, byte[] raw);
    Result reconnect(AgentRuntimeV2RegistrationContext identity, String fence, String owner, long epoch, byte[] raw);
    boolean renew(AgentRuntimeV2RegistrationContext identity, AgentRuntimeV2SocketTuple socket);
    boolean disconnect(AgentRuntimeV2SocketTuple socket, String reason);
    int reapExpired(int limit);
}
