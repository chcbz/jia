package cn.jia.user.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("user_org_rel")
@Schema(name = "OrgRel对象", description="")
public class OrgRel extends BaseEntity {

    private static final long serialVersionUID=1L;

    @Schema(description = "用户ID")
    private Integer userId;

    @Schema(description = "组织ID")
    private Integer orgId;


}
