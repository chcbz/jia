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
@TableName("user_auth")
@Schema(name = "Auth对象", description="")
public class AuthEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @Schema(description = "角色ID")
    private Integer roleId;

    @Schema(description = "权限ID")
    private Integer permsId;


}
