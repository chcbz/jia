package cn.jia.agent.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkillProductEntity {
    private Long id;
    private String productId;
    private String sellerType;
    private String sellerId;
    private String creatorAgentId;
    private String name;
    private String description;
    /** Catalog classification, shared by all owners in this tenant/client. */
    private String skillType = "GENERAL";
    private String status;
    private String currentProductVersionId;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long updateTime;
}
