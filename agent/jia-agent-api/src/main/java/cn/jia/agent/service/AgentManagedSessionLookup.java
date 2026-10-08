package cn.jia.agent.service;

import cn.jia.agent.entity.AgentRawCommandDispatchResult;

/** Exact live transport evidence, never an API-key or immutable product-install binding. */
public interface AgentManagedSessionLookup {
    record SessionFence(String runtimeInstallationId, String hostId, String runtimeInstanceId,
                        long sessionGeneration) { }
    boolean isReady(String tenantId, String clientId, String canonicalAgentId, SessionFence fence);
    /** After commit only, one current authenticated session, never broadcast. */
    default AgentRawCommandDispatchResult dispatch(String tenantId, String clientId,
            String canonicalAgentId, SessionFence fence, byte[] wire) {
        return AgentRawCommandDispatchResult.rejected();
    }
}
