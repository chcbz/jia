package cn.jia.economy.entity.skill;
import lombok.Data;
import lombok.experimental.Accessors;
@Data @Accessors(chain=true)
public class SkillManagedCredentialEntity {
    private String credentialId;
    private String apiKeyId;
    private String agentId;
    private Long bindingId;
    private Long version;
    private String tenantId;
    private String clientId;
}
