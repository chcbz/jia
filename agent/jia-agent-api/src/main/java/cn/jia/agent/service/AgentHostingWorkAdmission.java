package cn.jia.agent.service;

/** Denies only NEW work for managed rent leases; never cancels/stops running work or deletes data. */
public interface AgentHostingWorkAdmission {
    void requireNewWork(String tenantId, String clientId, String canonicalAgentId);
}
