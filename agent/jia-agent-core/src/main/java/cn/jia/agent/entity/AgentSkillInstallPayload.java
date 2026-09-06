package cn.jia.agent.entity;
/** W10 frozen install identity. Order ID occupies the transport's correlation/aggregate slot, not a task admission. */
public record AgentSkillInstallPayload(String orderId,String installationId,String productVersionId,
        String skillKey,String skillVersion,String packageSize,String packageDigest,String downloadPath)
        implements AgentCommandPayload { }
