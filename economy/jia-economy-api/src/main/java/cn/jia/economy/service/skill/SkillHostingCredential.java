package cn.jia.economy.service.skill;
import lombok.Data;
import lombok.experimental.Accessors;
@Data @Accessors(chain=true)
public class SkillHostingCredential {
    private String intentId;
    private String leaseId;
    private String principalType;
    private String principalId;
    private String managedApiKeyId;
}
