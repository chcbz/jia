package cn.jia.agent.service;

/** Live native text-file executor fact. Readiness is not an execution grant. */
public interface NativeTextFileRuntimeLookup {
    record Scope(String tenantId, String clientId, String ownerJiacn, String agentId) { }
    boolean ready(Scope scope);
}
