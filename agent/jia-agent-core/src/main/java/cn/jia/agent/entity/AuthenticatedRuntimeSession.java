package cn.jia.agent.entity;

/** Server-authenticated current runtime connection tuple. */
public record AuthenticatedRuntimeSession(
        String runtimeBindingId,
        long bindingGeneration,
        String runtimeInstanceId,
        String sessionFence,
        String socketOwnerInstanceId,
        long socketOwnerEpoch) {

    public AuthenticatedRuntimeSession {
        if (runtimeBindingId == null || !runtimeBindingId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("runtimeBindingId is not a canonical server UUID");
        }
        if (bindingGeneration < 0 || socketOwnerEpoch < 0) {
            throw new IllegalArgumentException("session revisions must be non-negative");
        }
        RuntimeV2ExactIdentity.require(runtimeInstanceId, 100, "runtimeInstanceId");
        RuntimeV2ExactIdentity.require(sessionFence, 100, "sessionFence");
        RuntimeV2ExactIdentity.require(socketOwnerInstanceId, 100, "socketOwnerInstanceId");
    }

}
