package cn.jia.economy.entity.skill;

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
    private String status;
    private String currentProductVersionId;
    private Long version;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long updateTime;
}
