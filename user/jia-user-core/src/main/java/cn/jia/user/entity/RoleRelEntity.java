package cn.jia.user.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

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
@TableName("user_role_rel")
@Schema(name = "RoleRel对象", description="")
public class RoleRelEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户组ID")
    private Long groupId;

    @Schema(description = "角色ID")
    private Long roleId;

}
