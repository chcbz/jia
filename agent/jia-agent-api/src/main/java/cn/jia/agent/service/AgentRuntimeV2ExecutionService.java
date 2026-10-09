package cn.jia.agent.service;

import cn.jia.agent.entity.*;
import java.util.*;

/** Authenticated socket context plus exact raw bytes. The body supplies no authority or server time. */
public interface AgentRuntimeV2ExecutionService {
    record Result(List<byte[]> responses,boolean close) {
        public Result {responses=responses.stream().map(byte[]::clone).toList();}
        @Override public List<byte[]> responses(){return responses.stream().map(byte[]::clone).toList();}
        public static Result closed(){return new Result(List.of(),true);}
    }
    Result acknowledge(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] raw);
    Result continueExecution(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] raw);
    Result renewExecution(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] raw);
    /** Non-mutating response-time check; stale grant outcomes are never sent as a new authority. */
    boolean current(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] grantBytes);
}
