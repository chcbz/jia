package cn.jia.agent.service;
/** Read-only in-memory evidence from an authenticated, successfully registered managed WS session.
 * No network I/O: money admission may consult it while holding the canonical runtime lock. */
public interface AgentManagedSessionLookup {
    boolean isReady(String tenantId,String clientId,String canonicalAgentId,String apiKeyId,byte[] registrationHash);
    /** Only after the caller's admission transaction commits; exact key/generation, never broadcast. */
    default cn.jia.agent.entity.AgentRawCommandDispatchResult dispatch(String tenantId,String clientId,
            String canonicalAgentId,String apiKeyId,byte[] registrationHash,byte[] wire) {
        return cn.jia.agent.entity.AgentRawCommandDispatchResult.rejected();
    }
}
