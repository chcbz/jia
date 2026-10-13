package cn.jia.economy.entity.skill;

import lombok.Data;

/** Immutable product-install delivery ownership. Runtime session generations are transport only.
 * Legacy key/hash fields are nullable audit evidence, never current authorization. */
@Data
public class SkillDeliveryBindingEntity {
    private Long escrowVersion;
    private String canonicalAgentId;
    private String runtimeInstallationId;
    private String runtimeHostId;
    private String apiKeyId;
    private byte[] registrationHash;
}
