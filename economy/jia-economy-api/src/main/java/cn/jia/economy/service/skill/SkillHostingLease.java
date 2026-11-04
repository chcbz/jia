package cn.jia.economy.service.skill;
import lombok.Data;
import lombok.experimental.Accessors;
@Data @Accessors(chain=true)
public class SkillHostingLease {
    private String agentId;
    private String bindingId;
    private String status;
}
