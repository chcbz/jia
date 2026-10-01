package cn.jia.agent.service;

/** Domain manager authorization lives behind this port, not a reverse dependency on chat-service. */
public interface AgentPlatformSkillProvisioningPolicy {
    /** Acquire Agent identity root locks before this grant, then platform installation/transport locks. */
    void requireAllowed(String tenantId,String clientId,String ownerJiacn,String skillKey,boolean lock);
}
