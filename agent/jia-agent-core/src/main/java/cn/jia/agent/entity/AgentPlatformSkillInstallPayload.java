package cn.jia.agent.entity;

/** Platform provisioning is not a marketplace order or entitlement. */
public record AgentPlatformSkillInstallPayload(
        int schemaVersion, String installationId, String bindingVersion,
        String skillKey, String skillVersion, String packageSha256,
        String challengeId, String packageRef) implements AgentCommandPayload { }
