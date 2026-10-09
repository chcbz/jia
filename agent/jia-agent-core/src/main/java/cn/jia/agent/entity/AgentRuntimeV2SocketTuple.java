package cn.jia.agent.entity;

/** Six-field fence. A provisional socket uses the requested binding/generation only as hints. */
public record AgentRuntimeV2SocketTuple(String runtimeBindingId, long bindingGeneration,
        String runtimeInstanceId, String sessionFence, String socketOwnerInstanceId, long socketOwnerEpoch) {
    public AgentRuntimeV2SocketTuple {
        AgentRuntimeV2RegistrationFingerprints.uuid(runtimeBindingId, "runtimeBindingId");
        AgentRuntimeV2RegistrationFingerprints.nonnegative(bindingGeneration, "bindingGeneration");
        AgentRuntimeV2RegistrationFingerprints.uuid(runtimeInstanceId, "runtimeInstanceId");
        AgentRuntimeV2RegistrationFingerprints.uuid(sessionFence, "sessionFence");
        AgentRuntimeV2RegistrationFingerprints.uuid(socketOwnerInstanceId, "socketOwnerInstanceId");
        AgentRuntimeV2RegistrationFingerprints.nonnegative(socketOwnerEpoch, "socketOwnerEpoch");
    }
}
