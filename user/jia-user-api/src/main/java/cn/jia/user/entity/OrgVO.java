package cn.jia.user.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Schema(name = "组织查询VO", description="")
public class OrgVO extends OrgEntity {
    private String nameLike;

    private String codeLike;

    private String remarkLike;

    private List<Long> userIds;

    private List<Long> orgIds;

    private String directorNames;
    
}
