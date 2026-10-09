package cn.jia.agent.service;

import cn.jia.agent.entity.AgentRuntimeV2RegistrationContext;
import cn.jia.agent.entity.AgentRuntimeV2SocketTuple;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Exact additive raw boundary. The authenticated handshake, nullable actual
 * committed socket and server-owned route are separate from untrusted bytes.
 * Route provenance is descriptive and never supplies recovery/execution ACL.
 */
public interface AgentRuntimeV2EffectService {
    record TrustedRoute(String sessionFence,String socketOwnerInstanceId,long socketOwnerEpoch) {
        public TrustedRoute {
            exact(sessionFence);exact(socketOwnerInstanceId);
            if(socketOwnerEpoch<0)throw new IllegalArgumentException("negative actual route epoch");
        }
        private static void exact(String value) {
            if(value==null||value.isBlank()||!value.equals(value.strip())||value.indexOf('\0')>=0||value.getBytes(StandardCharsets.UTF_8).length>100)throw new IllegalArgumentException("exact trusted route required");
        }
        public void requireSocket(AgentRuntimeV2SocketTuple socket) {
            if(socket!=null&&(!Objects.equals(sessionFence,socket.sessionFence())||!Objects.equals(socketOwnerInstanceId,socket.socketOwnerInstanceId())||socketOwnerEpoch!=socket.socketOwnerEpoch()))throw new SecurityException("actual route/socket mismatch");
        }
    }
    record Result(List<byte[]> responses,boolean close) {
        public Result { responses=List.copyOf(responses).stream().map(byte[]::clone).toList(); }
        @Override public List<byte[]> responses(){return responses.stream().map(byte[]::clone).toList();}
        public static Result closed(){return new Result(List.of(),true);}
    }
    Result inspect(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple actualSocket,TrustedRoute route,byte[] raw);
    Result effect(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple actualSocket,TrustedRoute route,byte[] raw);
    Result reconcile(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple actualSocket,TrustedRoute route,byte[] raw);
    /** After complete transaction cleanup, independently recheck current ACL
     * and result limits. Adapter MUST also re-admit its exact same Route/session
     * map/fence after this potentially blocking read before any network send.
     */
    boolean current(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple actualSocket,TrustedRoute route,byte[] originalResponse);
}
